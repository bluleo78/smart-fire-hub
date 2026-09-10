package com.smartfirehub.proactive.repository;

import com.smartfirehub.proactive.util.ProactiveTime;
import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionSummaryResponse;
import com.smartfirehub.proactive.dto.ReportListItemResponse;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로액티브 잡 실행 이력 저장소.
 *
 * <p>클래스 레벨 {@code @Transactional} 이 필요한 이유: V103 으로 {@code tenant_id} 가 생겼고
 * V104 에서 RLS 가 걸린다. 테넌트 값은 트랜잭션-로컬 GUC 라 트랜잭션이 없으면 공급되지 않는다.
 * 이 저장소의 주 호출자인 {@code ProactiveJobAsyncRunner.executeJob} 은 {@code @Async} 만 있고
 * 트랜잭션이 없어, 배선이 없으면 실행 이력이 조용히 남지 않는다. 전파 REQUIRED 이므로
 * 컨트롤러 경로의 동작은 불변이다.
 */
@Transactional
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

  // 리포트 목록 조회용 — 잡 소유자 검증과 잡 이름 표기를 위해 proactive_job 을 조인한다
  private static final Table<?> PROACTIVE_JOB = table(name("proactive_job"));
  private static final Field<Long> PJ_ID = field(name("proactive_job", "id"), Long.class);
  private static final Field<Long> PJ_USER_ID =
      field(name("proactive_job", "user_id"), Long.class);
  private static final Field<String> PJ_NAME = field(name("proactive_job", "name"), String.class);

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
        .set(PJE_COMPLETED_AT, ProactiveTime.nowUtc())
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

  /**
   * 실행 이력 목록(경량 뷰) 조회 — 컨트롤러 목록 endpoint 전용 (#604).
   *
   * <p>{@link #findByJobId}와 달리 {@code result}(리포트 본문) 컬럼 자체를 SELECT하지 않는다. 목록은 실행
   * 건수만큼 반복되므로 본문을 포함하면 응답이 수만 자에 달해 MCP 도구 결과 토큰 한도를 초과할 수 있다. 본문이
   * 필요하면 단건 조회({@link #findById})를 사용해야 한다.
   */
  public List<ProactiveJobExecutionSummaryResponse> findSummariesByJobId(
      Long jobId, int limit, int offset) {
    return dsl.select(
            PJE_ID,
            PJE_JOB_ID,
            PJE_STATUS,
            PJE_STARTED_AT,
            PJE_COMPLETED_AT,
            PJE_ERROR_MESSAGE,
            PJE_DELIVERED_CHANNELS,
            PJE_CREATED_AT)
        .from(PROACTIVE_JOB_EXECUTION)
        .where(PJE_JOB_ID.eq(jobId))
        .orderBy(PJE_ID.desc())
        .limit(limit)
        .offset(offset)
        .fetch(r -> toSummaryResponse(r));
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

  /**
   * 사용자가 소유한 스마트 작업들이 생성한 리포트를 잡 횡단으로 조회한다.
   *
   * <p>"리포트"는 COMPLETED 이면서 result.htmlContent 가 실제로 있는 실행만을 뜻한다. htmlContent 가 없으면 뷰어가 404를
   * 내므로 목록에 넣으면 빈 화면으로 이어진다.
   *
   * <p>본문(htmlContent)은 수십 KB이므로 SELECT 하지 않고 WHERE 절의 존재 판정에만 사용한다. 잡 스코핑이 없는
   * 엔드포인트이므로 user_id 조건이 유일한 소유권 방어선이다.
   */
  public List<ReportListItemResponse> findReportsByUserId(Long userId, int limit, int offset) {
    // result ->> 'htmlContent' / 'title' / 'summary' — JSONB 텍스트 추출
    Field<String> htmlContent = field("{0} ->> 'htmlContent'", String.class, PJE_RESULT);
    Field<String> title = field("{0} ->> 'title'", String.class, PJE_RESULT);
    Field<String> summary = field("{0} ->> 'summary'", String.class, PJE_RESULT);

    // ProactiveResult.effectiveTitle(jobName) 과 동일한 규칙을 SQL 로 재현
    Field<String> effectiveTitle = coalesce(nullif(trim(title), val("")), PJ_NAME);

    return dsl.select(PJE_ID, PJE_JOB_ID, PJ_NAME, effectiveTitle, summary, PJE_COMPLETED_AT)
        .from(PROACTIVE_JOB_EXECUTION)
        .join(PROACTIVE_JOB)
        .on(PJE_JOB_ID.eq(PJ_ID))
        .where(PJ_USER_ID.eq(userId))
        .and(PJE_STATUS.eq("COMPLETED"))
        .and(htmlContent.isNotNull())
        .and(htmlContent.ne(""))
        .orderBy(PJE_COMPLETED_AT.desc(), PJE_ID.desc())
        .limit(limit)
        .offset(offset)
        .fetch(
            r ->
                new ReportListItemResponse(
                    r.get(PJE_ID),
                    r.get(PJE_JOB_ID),
                    r.get(PJ_NAME),
                    r.get(effectiveTitle),
                    r.get(summary),
                    r.get(PJE_COMPLETED_AT)));
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

  private ProactiveJobExecutionSummaryResponse toSummaryResponse(org.jooq.Record r) {
    String channelsStr = r.get(PJE_DELIVERED_CHANNELS);
    List<String> channels =
        (channelsStr != null && !channelsStr.isBlank()) ? List.of(channelsStr.split(",")) : null;
    return new ProactiveJobExecutionSummaryResponse(
        r.get(PJE_ID),
        r.get(PJE_JOB_ID),
        r.get(PJE_STATUS),
        r.get(PJE_STARTED_AT),
        r.get(PJE_COMPLETED_AT),
        r.get(PJE_ERROR_MESSAGE),
        channels,
        r.get(PJE_CREATED_AT));
  }
}
