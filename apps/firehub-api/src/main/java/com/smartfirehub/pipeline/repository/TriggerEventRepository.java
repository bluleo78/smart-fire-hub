package com.smartfirehub.pipeline.repository;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.pipeline.dto.TriggerEventResponse;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Transactional
public class TriggerEventRepository {

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  private static final Table<?> TRIGGER_EVENT = table(name("trigger_event"));
  private static final Field<Long> TE_ID = field(name("trigger_event", "id"), Long.class);
  private static final Field<Long> TE_TRIGGER_ID =
      field(name("trigger_event", "trigger_id"), Long.class);
  private static final Field<Long> TE_PIPELINE_ID =
      field(name("trigger_event", "pipeline_id"), Long.class);
  private static final Field<Long> TE_EXECUTION_ID =
      field(name("trigger_event", "execution_id"), Long.class);
  private static final Field<String> TE_EVENT_TYPE =
      field(name("trigger_event", "event_type"), String.class);
  private static final Field<JSONB> TE_DETAIL = field(name("trigger_event", "detail"), JSONB.class);
  private static final Field<LocalDateTime> TE_CREATED_AT =
      field(name("trigger_event", "created_at"), LocalDateTime.class);

  private static final Table<?> PIPELINE_TRIGGER = table(name("pipeline_trigger"));
  private static final Field<Long> T_ID = field(name("pipeline_trigger", "id"), Long.class);
  private static final Field<String> T_NAME = field(name("pipeline_trigger", "name"), String.class);
  private static final Field<String> T_TRIGGER_TYPE =
      field(name("pipeline_trigger", "trigger_type"), String.class);

  /**
   * 어떤 실행이 PIPELINE_CHAIN 연쇄의 몇 번째 단계였는지(chainDepth)를 그 실행을 만든 FIRED 이벤트에서 읽는다(WD-106).
   *
   * <p>연쇄 발화는 {@code fireTrigger} 가 params 를 FIRED 이벤트 detail 에 그대로 남기므로, 거기에 실어 둔 깊이를 다음 단계가 이어
   * 받는다 — 실행 레코드에 컬럼을 추가하지 않고(마이그레이션 없이) 깊이를 전달하는 방법이다.
   *
   * <p><b>PIPELINE_CHAIN 트리거의 FIRED 이벤트만 믿는다.</b> API·WEBHOOK 트리거는 외부 호출자의 요청 본문이 params 로 그대로
   * detail 에 남으므로, 거기의 chainDepth(예: 음수)를 믿으면 외부에서 깊이 상한을 무력화할 수 있다. 음수도 0 으로 다룬다.
   *
   * <p>{@code pipeline_id} 조건은 인덱스(idx_trigger_event_pipeline)를 타기 위한 것이다 — FIRED 이벤트의 pipeline_id
   * 는 그 실행의 파이프라인과 같다.
   *
   * @return 깊이. 연쇄로 시작된 실행이 아니면(수동·스케줄·API 등) 빈 값
   */
  public Optional<Integer> findChainDepthOfExecution(Long pipelineId, Long executionId) {
    Field<String> depth = field("{0}->>'chainDepth'", String.class, TE_DETAIL);
    return dsl.select(depth)
        .from(TRIGGER_EVENT)
        .join(PIPELINE_TRIGGER)
        .on(TE_TRIGGER_ID.eq(T_ID))
        .where(TE_PIPELINE_ID.eq(pipelineId))
        .and(TE_EXECUTION_ID.eq(executionId))
        .and(TE_EVENT_TYPE.eq("FIRED"))
        .and(T_TRIGGER_TYPE.eq("PIPELINE_CHAIN"))
        .limit(1)
        .fetchOptional()
        // detail 에 chainDepth 가 없으면 값이 null 이다 — Optional.map 이 빈 값으로 바꾼다.
        .map(r -> r.value1())
        .flatMap(
            raw -> {
              try {
                return Optional.of(Math.max(0, Integer.parseInt(raw)));
              } catch (NumberFormatException e) {
                return Optional.empty();
              }
            });
  }

  /** 한 트리거의 가장 최근 이벤트 요약 — 종류와 detail.reason(없으면 null)(WD-169). */
  public record LatestEvent(String eventType, String reason) {}

