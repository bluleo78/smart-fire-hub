package com.smartfirehub.dashboard.job;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * pipeline_execution TTL 정리 Job 통합 테스트.
 *
 * <p>- 90일 초과 + COMPLETED 행만 삭제, FAILED 는 보존. - 윈도우 내(89일 등) COMPLETED 행은 보존. - trigger_event 자식 행이
 * FK CASCADE 로 함께 삭제되는지 검증 (V59 의존).
 *
 * <p><b>이 클래스에는 클래스 레벨 {@code @Transactional} 이 없다 — 의도된 것이다.</b> V93 이후
 * 픽스처 INSERT 는 tenant_id DEFAULT 를 채울 트랜잭션 GUC 가 필요하지만, 그것을 클래스 레벨
 * {@code @Transactional} 로 해결하면 테스트가 열어둔 트랜잭션에 {@code runOnce()} 가 얹혀 타게 된다.
 * {@code PipelineExecutionTtlJob} 은 P2-b Task 8 에서 테넌트 순회 + 자체 트랜잭션을 받을 대상이라,
 * 그렇게 하면 배선을 빠뜨려도 이 테스트가 통과해 결함을 구조적으로 못 보게 된다. 그래서 픽스처만
 * 트랜잭션 안에서 만들고, {@code runOnce()} 호출은 트랜잭션 밖에 남긴다.
 *
 * <p>롤백이 없으므로 심은 행은 {@link #cleanup()} 에서 직접 지운다.
 */
class PipelineExecutionTtlJobTest extends IntegrationTestBase {

  @Autowired private PipelineExecutionTtlJob job;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;
  private Long pipelineId;
  private Long userId;

  @BeforeEach
  void seedPipeline() {
    tx = new TransactionTemplate(transactionManager);
    inTenantTx(
        () -> {
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
        });
  }

  @AfterEach
  void cleanup() {
    // 파이프라인을 지우면 실행·트리거·이벤트가 FK CASCADE 로 함께 사라진다.
    inTenantTx(
        () -> {
          dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.eq(pipelineId)).execute();
          dsl.deleteFrom(USER).where(USER.ID.eq(userId)).execute();
        });
  }

  /** 90일 보존 정책 — created_at < (now - days) AND status = COMPLETED 행만 삭제. */
  @Test
  void runOnce_deletesCompletedRowsOlderThanRetention() {
    Long oldCompleted =
        inTenantTx(() -> insertExecution(LocalDateTime.now().minusDays(100), "COMPLETED"));
    Long oldFailed = inTenantTx(() -> insertExecution(LocalDateTime.now().minusDays(100), "FAILED"));
    Long recentCompleted =
        inTenantTx(() -> insertExecution(LocalDateTime.now().minusDays(89), "COMPLETED"));

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
    Long execId = inTenantTx(() -> insertExecution(LocalDateTime.now().minusDays(89), "COMPLETED"));
    ReflectionTestUtils.setField(job, "retentionDays", 30);

    job.runOnce();

    assertThat(executionExists(execId)).isFalse();
  }

  /** CASCADE — trigger_event 자식 행이 부모 삭제 시 함께 제거. V59 마이그레이션 의존. */
  @Test
  void runOnce_cascadesTriggerEventChild() {
    Long execId = inTenantTx(() -> insertExecution(LocalDateTime.now().minusDays(100), "COMPLETED"));

    Long eventId =
        inTenantTx(
            () -> {
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

              return dsl.insertInto(TRIGGER_EVENT)
                  .set(TRIGGER_EVENT.PIPELINE_ID, pipelineId)
                  .set(TRIGGER_EVENT.TRIGGER_ID, triggerId)
                  .set(TRIGGER_EVENT.EXECUTION_ID, execId)
                  .set(TRIGGER_EVENT.EVENT_TYPE, "TEST")
                  .set(TRIGGER_EVENT.CREATED_AT, LocalDateTime.now())
                  .returning(TRIGGER_EVENT.ID)
                  .fetchOne()
                  .getId();
            });

    ReflectionTestUtils.setField(job, "retentionDays", 90);
    job.runOnce();

    assertThat(executionExists(execId)).isFalse();
    assertThat(
            inTenantTx(
                () ->
                    dsl.fetchExists(
                        dsl.selectFrom(TRIGGER_EVENT).where(TRIGGER_EVENT.ID.eq(eventId)))))
        .isFalse();
  }

  // ── 픽스처 헬퍼 ────────────────────────────────────────────────────────
  //
  // 픽스처와 검증 조회만 테넌트 트랜잭션 안에서 돈다. 검증 대상인 runOnce() 는 밖에 남겨야
  // "잡이 스스로 테넌트와 트랜잭션을 확보하는가" 를 이 테스트가 계속 검증할 수 있다.

  // runInTenantTransaction 은 진입 전 컨텍스트를 복원하므로, 픽스처 뒤에도 IntegrationTestBase 가
  // 세운 기본 테넌트가 그대로 남는다 — 즉 runOnce() 는 "컨텍스트는 있고 앰비언트 트랜잭션은 없다"
  // 는 운영 조건 그대로 호출된다(트랜잭션까지 열어 주면 잡의 배선 검증력이 사라진다).

  private void inTenantTx(Runnable action) {
    TenantRlsTestSupport.runInTenantTransaction(tx, DEFAULT_TEST_TENANT_ID, action);
  }

  private <T> T inTenantTx(Supplier<T> action) {
    return TenantRlsTestSupport.runInTenantTransaction(tx, DEFAULT_TEST_TENANT_ID, action);
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
    return inTenantTx(
        () ->
            dsl.fetchExists(dsl.selectFrom(PIPELINE_EXECUTION).where(PIPELINE_EXECUTION.ID.eq(id))));
  }
}
