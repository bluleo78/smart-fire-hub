package com.smartfirehub.proactive.repository;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProactiveJobExecutionRepository {

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  private static final Table<?> PROACTIVE_JOB_EXECUTION = table(name("proactive_job_execution"));
  private static final Field<Long> PJE_ID =
      field(name("proactive_job_execution", "id"), Long.class);
  private static final Field<Long> PJE_JOB_ID =
      field(name("proactive_job_execution", "job_id"), Long.class);
  private static final Field<String> PJE_STATUS =
      field(name("proactive_job_execution", "status"), String.class);
  private static final Field<LocalDateTime> PJE_STARTED_AT =
      field(name("proactive_job_execution", "started_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> PJE_COMPLETED_AT =
      field(name("proactive_job_execution", "completed_at"), LocalDateTime.class);
  private static final Field<String> PJE_ERROR_MESSAGE =
      field(name("proactive_job_execution", "error_message"), String.class);
  private static final Field<JSONB> PJE_RESULT =
      field(name("proactive_job_execution", "result"), JSONB.class);
  private static final Field<LocalDateTime> PJE_CREATED_AT =
      field(name("proactive_job_execution", "created_at"), LocalDateTime.class);

  /** 실제 전달된 채널 목록 (쉼표 구분 문자열, 예: "CHAT,EMAIL") */
  private static final Field<String> PJE_DELIVERED_CHANNELS =
      field(name("proactive_job_execution", "delivered_channels"), String.class);

  public Long create(Long jobId) {
    return dsl.insertInto(PROACTIVE_JOB_EXECUTION)
        .set(PJE_JOB_ID, jobId)
        .set(PJE_STATUS, "PENDING")
        .returning(PJE_ID)
        .fetchOne(r -> r.get(PJE_ID));
  }

  public void updateStatus(
      Long id, String status, LocalDateTime startedAt, LocalDateTime completedAt) {
    var query = dsl.update(PROACTIVE_JOB_EXECUTION).set(PJE_STATUS, status);
    if (startedAt != null) query = query.set(PJE_STARTED_AT, startedAt);
    if (completedAt != null) query = query.set(PJE_COMPLETED_AT, completedAt);
    query.where(PJE_ID.eq(id)).execute();
  }

  public void updateResult(
      Long id, String status, Map<String, Object> result, LocalDateTime completedAt) {
    try {
      String resultJson = result != null ? objectMapper.writeValueAsString(result) : null;
      var query =
          dsl.update(PROACTIVE_JOB_EXECUTION)
              .set(PJE_STATUS, status)
              .set(PJE_COMPLETED_AT, completedAt);
      if (resultJson != null) query = query.set(PJE_RESULT, JSONB.valueOf(resultJson));
      query.where(PJE_ID.eq(id)).execute();
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize result", e);
    }
  }

  public void updateError(Long id, String errorMessage) {
    dsl.update(PROACTIVE_JOB_EXECUTION)
        .set(PJE_STATUS, "FAILED")
        .set(PJE_ERROR_MESSAGE, errorMessage)
        .set(PJE_COMPLETED_AT, LocalDateTime.now())
        .where(PJE_ID.eq(id))
        .execute();
  }

  public List<ProactiveJobExecutionResponse> findByJobId(Long jobId, int limit, int offset) {
    return dsl.select(
            PJE_ID,
            PJE_JOB_ID,
            PJE_STATUS,
            PJE_STARTED_AT,
            PJE_COMPLETED_AT,
            PJE_ERROR_MESSAGE,
            PJE_RESULT,
            PJE_DELIVERED_CHANNELS,
            PJE_CREATED_AT)
        .from(PROACTIVE_JOB_EXECUTION)
        .where(PJE_JOB_ID.eq(jobId))
        .orderBy(PJE_ID.desc())
        .limit(limit)
        .offset(offset)
        .fetch(r -> toResponse(r));
  }

  public Optional<ProactiveJobExecutionResponse> findById(Long id) {
    return dsl.select(
            PJE_ID,
            PJE_JOB_ID,
            PJE_STATUS,
            PJE_STARTED_AT,
            PJE_COMPLETED_AT,
            PJE_ERROR_MESSAGE,
            PJE_RESULT,
            PJE_DELIVERED_CHANNELS,
            PJE_CREATED_AT)
        .from(PROACTIVE_JOB_EXECUTION)
        .where(PJE_ID.eq(id))
        .fetchOptional(r -> toResponse(r));
  }

  /** 실행 완료 후 실제 전달된 채널 목록을 DB에 저장한다. 쉼표 구분 문자열로 저장 (예: "CHAT,EMAIL"). */
  public void updateDeliveredChannels(Long executionId, List<String> channels) {
    String value = (channels != null && !channels.isEmpty()) ? String.join(",", channels) : null;
    dsl.update(PROACTIVE_JOB_EXECUTION)
        .set(PJE_DELIVERED_CHANNELS, value)
        .where(PJE_ID.eq(executionId))
        .execute();
  }

  private ProactiveJobExecutionResponse toResponse(org.jooq.Record r) {
    try {
      JSONB resultJsonb = r.get(PJE_RESULT);
      Map<String, Object> result =
          resultJsonb != null
              ? objectMapper.readValue(resultJsonb.data(), new TypeReference<>() {})
              : null;
      // deliveredChannels: 쉼표로 구분된 문자열을 리스트로 변환
      String channelsStr = r.get(PJE_DELIVERED_CHANNELS);
      List<String> channels =
          (channelsStr != null && !channelsStr.isBlank()) ? List.of(channelsStr.split(",")) : null;
      return new ProactiveJobExecutionResponse(
          r.get(PJE_ID),
          r.get(PJE_JOB_ID),
          r.get(PJE_STATUS),
          r.get(PJE_STARTED_AT),
          r.get(PJE_COMPLETED_AT),
          r.get(PJE_ERROR_MESSAGE),
          result,
          channels,
          r.get(PJE_CREATED_AT));
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize result", e);
    }
  }
}
