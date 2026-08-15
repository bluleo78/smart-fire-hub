package com.smartfirehub.pipeline.repository;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.pipeline.dto.TriggerEventResponse;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
