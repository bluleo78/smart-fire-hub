package com.smartfirehub.pipeline.repository.jooq;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.PipelineDeleteImpactResponse;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.UpdateTriggerRequest;
import com.smartfirehub.pipeline.repository.TriggerRepository;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** TriggerRepository 의 jOOQ(PostgreSQL) 구현 — WD-485. */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Transactional
public class JooqTriggerRepository implements TriggerRepository {

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  /** 체인 그래프 직렬화 잠금 — PG advisory 호출을 플랫폼 잠금 추상화로 위임한다(WD-486). */
  private final PlatformLocks locks;

  private static final Table<?> PIPELINE_TRIGGER = table(name("pipeline_trigger"));
  private static final Field<Long> T_ID = field(name("pipeline_trigger", "id"), Long.class);
  private static final Field<Long> T_PIPELINE_ID =
      field(name("pipeline_trigger", "pipeline_id"), Long.class);
  private static final Field<String> T_TRIGGER_TYPE =
      field(name("pipeline_trigger", "trigger_type"), String.class);
  private static final Field<String> T_NAME = field(name("pipeline_trigger", "name"), String.class);
  private static final Field<String> T_DESCRIPTION =
      field(name("pipeline_trigger", "description"), String.class);
  private static final Field<Boolean> T_IS_ENABLED =
      field(name("pipeline_trigger", "is_enabled"), Boolean.class);
  private static final Field<JSONB> T_CONFIG =
      field(name("pipeline_trigger", "config"), JSONB.class);
  private static final Field<JSONB> T_TRIGGER_STATE =
      field(name("pipeline_trigger", "trigger_state"), JSONB.class);
  private static final Field<Long> T_CREATED_BY =
      field(name("pipeline_trigger", "created_by"), Long.class);
  private static final Field<Long> T_UPDATED_BY =
      field(name("pipeline_trigger", "updated_by"), Long.class);
  private static final Field<LocalDateTime> T_CREATED_AT =
      field(name("pipeline_trigger", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> T_UPDATED_AT =
      field(name("pipeline_trigger", "updated_at"), LocalDateTime.class);

  private Map<String, Object> parseJsonb(JSONB jsonb) {
    if (jsonb == null || jsonb.data() == null) {
      return Collections.emptyMap();
    }
    try {
      return objectMapper.readValue(jsonb.data(), new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      return Collections.emptyMap();
    }
  }

  private JSONB toJsonb(Map<String, Object> map) {
    if (map == null || map.isEmpty()) {
      return JSONB.valueOf("{}");
    }
    try {
      return JSONB.valueOf(objectMapper.writeValueAsString(map));
    } catch (Exception e) {
      return JSONB.valueOf("{}");
    }
  }

  private TriggerResponse mapToResponse(org.jooq.Record r) {
    Map<String, Object> triggerState = parseJsonb(r.get(T_TRIGGER_STATE));
    // nextFireTime 은 trigger_state(jsonb) 안에 저장돼 있다 (#676) — DTO 최상위 필드로 꺼내
    // 프론트엔드가 별도 파싱 없이 trigger.nextFireTime 으로 바로 읽을 수 있게 한다.
    // 비활성 트리거는 실행되지 않으므로 "다음 실행"을 내보내지 않는다(WD-114). 끌 때 trigger_state 의 값은 지우지 않고(이미 꺼진
    // 옛 행, 끄는 순간 돌던 발화가 끝나며 다시 쓴 값 포함) 읽을 때 한 곳에서 거른다 — 최상위 필드뿐 아니라 triggerState 원본에서도
    // 빼야 원본 JSON 을 그대로 읽는 AI 에이전트 등 모든 소비자가 같은 답을 본다. 다시 켜면 등록 시 새 값으로 덮어쓴다.
    boolean isEnabled = r.get(T_IS_ENABLED);
    if (!isEnabled) {
      triggerState.remove("nextFireTime");
    }
    Object nextFireTimeValue = triggerState.get("nextFireTime");
    return new TriggerResponse(
        r.get(T_ID),
        r.get(T_PIPELINE_ID),
        r.get(T_TRIGGER_TYPE),
        r.get(T_NAME),
        r.get(T_DESCRIPTION),
        isEnabled,
        parseJsonb(r.get(T_CONFIG)),
        triggerState,
        nextFireTimeValue == null ? null : nextFireTimeValue.toString(),
        r.get(T_CREATED_BY),
        r.get(T_CREATED_AT),
        r.get(T_UPDATED_AT));
  }

  @Override
  public List<TriggerResponse> findByPipelineId(Long pipelineId) {
    return dsl.select(
            T_ID,
            T_PIPELINE_ID,
            T_TRIGGER_TYPE,
            T_NAME,
            T_DESCRIPTION,
            T_IS_ENABLED,
            T_CONFIG,
            T_TRIGGER_STATE,
            T_CREATED_BY,
            T_CREATED_AT,
            T_UPDATED_AT)
        .from(PIPELINE_TRIGGER)
        .where(T_PIPELINE_ID.eq(pipelineId))
        .orderBy(T_ID.asc())
        .fetch(this::mapToResponse);
  }

  @Override
  public Optional<TriggerResponse> findById(Long id) {
    return dsl.select(
            T_ID,
            T_PIPELINE_ID,
            T_TRIGGER_TYPE,
            T_NAME,
            T_DESCRIPTION,
            T_IS_ENABLED,
            T_CONFIG,
            T_TRIGGER_STATE,
            T_CREATED_BY,
            T_CREATED_AT,
            T_UPDATED_AT)
        .from(PIPELINE_TRIGGER)
        .where(T_ID.eq(id))
        .fetchOptional(this::mapToResponse);
  }

  @Override
  public List<TriggerResponse> findEnabledByType(String triggerType) {
    return dsl.select(
            T_ID,
            T_PIPELINE_ID,
            T_TRIGGER_TYPE,
            T_NAME,
            T_DESCRIPTION,
            T_IS_ENABLED,
            T_CONFIG,
            T_TRIGGER_STATE,
            T_CREATED_BY,
            T_CREATED_AT,
            T_UPDATED_AT)
        .from(PIPELINE_TRIGGER)
        .where(T_TRIGGER_TYPE.eq(triggerType))
        .and(T_IS_ENABLED.eq(true))
        .fetch(this::mapToResponse);
  }

  @Override
  public List<TriggerResponse> findEnabledChainTriggersByUpstreamId(Long upstreamPipelineId) {
    return dsl.select(
            T_ID,
            T_PIPELINE_ID,
            T_TRIGGER_TYPE,
            T_NAME,
            T_DESCRIPTION,
            T_IS_ENABLED,
            T_CONFIG,
            T_TRIGGER_STATE,
            T_CREATED_BY,
            T_CREATED_AT,
            T_UPDATED_AT)
        .from(PIPELINE_TRIGGER)
        .where(T_TRIGGER_TYPE.eq("PIPELINE_CHAIN"))
        .and(T_IS_ENABLED.eq(true))
        .and(
            field("({0}->>'upstreamPipelineId')::bigint", Long.class, T_CONFIG)
                .eq(upstreamPipelineId))
        .fetch(this::mapToResponse);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void lockChainGraph(long tenantId) {
    locks.xactLock("pipeline-chain-graph:" + tenantId);
  }

  @Override
  public List<ChainEdgeRow> findEnabledChainEdges() {
    return dsl.select(
            T_ID, T_PIPELINE_ID, field("{0}->>'upstreamPipelineId'", String.class, T_CONFIG))
        .from(PIPELINE_TRIGGER)
        .where(T_TRIGGER_TYPE.eq("PIPELINE_CHAIN"))
        .and(T_IS_ENABLED.eq(true))
        .fetch(r -> new ChainEdgeRow(r.value1(), r.value2(), r.value3()));
  }

  @Override
  public Long create(
      Long pipelineId, CreateTriggerRequest req, Map<String, Object> config, Long userId) {
    return dsl.insertInto(PIPELINE_TRIGGER)
        .set(T_PIPELINE_ID, pipelineId)
        .set(T_TRIGGER_TYPE, req.triggerType().name())
        .set(T_NAME, req.name())
        .set(T_DESCRIPTION, req.description())
        .set(T_CONFIG, toJsonb(config))
        .set(T_CREATED_BY, userId)
        .returning(T_ID)
        .fetchOne(r -> r.get(T_ID));
  }

  /** 수정 시각을 행 잠금과 함께 읽는다 — 편집 충돌 비교용(WD-576). 계약은 {@link TriggerRepository#lockUpdatedAtById}. */
  @Override
  public Optional<LockedRowVersion> lockUpdatedAtById(Long id) {
    return dsl.select(T_UPDATED_AT)
        .from(PIPELINE_TRIGGER)
        .where(T_ID.eq(id))
        .forUpdate()
        .fetchOptional()
        .map(r -> new LockedRowVersion(r.value1()));
  }

  @Override
  public void update(Long id, UpdateTriggerRequest req, Long userId) {
    var query =
        dsl.update(PIPELINE_TRIGGER)
            .set(T_UPDATED_AT, LocalDateTime.now())
            .set(T_UPDATED_BY, userId);

    if (req.name() != null) {
      query = query.set(T_NAME, req.name());
    }
    // description 이 null 이면 "바꾸지 않음"(부분 수정 — AI 에이전트 등), 빈 문자열이면 "지움"으로 본다(WD-113).
    // 예전엔 웹이 빈 설명을 키째 빼고 보내 null 과 구분이 안 돼, 설명을 지우고 저장해도 옛 설명이 남았다.
    if (req.description() != null) {
      query = query.set(T_DESCRIPTION, req.description().isBlank() ? null : req.description());
    }
    if (req.isEnabled() != null) {
      query = query.set(T_IS_ENABLED, req.isEnabled());
    }
    if (req.config() != null) {
      query = query.set(T_CONFIG, toJsonb(req.config()));
    }

    query.where(T_ID.eq(id)).execute();
  }

  @Override
  public int delete(Long id) {
    return dsl.deleteFrom(PIPELINE_TRIGGER).where(T_ID.eq(id)).execute();
  }

  @Override
  public void updateEnabled(Long id, boolean enabled) {
    dsl.update(PIPELINE_TRIGGER)
        .set(T_IS_ENABLED, enabled)
        .set(T_UPDATED_AT, LocalDateTime.now())
        .where(T_ID.eq(id))
        .execute();
  }

  /**
   * 실행 상태만 바꾼다. 수정 시각은 건드리지 않는다(WD-576) — 스케줄 발화·데이터 변경 폴링마다 불리므로, 여기서 {@code updated_at} 을 바꾸면 편집
   * 창을 열어 둔 사이에 트리거가 한 번 돌기만 해도 저장이 편집 충돌로 거부된다. {@code updated_at} 은 사람이 고친
   * 정의(이름·설명·config·켜기/끄기)의 수정 시각이다.
   */
  @Override
  public void updateTriggerState(Long id, Map<String, Object> state) {
    dsl.update(PIPELINE_TRIGGER).set(T_TRIGGER_STATE, toJsonb(state)).where(T_ID.eq(id)).execute();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void mergeNextFireTime(Long id, String nextFireTimeIso) {
    doMergeNextFireTime(id, nextFireTimeIso);
  }

  /** 바깥 트랜잭션에 참여(MANDATORY)해 같은 커넥션으로 쓴다 — 커넥션 1개 원칙(WD-554). */
  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void mergeNextFireTimeInTransaction(Long id, String nextFireTimeIso) {
    doMergeNextFireTime(id, nextFireTimeIso);
  }

  private void doMergeNextFireTime(Long id, String nextFireTimeIso) {
    findById(id)
        .ifPresent(
            trigger -> {
              Map<String, Object> updatedState = new HashMap<>(trigger.triggerState());
              updatedState.put("nextFireTime", nextFireTimeIso);
              updateTriggerState(id, updatedState);
            });
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void clearNextFireTime(Long id) {
    doClearNextFireTime(id);
  }

  /** 바깥 트랜잭션에 참여(MANDATORY)해 같은 커넥션으로 쓴다 — 커넥션 1개 원칙(WD-554). */
  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void clearNextFireTimeInTransaction(Long id) {
    doClearNextFireTime(id);
  }

  private void doClearNextFireTime(Long id) {
    findById(id)
        .ifPresent(
            trigger -> {
              if (trigger.triggerState() == null
                  || !trigger.triggerState().containsKey("nextFireTime")) {
                return;
              }
              Map<String, Object> updatedState = new HashMap<>(trigger.triggerState());
              updatedState.remove("nextFireTime");
              updateTriggerState(id, updatedState);
            });
  }

  @Override
  public List<PipelineDeleteImpactResponse.DownstreamChainTrigger>
      findEnabledDownstreamChainTriggers(Long upstreamPipelineId) {
    Table<?> pipeline = table(name("pipeline"));
    Field<Long> pId = field(name("pipeline", "id"), Long.class);
    Field<String> pName = field(name("pipeline", "name"), String.class);
    return dsl.select(T_ID, T_NAME, T_PIPELINE_ID, pName)
        .from(PIPELINE_TRIGGER)
        .join(pipeline)
        .on(pId.eq(T_PIPELINE_ID))
        .where(T_TRIGGER_TYPE.eq("PIPELINE_CHAIN"))
        .and(T_IS_ENABLED.eq(true))
        .and(T_PIPELINE_ID.ne(upstreamPipelineId))
        .and(
            field("({0}->>'upstreamPipelineId')::bigint", Long.class, T_CONFIG)
                .eq(upstreamPipelineId))
        .orderBy(pName, T_ID)
        .fetch(
            r ->
                new PipelineDeleteImpactResponse.DownstreamChainTrigger(
                    r.value1(), r.value2(), r.value3(), r.value4()));
  }

  @Override
  public int disableByUpstreamPipelineId(Long upstreamPipelineId) {
    return dsl.update(PIPELINE_TRIGGER)
        .set(T_IS_ENABLED, false)
        .set(T_UPDATED_AT, LocalDateTime.now())
        .where(T_TRIGGER_TYPE.eq("PIPELINE_CHAIN"))
        .and(
            field("({0}->>'upstreamPipelineId')::bigint", Long.class, T_CONFIG)
                .eq(upstreamPipelineId))
        .execute();
  }

  @Override
  public int removeWatchedDataset(Long datasetId) {
    return dsl.execute(
        """
        UPDATE pipeline_trigger SET
          config = jsonb_set(config, '{datasetIds}', COALESCE(
            (SELECT jsonb_agg(e) FROM jsonb_array_elements(config -> 'datasetIds') e
              WHERE e <> to_jsonb(?::bigint)), '[]'::jsonb)),
          trigger_state = COALESCE(trigger_state, '{}'::jsonb) #- ARRAY['lastSnapshot', ?::text]
            #- ARRAY['lastChangeSnapshot', ?::text] #- ARRAY['lastRelationSnapshot', ?::text],
          is_enabled = is_enabled AND EXISTS (
            SELECT 1 FROM jsonb_array_elements(config -> 'datasetIds') e
              WHERE e <> to_jsonb(?::bigint)),
          updated_at = ?
        WHERE trigger_type = 'DATASET_CHANGE'
          AND jsonb_typeof(config -> 'datasetIds') = 'array'
          AND (config -> 'datasetIds') @> to_jsonb(?::bigint)
        """,
        datasetId,
        String.valueOf(datasetId),
        String.valueOf(datasetId),
        String.valueOf(datasetId),
        datasetId,
        LocalDateTime.now(),
        datasetId);
  }

  @Override
  public java.util.Set<Long> findOutputDatasetIds(Long pipelineId) {
    return new java.util.HashSet<>(
        dsl.selectDistinct(field(name("pipeline_step", "output_dataset_id"), Long.class))
            .from(table(name("pipeline_step")))
            .where(field(name("pipeline_step", "pipeline_id"), Long.class).eq(pipelineId))
            .and(field(name("pipeline_step", "output_dataset_id"), Long.class).isNotNull())
            .fetch(0, Long.class));
  }

  @Override
  public void clearPollSnapshot(Long id) {
    dsl.execute(
        "UPDATE pipeline_trigger SET trigger_state = COALESCE(trigger_state, '{}'::jsonb)"
            + " - 'lastSnapshot' - 'lastChangeSnapshot' - 'lastRelationSnapshot' - 'lastCheckedAt'"
            + " WHERE id = ?",
        id);
  }

  @Override
  public Optional<TriggerResponse> findByTokenHash(String tokenHash) {
    return dsl.select(
            T_ID,
            T_PIPELINE_ID,
            T_TRIGGER_TYPE,
            T_NAME,
            T_DESCRIPTION,
            T_IS_ENABLED,
            T_CONFIG,
            T_TRIGGER_STATE,
            T_CREATED_BY,
            T_CREATED_AT,
            T_UPDATED_AT)
        .from(PIPELINE_TRIGGER)
        .where(T_TRIGGER_TYPE.eq("API"))
        .and(field("{0}->>'tokenHash'", String.class, T_CONFIG).eq(tokenHash))
        .fetchOptional(this::mapToResponse);
  }

  @Override
  public Optional<TriggerResponse> findByWebhookId(String webhookId) {
    return dsl.select(
            T_ID,
            T_PIPELINE_ID,
            T_TRIGGER_TYPE,
            T_NAME,
            T_DESCRIPTION,
            T_IS_ENABLED,
            T_CONFIG,
            T_TRIGGER_STATE,
            T_CREATED_BY,
            T_CREATED_AT,
            T_UPDATED_AT)
        .from(PIPELINE_TRIGGER)
        .where(T_TRIGGER_TYPE.eq("WEBHOOK"))
        .and(field("{0}->>'webhookId'", String.class, T_CONFIG).eq(webhookId))
        .fetchOptional(this::mapToResponse);
  }

  @Override
  public boolean hasRunningExecution(Long pipelineId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(table(name("pipeline_execution")))
            .where(field(name("pipeline_execution", "pipeline_id"), Long.class).eq(pipelineId))
            .and(
                field(name("pipeline_execution", "status"), String.class)
                    .in("PENDING", "RUNNING")));
  }

  @Override
  public boolean isPipelineActive(Long pipelineId) {
    return dsl.select(field(name("pipeline", "is_active"), Boolean.class))
        .from(table(name("pipeline")))
        .where(field(name("pipeline", "id"), Long.class).eq(pipelineId))
        .fetchOptional(r -> r.get(field(name("pipeline", "is_active"), Boolean.class)))
        .orElse(false);
  }
}
