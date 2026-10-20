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

  /** Find API trigger by token hash. */
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
        .and(T_IS_ENABLED.eq(true))
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