  /**
   * 한 트리거의 가장 최근 이벤트를 읽는다(WD-169). 트리거를 만든 사람이 정지·권한 회수돼 건너뛸 때 「처음 한 번만」 알리기 위한 판단 근거다 — 직전 이벤트가 같은
   * 사유의 건너뜀이면 이미 알렸다고 본다. 별도 컬럼 없이(마이그레이션 없이) 기존 이벤트 이력으로 판단한다.
   *
   * <p>순서는 created_at 내림차순(인덱스 idx_trigger_event_trigger_time 을 탄다)에 id 로 동률을 가른다 — 같은 시각에 겹친 이벤트도
   * 나중에 넣은 것이 최근이다.
   */
  public Optional<LatestEvent> findLatestByTrigger(Long triggerId) {
    Field<String> reason = field("{0}->>'reason'", String.class, TE_DETAIL);
    return dsl.select(TE_EVENT_TYPE, reason)
        .from(TRIGGER_EVENT)
        .where(TE_TRIGGER_ID.eq(triggerId))
        // MISSED 는 발화 결과가 아니라 「놓친 발화를 지금 대신 돈다」는 표시라 직후의 실제 발화 결과와 짝을 이룬다 — 그것을 직전 이벤트로 보면
        // 놓친 발화 복구 때마다 같은 건너뜀을 다시 알리게 된다
        .and(TE_EVENT_TYPE.ne("MISSED"))
        .orderBy(TE_CREATED_AT.desc(), TE_ID.desc())
        .limit(1)
        .fetchOptional(r -> new LatestEvent(r.value1(), r.value2()));
  }

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
      return null;
    }
    try {
      return JSONB.valueOf(objectMapper.writeValueAsString(map));
    } catch (Exception e) {
      return null;
    }
  }

  public void create(
      Long triggerId,
      Long pipelineId,
      Long executionId,
      String eventType,
      Map<String, Object> detail) {
    var query =
        dsl.insertInto(TRIGGER_EVENT)
            .set(TE_TRIGGER_ID, triggerId)
            .set(TE_PIPELINE_ID, pipelineId)
            .set(TE_EVENT_TYPE, eventType);

    if (executionId != null) {
      query = query.set(TE_EXECUTION_ID, executionId);
    }

    JSONB detailJsonb = toJsonb(detail);
    if (detailJsonb != null) {
      query = query.set(TE_DETAIL, detailJsonb);
    }

    query.execute();
  }

  public List<TriggerEventResponse> findByPipelineId(Long pipelineId, int limit) {
    return dsl.select(
            TE_ID, TE_TRIGGER_ID, T_NAME, TE_EVENT_TYPE, TE_EXECUTION_ID, TE_DETAIL, TE_CREATED_AT)
        .from(TRIGGER_EVENT)
        .leftJoin(PIPELINE_TRIGGER)
        .on(TE_TRIGGER_ID.eq(T_ID))
        .where(TE_PIPELINE_ID.eq(pipelineId))
        .orderBy(TE_CREATED_AT.desc())
        .limit(limit)
        .fetch(
            r ->
                new TriggerEventResponse(
                    r.get(TE_ID),
                    r.get(TE_TRIGGER_ID),
                    r.get(T_NAME),
                    r.get(TE_EVENT_TYPE),
                    r.get(TE_EXECUTION_ID),
                    parseJsonb(r.get(TE_DETAIL)),
                    r.get(TE_CREATED_AT)));
  }

  public List<TriggerEventResponse> findByTriggerId(Long triggerId, int limit) {
    return dsl.select(
            TE_ID, TE_TRIGGER_ID, T_NAME, TE_EVENT_TYPE, TE_EXECUTION_ID, TE_DETAIL, TE_CREATED_AT)
        .from(TRIGGER_EVENT)
        .leftJoin(PIPELINE_TRIGGER)
        .on(TE_TRIGGER_ID.eq(T_ID))
        .where(TE_TRIGGER_ID.eq(triggerId))
        .orderBy(TE_CREATED_AT.desc())
        .limit(limit)
        .fetch(
            r ->
                new TriggerEventResponse(
                    r.get(TE_ID),
                    r.get(TE_TRIGGER_ID),
                    r.get(T_NAME),
                    r.get(TE_EVENT_TYPE),
                    r.get(TE_EXECUTION_ID),
                    parseJsonb(r.get(TE_DETAIL)),
                    r.get(TE_CREATED_AT)));
  }

  public int deleteOlderThan(int days) {
    return dsl.deleteFrom(TRIGGER_EVENT)
        .where(TE_CREATED_AT.lt(LocalDateTime.now().minusDays(days)))
        .execute();
  }
}
