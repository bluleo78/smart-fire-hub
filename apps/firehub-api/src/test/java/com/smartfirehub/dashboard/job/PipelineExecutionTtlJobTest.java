package com.smartfirehub.dashboard.job;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * pipeline_execution TTL 정리 Job 통합 테스트.
 *
 * <p>- 90일 초과 + COMPLETED 행만 삭제, FAILED 는 보존. - 윈도우 내(89일 등) COMPLETED 행은 보존. - trigger_event 자식 행이
 * FK CASCADE 로 함께 삭제되는지 검증 (V59 의존).
 */
class PipelineExecutionTtlJobTest extends IntegrationTestBase {

  @Autowired private PipelineExecutionTtlJob job;
  @Autowired private DSLContext dsl;

  private Long pipelineId;
  private Long userId;

  @BeforeEach
  void seedPipeline() {
    // 테스트용 유저 생성 (pipeline_trigger.created_by FK 충족)
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "ttl-test-user-" + System.nanoTime())
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "TTL Test User")
            .set(USER.EMAIL, "ttl-test-" + System.nanoTime() + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    pipelineId =
        dsl.insertInto(PIPELINE)
            .set(PIPELINE.NAME, "ttl-test-" + System.nanoTime())
            .set(PIPELINE.IS_ACTIVE, true)
            .set(PIPELINE.CREATED_BY, userId)
            .set(PIPELINE.CREATED_AT, LocalDateTime.now())
            .returning(PIPELINE.ID)
            .fetchOne()
            .getId();
  }

  /** 90일 보존 정책 — created_at < (now - days) AND status = COMPLETED 행만 삭제. */
  @Test
  void runOnce_deletesCompletedRowsOlderThanRetention() {
    Long oldCompleted = insertExecution(LocalDateTime.now().minusDays(100), "COMPLETED");
    Long oldFailed = insertExecution(LocalDateTime.now().minusDays(100), "FAILED");
    Long recentCompleted = insertExecution(LocalDateTime.now().minusDays(89), "COMPLETED");

    ReflectionTestUtils.setField(job, "retentionDays", 90);
    int deleted = job.runOnce();

    assertThat(deleted).isEqualTo(1);
    assertThat(executionExists(oldCompleted)).isFalse();
    assertThat(executionExists(oldFailed)).isTrue();
    assertThat(executionExists(recentCompleted)).isTrue();
  }

  /** retentionDays override 검증 — 30일로 설정 시 89일 행도 삭제. */
  @Test
  void runOnce_respectsRetentionDaysOverride() {
    Long execId = insertExecution(LocalDateTime.now().minusDays(89), "COMPLETED");
    ReflectionTestUtils.setField(job, "retentionDays", 30);

    job.runOnce();

    assertThat(executionExists(execId)).isFalse();
  }

  /** CASCADE — trigger_event 자식 행이 부모 삭제 시 함께 제거. V59 마이그레이션 의존. */
  @Test
  void runOnce_cascadesTriggerEventChild() {
    Long execId = insertExecution(LocalDateTime.now().minusDays(100), "COMPLETED");

    // pipeline_trigger.created_by NOT NULL → 유저 시드 필요
    Long triggerId =
        dsl.insertInto(PIPELINE_TRIGGER)
            .set(PIPELINE_TRIGGER.PIPELINE_ID, pipelineId)
            .set(PIPELINE_TRIGGER.TRIGGER_TYPE, "API")
            .set(PIPELINE_TRIGGER.NAME, "test-trigger-" + System.nanoTime())
            .set(PIPELINE_TRIGGER.IS_ENABLED, true)
            .set(PIPELINE_TRIGGER.CREATED_BY, userId)
            .set(PIPELINE_TRIGGER.CREATED_AT, LocalDateTime.now())
            .returning(PIPELINE_TRIGGER.ID)
            .fetchOne()
            .getId();

    Long eventId =
        dsl.insertInto(TRIGGER_EVENT)
            .set(TRIGGER_EVENT.PIPELINE_ID, pipelineId)
            .set(TRIGGER_EVENT.TRIGGER_ID, triggerId)
            .set(TRIGGER_EVENT.EXECUTION_ID, execId)
            .set(TRIGGER_EVENT.EVENT_TYPE, "TEST")
            .set(TRIGGER_EVENT.CREATED_AT, LocalDateTime.now())
            .returning(TRIGGER_EVENT.ID)
            .fetchOne()
            .getId();

    ReflectionTestUtils.setField(job, "retentionDays", 90);
    job.runOnce();

    assertThat(executionExists(execId)).isFalse();
    assertThat(dsl.fetchExists(dsl.selectFrom(TRIGGER_EVENT).where(TRIGGER_EVENT.ID.eq(eventId))))
        .isFalse();
  }

  private Long insertExecution(LocalDateTime createdAt, String status) {
    return dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, pipelineId)
        .set(PIPELINE_EXECUTION.STATUS, status)
        .set(PIPELINE_EXECUTION.EXECUTED_BY, userId)
        .set(PIPELINE_EXECUTION.CREATED_AT, createdAt)
        .returning(PIPELINE_EXECUTION.ID)
        .fetchOne()
        .getId();
  }

  private boolean executionExists(Long id) {
    return dsl.fetchExists(dsl.selectFrom(PIPELINE_EXECUTION).where(PIPELINE_EXECUTION.ID.eq(id)));
  }
}
