package com.smartfirehub.pipeline.repository;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.UpdateTriggerRequest;
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

@Repository
@RequiredArgsConstructor
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Transactional
public class TriggerRepository {

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

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
        r.get(T_CREATED_AT));
  }

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
            T_CREATED_AT)
        .from(PIPELINE_TRIGGER)
        .where(T_PIPELINE_ID.eq(pipelineId))
        .orderBy(T_ID.asc())
        .fetch(this::mapToResponse);
  }

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
            T_CREATED_AT)
        .from(PIPELINE_TRIGGER)
        .where(T_ID.eq(id))
        .fetchOptional(this::mapToResponse);
  }

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
            T_CREATED_AT)
        .from(PIPELINE_TRIGGER)
        .where(T_TRIGGER_TYPE.eq(triggerType))
        .and(T_IS_ENABLED.eq(true))
        .fetch(this::mapToResponse);
  }

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
            T_CREATED_AT)
        .from(PIPELINE_TRIGGER)
        .where(T_TRIGGER_TYPE.eq("PIPELINE_CHAIN"))
        .and(T_IS_ENABLED.eq(true))
        .and(
            field("({0}->>'upstreamPipelineId')::bigint", Long.class, T_CONFIG)
                .eq(upstreamPipelineId))
        .fetch(this::mapToResponse);
  }

  /**
   * 활성 PIPELINE_CHAIN 연쇄 간선 한 줄 — 트리거 id, 그 트리거가 실행하는(하류) 파이프라인 id, config 의 상류 id 원문.
   *
   * <p>상류 id 는 원문(text)으로 돌려준다. SQL 에서 {@code ::bigint} 로 바꾸면 숫자로 읽을 수 없는 값 하나가 조회 전체를 실패시키므로, 해석은
   * 저장 시 검사와 같은 규칙으로 서비스가 한다.
   */
  public record ChainEdgeRow(long triggerId, long pipelineId, String upstreamPipelineId) {}

  /**
   * 이 테넌트의 연쇄 그래프 변경(새 활성 연쇄 간선을 더하는 생성·수정·켜기)을 직렬화하는 트랜잭션 단위 advisory 잠금(WD-239). 커밋·롤백 때 풀린다.
   *
   * <p>왜: 순환 검사({@link #findEnabledChainEdges} 로 읽어 판정)와 저장이 잠금 없이 분리돼 있으면, F←G 와 G←F 를 동시에 저장하는 두
   * 트랜잭션이 서로의 아직 커밋되지 않은 간선을 못 보고 둘 다 통과해 순환이 저장된다. 검사 직전에 이 잠금을 잡으면 둘째 트랜잭션은 첫째가 커밋할 때까지 기다리고, 그
   * 뒤의 간선 조회는 READ COMMITTED 의 문장 단위 스냅샷이라 첫째의 간선을 본다.
   *
   * <p>{@code MANDATORY}: 호출자의 저장 트랜잭션에 합류해야만 의미가 있다 — 트랜잭션 없이 부르면 문장이 끝나자마자 풀려 아무것도 막지 못하므로 그 실수를
   * 예외로 드러낸다. 키는 접두어를 붙인 문자열 해시다 — 다른 테넌트 단위 잠금(member-admin-guard:, security_level_defs_t 등)과 키
   * 공간을 나눈다.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockChainGraph(long tenantId) {
    dsl.execute(
        "SELECT pg_advisory_xact_lock(hashtextextended({0}, 0))",
        val("pipeline-chain-graph:" + tenantId));
  }

  /**
   * 현재 테넌트(RLS)의 활성 PIPELINE_CHAIN 간선을 한 번에 읽는다(WD-106). 저장 시 순환·길이 검사가 노드마다 조회하지 않고(N+1) 메모리 그래프로
   * 판정하기 위한 것이다.
   */
  public List<ChainEdgeRow> findEnabledChainEdges() {
    return dsl.select(
            T_ID, T_PIPELINE_ID, field("{0}->>'upstreamPipelineId'", String.class, T_CONFIG))
        .from(PIPELINE_TRIGGER)
        .where(T_TRIGGER_TYPE.eq("PIPELINE_CHAIN"))
        .and(T_IS_ENABLED.eq(true))
        .fetch(r -> new ChainEdgeRow(r.value1(), r.value2(), r.value3()));
  }

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

  public void delete(Long id) {
    dsl.deleteFrom(PIPELINE_TRIGGER).where(T_ID.eq(id)).execute();
  }

  public void updateEnabled(Long id, boolean enabled) {
    dsl.update(PIPELINE_TRIGGER)
        .set(T_IS_ENABLED, enabled)
        .set(T_UPDATED_AT, LocalDateTime.now())
        .where(T_ID.eq(id))
        .execute();
  }

  public void updateTriggerState(Long id, Map<String, Object> state) {
    dsl.update(PIPELINE_TRIGGER)
        .set(T_TRIGGER_STATE, toJsonb(state))
        .set(T_UPDATED_AT, LocalDateTime.now())
        .where(T_ID.eq(id))
        .execute();
  }

  /**
   * {@code trigger_state.nextFireTime} 을 읽기-수정-쓰기로 병합한다(#676).
   *
   * <p>{@code REQUIRES_NEW} 인 이유: 이 메서드는 {@code TriggerService.createTrigger}/{@code updateTrigger}
   * 의 {@code TransactionSynchronization.afterCommit()} 콜백(= {@code
   * TriggerSchedulerService.registerSchedule}) 안에서 호출된다. afterCommit 시점에는 방금 커밋된 트랜잭션의 리소스(커넥션)가 아직
   * 스레드에 바인딩된 채라, 클래스 레벨 기본값인 {@code REQUIRED} 로는 이미 완료된 그 트랜잭션에 "참여"하게 되어 이 update 가 예외 없이 조용히
   * 반영되지 않는다(#676 구현 중 실측: 로그·리턴값 모두 정상인데 DB에는 반영 안 됨). {@code REQUIRES_NEW} 로 강제로 새 물리 트랜잭션·커넥션을
   * 열어야 실제로 커밋된다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void mergeNextFireTime(Long id, String nextFireTimeIso) {
    findById(id)
        .ifPresent(
            trigger -> {
              Map<String, Object> updatedState = new HashMap<>(trigger.triggerState());
              updatedState.put("nextFireTime", nextFireTimeIso);
              updateTriggerState(id, updatedState);
            });
  }

  /**
   * {@code trigger_state.nextFireTime} 을 지운다(WD-107).
   *
   * <p>스케줄을 걸 수 없거나(다음 발화가 없는 cron, 커밋 후 등록 실패) 스케줄이 없는 상태로 남을 때, 화면의 "다음 실행"에 옛 시각이 거짓으로 남지 않게 하기
   * 위함이다. {@link #mergeNextFireTime} 과 같은 이유로 afterCommit 콜백 안에서 불리므로 {@code REQUIRES_NEW} 로 연다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void clearNextFireTime(Long id) {
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

  /**
   * 데이터 변경(DATASET_CHANGE) 트리거들의 감시 목록에서 데이터셋 하나를 뺀다(WD-221).
   *
   * <p>데이터셋이 삭제될 때와, 폴러가 이미 없는 감시 대상을 발견했을 때 부른다. 감시 목록({@code config.datasetIds})과 폴러의 행 수
   * 스냅숏({@code trigger_state.lastSnapshot.<id>})에서 함께 지운다 — 남겨 두면 폴러가 매 주기 「행 수 0 ≠ 직전값」으로 보고 같은 삭제
   * 데이터셋으로 파이프라인을 계속 실행했고, 편집 창에는 지워진 id 가 「열람 권한 없음」 칩으로 남아 권한 문제처럼 보였다. 지운 뒤 감시 대상이 하나도 남지 않으면 같은
   * 문장에서 트리거를 끈다(꺼진 이유는 조회 응답의 {@code watchedDatasetsMissing} 이 「감시 목록이 비었음」으로 계산한다).
   *
   * <p>읽고-고쳐-쓰기 대신 SQL 한 문장으로 바꾸는 이유: 같은 시각에 폴러가 상태를 쓰거나 다른 감시 대상이 지워져도 서로의 변경을 덮지 않게 하기 위함이다.
   * {@code jsonb_agg} 는 남는 원소가 없으면 NULL 이라 빈 배열로 감싼다. UPDATE 의 SET 식은 모두 바뀌기 전 행 값을 읽는다.
   *
   * @return 바뀐 트리거 수
   */
  public int removeWatchedDataset(Long datasetId) {
    return dsl.execute(
        """
        UPDATE pipeline_trigger SET
          config = jsonb_set(config, '{datasetIds}', COALESCE(
            (SELECT jsonb_agg(e) FROM jsonb_array_elements(config -> 'datasetIds') e
              WHERE e <> to_jsonb(?::bigint)), '[]'::jsonb)),
          trigger_state = COALESCE(trigger_state, '{}'::jsonb) #- ARRAY['lastSnapshot', ?::text],
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
        datasetId,
        LocalDateTime.now(),
        datasetId);
  }

  /**
   * 폴러의 행 수 스냅숏을 지운다(WD-221) — 꺼져 있던 데이터 변경 트리거를 다시 켤 때 부른다.
   *
   * <p>꺼진 동안의 변경으로 켜자마자 발화하지 않게, 켠 뒤 첫 폴링이 기준선만 새로 잡도록 한다. {@code lastCheckedAt} 도 지워 그 기준선을 폴링 주기를
   * 기다리지 않고 다음 폴링에 바로 잡는다.
   */
  public void clearPollSnapshot(Long id) {
    dsl.execute(
        "UPDATE pipeline_trigger SET trigger_state = COALESCE(trigger_state, '{}'::jsonb)"
            + " - 'lastSnapshot' - 'lastCheckedAt' WHERE id = ?",
        id);
  }

  /**
   * 토큰 해시로 API 트리거를 찾는다. 켜짐 여부와 무관하게 찾는다(WD-152) — 맞는 토큰으로 꺼진 트리거를 부른 호출자에게 "토큰이 틀렸다"(401) 대신 "트리거가
   * 꺼져 있다"(409)를 알려 주기 위해서다. 켜짐 판정은 {@code TriggerService#fireTrigger} 한 곳에서만 한다.
   */
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
            T_CREATED_AT)
        .from(PIPELINE_TRIGGER)
        .where(T_TRIGGER_TYPE.eq("API"))
        .and(field("{0}->>'tokenHash'", String.class, T_CONFIG).eq(tokenHash))
        .fetchOptional(this::mapToResponse);
  }

  /** Find webhook trigger by webhookId. */
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
            T_CREATED_AT)
        .from(PIPELINE_TRIGGER)
        .where(T_TRIGGER_TYPE.eq("WEBHOOK"))
        .and(field("{0}->>'webhookId'", String.class, T_CONFIG).eq(webhookId))
        .fetchOptional(this::mapToResponse);
  }

  /** Check if there is a running execution for the given pipeline. */
  public boolean hasRunningExecution(Long pipelineId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(table(name("pipeline_execution")))
            .where(field(name("pipeline_execution", "pipeline_id"), Long.class).eq(pipelineId))
            .and(
                field(name("pipeline_execution", "status"), String.class)
                    .in("PENDING", "RUNNING")));
  }

  /** Check if pipeline is active. */
  public boolean isPipelineActive(Long pipelineId) {
    return dsl.select(field(name("pipeline", "is_active"), Boolean.class))
        .from(table(name("pipeline")))
        .where(field(name("pipeline", "id"), Long.class).eq(pipelineId))
        .fetchOptional(r -> r.get(field(name("pipeline", "is_active"), Boolean.class)))
        .orElse(false);
  }
}
