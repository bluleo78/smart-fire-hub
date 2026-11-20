package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.exception.PipelineAlreadyRunningException;
import com.smartfirehub.support.ConcurrentCalls;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 수동 실행 중첩 409 가드(WD-825).
 *
 * <p>대기(PENDING)·실행 중(RUNNING) 실행이 있는 파이프라인을 수동 실행하면 409 로 거부하고 실행 행·감사가 늘지 않는지, 트리거 실행은 막지 않는지,
 * 동시에 두 번 눌러도 하나만 접수되는지 본다. 실행 행은 러너 없이 픽스처로 직접 넣는다 — 러너가 돌면 상태가 바뀌어 판정 대상이 흔들린다.
 *
 * <p>WD-827: 러너를 아무것도 하지 않는 대역으로 바꿔 접수된 실행이 PENDING 에 머문다 — 스텝 0개 실행이 몇 ms 만에 COMPLETED 가 되어 늦게 잠금을 잡은
 * 두 번째 호출이 접수되던 흔들림을 없앤다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 서비스 트랜잭션이 커밋돼야 경쟁(행 잠금)이 실제로 재현된다.
 */
class PipelineManualRunGuardTest extends IntegrationTestBase {

  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;
  @Autowired private DSLContext dsl;

  /** WD-827: 비동기 러너를 no-op 으로 대체해 접수된 실행이 PENDING 에 고정되게 한다. */
  @MockitoBean private PipelineAsyncRunner asyncRunner;

  private Long userId;
  private String unique;

  /** 정리 대상 파이프라인(실행은 FK CASCADE 로 함께 지워진다). */
  private final List<Long> pipelines = new ArrayList<>();

  @BeforeEach
  void setUp() {
    unique = String.valueOf(System.nanoTime());
    String username = "wd825_" + unique;
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, username)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "WD-825 User")
            .set(USER.EMAIL, username + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    TenantRlsTestSupport.insertActiveMembership(dsl, userId, DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> TenantRlsTestSupport.grantAdminRole(dsl, userId));
  }

  @AfterEach
  void cleanup() {
    inTenantFixture(() -> dsl.deleteFrom(PIPELINE).where(PIPELINE.ID.in(pipelines)).execute());
    inTenantFixture(() -> dsl.deleteFrom(AUDIT_LOG).where(AUDIT_LOG.USER_ID.eq(userId)).execute());
    TenantRlsTestSupport.deleteMembership(dsl, userId);
    dsl.deleteFrom(USER).where(USER.ID.eq(userId)).execute();
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────

  /** 스텝 없는 파이프라인 — 실행 판정만 보므로 스텝·임시 데이터셋이 필요 없다. */
  private Long createPipeline(String prefix) {
    Long id =
        pipelineService
            .createPipeline(
                new CreatePipelineRequest(prefix + "-" + unique, null, List.of()), userId)
            .id();
    pipelines.add(id);
    return id;
  }

  /** 러너 없이 실행 행을 직접 넣는다. */
  private void insertExecution(
      Long pipelineId, String status, String triggeredBy, boolean cancelRequested) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    inTenantFixture(
        () ->
            dsl.execute(
                "INSERT INTO pipeline_execution (pipeline_id, status, executed_by, tenant_id,"
                    + " heartbeat_at, started_at, cancel_requested_at, triggered_by)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                pipelineId,
                status,
                userId,
                DEFAULT_TEST_TENANT_ID,
                now,
                now,
                cancelRequested ? now : null,
                triggeredBy));
  }

  private int executionCount(Long pipelineId) {
    return inTenantFixture(
        () -> dsl.fetchCount(PIPELINE_EXECUTION, PIPELINE_EXECUTION.PIPELINE_ID.eq(pipelineId)));
  }

  private int executeAuditCount(Long pipelineId) {
    return inTenantFixture(
        () ->
            dsl.fetchCount(
                AUDIT_LOG,
                AUDIT_LOG
                    .ACTION_TYPE
                    .eq("EXECUTE")
                    .and(AUDIT_LOG.RESOURCE.eq("pipeline"))
                    .and(AUDIT_LOG.RESOURCE_ID.eq(String.valueOf(pipelineId)))));
  }

  /**
   * 수동 실행 호출이 409 로 거부되는지 확인한다. 거부 4개 시나리오가 같은 단언을 반복하므로 한곳에 모았다.
   *
   * @param call 수동 실행 호출
   * @param expectedMessage 기대 메시지(null 이면 예외 종류만 본다)
   */
  private void assertManualRejected(ThrowingCallable call, String expectedMessage) {
    var assertion = assertThatThrownBy(call).isInstanceOf(PipelineAlreadyRunningException.class);
    if (expectedMessage != null) {
      assertion.hasMessage(expectedMessage);
    }
  }

  // ── 거부 ────────────────────────────────────────────────────────────

  @Test
  void manualExecution_rejectedWhilePending() {
    Long id = createPipeline("wd825-pending");
    insertExecution(id, "PENDING", "MANUAL", false);

    assertManualRejected(
        () -> pipelineService.executePipeline(id, userId), PipelineAlreadyRunningException.MESSAGE);
  }

  @Test
  void manualExecution_rejectedWhileRunning() {
    Long id = createPipeline("wd825-running");
    insertExecution(id, "RUNNING", "MANUAL", false);

    assertManualRejected(
        () -> pipelineService.executePipeline(id, userId), PipelineAlreadyRunningException.MESSAGE);
  }

  /** triggeredBy 가 null 이면 createExecution 이 "MANUAL" 로 저장하므로 가드도 수동으로 보고 막는다. */
  @Test
  void nullTriggeredBy_treatedAsManual_rejectedWhileRunning() {
    Long id = createPipeline("wd825-null");
    insertExecution(id, "RUNNING", "MANUAL", false);

    assertManualRejected(
        () -> executionService.executePipeline(id, userId, null, null),
        PipelineAlreadyRunningException.MESSAGE);
  }

  /** 진행 중 실행이 취소를 요청한 상태면 「취소 진행 중」 문구로 안내한다. */
  @Test
  void manualExecution_cancellingMessage() {
    Long id = createPipeline("wd825-cancelling");
    insertExecution(id, "RUNNING", "MANUAL", true);

    assertManualRejected(
        () -> pipelineService.executePipeline(id, userId),
        PipelineAlreadyRunningException.CANCELLING_MESSAGE);
  }

  /** 트리거가 시작한 실행이 돌고 있어도 수동 실행은 같은 출력을 겹쳐 바꾸므로 막는다. */
  @Test
  void manualExecution_blockedByActiveTriggerExecution() {
    Long id = createPipeline("wd825-trigger-active");
    insertExecution(id, "RUNNING", "SCHEDULE", false);

    assertManualRejected(() -> pipelineService.executePipeline(id, userId), null);
  }

  // ── 허용 ────────────────────────────────────────────────────────────

  @Test
  void manualExecution_allowedWhenOnlyFinishedExecutions() {
    Long id = createPipeline("wd825-finished");
    insertExecution(id, "COMPLETED", "MANUAL", false);
    insertExecution(id, "FAILED", "MANUAL", false);
    insertExecution(id, "CANCELLED", "MANUAL", true);

    var response = pipelineService.executePipeline(id, userId);

    assertThat(response.status()).isEqualTo("PENDING");
    assertThat(executionCount(id)).isEqualTo(4);
  }

  /** 트리거 실행은 기존 동시 실행 정책을 따르므로 가드 대상이 아니다. */
  @Test
  void triggerExecution_notBlockedByActiveExecution() {
    Long id = createPipeline("wd825-trigger-ok");
    insertExecution(id, "RUNNING", "MANUAL", false);

    assertThatCode(() -> pipelineService.executePipeline(id, userId, "SCHEDULE", null))
        .doesNotThrowAnyException();
  }

  // ── 거부의 부작용 없음 ──────────────────────────────────────────────

  @Test
  void rejectedManualExecution_leavesNoExecutionRow() {
    Long id = createPipeline("wd825-no-row");
    insertExecution(id, "PENDING", "MANUAL", false);

    assertThatThrownBy(() -> pipelineService.executePipeline(id, userId))
        .isInstanceOf(PipelineAlreadyRunningException.class);

    assertThat(executionCount(id)).isEqualTo(1);
    assertThat(executeAuditCount(id)).isZero();
  }

  // ── 경쟁 ────────────────────────────────────────────────────────────

  /** 실행 없는 파이프라인을 동시에 두 번 수동 실행하면 하나만 접수되고 다른 하나는 409 다. */
  @Test
  void concurrentManualExecutions_onlyOneAccepted() throws Exception {
    Long id = createPipeline("wd825-race");

    List<Throwable> results =
        ConcurrentCalls.run(
            2, DEFAULT_TEST_TENANT_ID, () -> pipelineService.executePipeline(id, userId));

    assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
    assertThat(results.stream().filter(Objects::nonNull).toList())
        .hasSize(1)
        .allMatch(PipelineAlreadyRunningException.class::isInstance);
    assertThat(executionCount(id)).isEqualTo(1);
    // WD-827: 러너가 막혀 있으므로 접수된 실행은 여전히 PENDING 이어야 한다(활성 상태 유지 확인)
    assertThat(
            inTenantFixture(
                () ->
                    dsl.select(PIPELINE_EXECUTION.STATUS)
                        .from(PIPELINE_EXECUTION)
                        .where(PIPELINE_EXECUTION.PIPELINE_ID.eq(id))
                        .fetchOne(PIPELINE_EXECUTION.STATUS)))
        .isEqualTo("PENDING");
  }
}
