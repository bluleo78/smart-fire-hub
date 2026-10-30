package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.dto.CreatePipelineRequest;
import com.smartfirehub.pipeline.dto.PipelineDeleteImpactResponse;
import com.smartfirehub.pipeline.exception.PipelineExecutionActiveException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository.ActiveExecutionState;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 실행 중 파이프라인 삭제 거부(WD-437).
 *
 * <p>대기(PENDING)·실행 중(RUNNING) 실행이 있으면 삭제를 409 로 거부하고 파이프라인·실행 기록·감사가 그대로인지, 끝난 실행만 있으면 지워지고 삭제 감사가
 * 남는지 본다. 실행 행은 러너 없이 픽스처로 직접 넣는다 — 러너가 돌면 상태가 바뀌어 판정 대상이 흔들린다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 삭제는 서비스 트랜잭션에서 커밋되므로, 커밋된 결과를 별도 트랜잭션({@code
 * inTenantFixture})으로 읽어야 "남았다/지워졌다"의 증거가 된다.
 */
class PipelineDeleteActiveExecutionTest extends IntegrationTestBase {

  @Autowired private PipelineService pipelineService;
  @Autowired private PipelineExecutionService executionService;
  @Autowired private PipelineExecutionRepository executionRepository;
  @Autowired private DSLContext dsl;

  private Long userId;
  private String unique;

  /** 정리 대상 — 거부돼 남은 파이프라인(실행은 FK CASCADE 로 함께 지워진다). */
  private final List<Long> pipelines = new ArrayList<>();

  @BeforeEach
  void setUp() {
    unique = String.valueOf(System.nanoTime());
    String username = "wd437_" + unique;
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, username)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "WD-437 User")
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
    // 픽스처 경로로 직접 지운다 — 서비스 삭제는 남은 진행 중 실행 때문에 거부된다.
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
  private long insertExecution(Long pipelineId, String status, boolean cancelRequested) {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "INSERT INTO pipeline_execution (pipeline_id, status, executed_by, tenant_id,"
                        + " heartbeat_at, started_at, cancel_requested_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                    pipelineId,
                    status,
                    userId,
                    DEFAULT_TEST_TENANT_ID,
                    now,
                    now,
                    cancelRequested ? now : null)
                .get(0, Long.class));
  }

  private boolean pipelineExists(Long id) {
    return inTenantFixture(() -> dsl.fetchExists(PIPELINE, PIPELINE.ID.eq(id)));
  }

  private int executionCount(Long pipelineId) {
    return inTenantFixture(
        () -> dsl.fetchCount(PIPELINE_EXECUTION, PIPELINE_EXECUTION.PIPELINE_ID.eq(pipelineId)));
  }

  private int deleteAuditCount(Long pipelineId) {
    return inTenantFixture(
        () ->
            dsl.fetchCount(
                AUDIT_LOG,
                AUDIT_LOG
                    .ACTION_TYPE
                    .eq("DELETE")
                    .and(AUDIT_LOG.RESOURCE.eq("pipeline"))
                    .and(AUDIT_LOG.RESOURCE_ID.eq(String.valueOf(pipelineId)))));
  }

  // 화면·AI 도구 설명과 같은 문장이어야 하므로 상수가 아니라 문장 그대로 고정한다.
  private static final String ACTIVE_MESSAGE = "실행 중에는 삭제할 수 없습니다. 실행을 취소한 뒤 삭제하세요";
  private static final String CANCELLING_MESSAGE = "실행 취소가 진행 중입니다. 취소가 끝난 뒤 다시 삭제하세요";

  /** 거부 공통 검증 — 409 예외·문구, 파이프라인·실행 기록이 그대로, 삭제 감사 없음. */
  private void assertDeleteRejected(Long pipelineId, int expectedExecutions) {
    assertDeleteRejected(pipelineId, expectedExecutions, ACTIVE_MESSAGE);
  }

  private void assertDeleteRejected(Long pipelineId, int expectedExecutions, String message) {
    assertThatThrownBy(() -> pipelineService.deletePipeline(pipelineId, userId))
        .isInstanceOf(PipelineExecutionActiveException.class)
        .hasMessage(message);
    assertThat(pipelineExists(pipelineId)).isTrue();
    assertThat(executionCount(pipelineId)).isEqualTo(expectedExecutions);
    assertThat(deleteAuditCount(pipelineId)).isZero();
  }

  // ── 삭제 판정 ───────────────────────────────────────────────────────

  @Test
  void deletePipeline_withRunningExecution_rejectedAndKeepsHistory() {
    Long pipelineId = createPipeline("wd437-running");
    insertExecution(pipelineId, "COMPLETED", false);
    insertExecution(pipelineId, "RUNNING", false);

    assertDeleteRejected(pipelineId, 2);
  }

  @Test
  void deletePipeline_withPendingExecution_rejected() {
    Long pipelineId = createPipeline("wd437-pending");
    insertExecution(pipelineId, "PENDING", false);

    assertDeleteRejected(pipelineId, 1);
  }

  /**
   * 취소를 요청했어도 러너가 체크포인트에 닿기 전까지는 출력을 바꿀 수 있으므로 진행 중으로 센다. 진행 중 실행이 모두 취소 요청 상태면 「취소 진행 중」
   * 문구로 안내한다(다시 취소하라고 하지 않는다).
   */
  @Test
  void deletePipeline_withCancelRequestedRunningExecution_rejectedWithCancellingMessage() {
    Long pipelineId = createPipeline("wd437-cancelling");
    insertExecution(pipelineId, "RUNNING", true);

    assertDeleteRejected(pipelineId, 1, CANCELLING_MESSAGE);
  }

  /** 취소 요청 실행과 취소 요청 없는 실행이 섞여 있으면 사용자가 취소할 것이 남았으므로 일반 문구다. */
  @Test
  void deletePipeline_withMixedCancellingAndActive_rejectedWithActiveMessage() {
    Long pipelineId = createPipeline("wd437-mixed");
    insertExecution(pipelineId, "RUNNING", true);
    insertExecution(pipelineId, "PENDING", false);

    assertDeleteRejected(pipelineId, 2, ACTIVE_MESSAGE);
  }

  /** 대기 중 실행은 기존 취소 API 로 그 자리에서 CANCELLED 로 마감되므로, 안내대로 취소하면 바로 지울 수 있다. */
  @Test
  void deletePipeline_afterCancellingPendingExecution_succeeds() {
    Long pipelineId = createPipeline("wd437-cancel-then-delete");
    long executionId = insertExecution(pipelineId, "PENDING", false);
    assertDeleteRejected(pipelineId, 1);

    pipelineService.cancelExecution(pipelineId, executionId, userId);
    pipelineService.deletePipeline(pipelineId, userId);

    assertThat(pipelineExists(pipelineId)).isFalse();
    assertThat(deleteAuditCount(pipelineId)).isEqualTo(1);
  }

  // ── 삭제 영향(확인 창) ──────────────────────────────────────────────

  @Test
  void getDeleteImpact_reportsActiveExecutionState() {
    Long pipelineId = createPipeline("wd437-impact");
    assertThat(pipelineService.getDeleteImpact(pipelineId, userId))
        .isEqualTo(new PipelineDeleteImpactResponse(0, false, ActiveExecutionState.NONE));

    long running = insertExecution(pipelineId, "RUNNING", false);
    assertThat(pipelineService.getDeleteImpact(pipelineId, userId))
        .isEqualTo(new PipelineDeleteImpactResponse(0, false, ActiveExecutionState.ACTIVE));

    inTenantFixture(
        () ->
            dsl.update(PIPELINE_EXECUTION)
                .set(PIPELINE_EXECUTION.CANCEL_REQUESTED_AT, LocalDateTime.now(ZoneOffset.UTC))
                .where(PIPELINE_EXECUTION.ID.eq(running))
                .execute());
    assertThat(pipelineService.getDeleteImpact(pipelineId, userId))
        .isEqualTo(new PipelineDeleteImpactResponse(0, false, ActiveExecutionState.CANCELLING));
  }

  @Test
  void getDeleteImpact_missingPipeline_throwsNotFound() {
    assertThatThrownBy(() -> pipelineService.getDeleteImpact(-1L, userId))
        .isInstanceOf(PipelineNotFoundException.class);
  }

  @Test
  void deletePipeline_missingPipeline_throwsNotFound() {
    assertThatThrownBy(() -> pipelineService.deletePipeline(-1L, userId))
        .isInstanceOf(PipelineNotFoundException.class);
  }

  @Test
  void deletePipeline_withOnlyFinishedExecutions_deletesAndWritesAudit() {
    Long pipelineId = createPipeline("wd437-finished");
    insertExecution(pipelineId, "COMPLETED", false);
    insertExecution(pipelineId, "FAILED", false);
    insertExecution(pipelineId, "CANCELLED", true);

    pipelineService.deletePipeline(pipelineId, userId);

    assertThat(pipelineExists(pipelineId)).isFalse();
    assertThat(executionCount(pipelineId)).isZero();
    assertThat(deleteAuditCount(pipelineId)).isEqualTo(1);
  }

  /** 삭제된 파이프라인으로 실행을 만들려 하면(삭제와 경쟁해 진 쪽) FK 위반이 아니라 404 로 거부된다. */
  @Test
  void executePipeline_afterDelete_throwsNotFound() {
    Long pipelineId = createPipeline("wd437-exec-after-delete");
    pipelineService.deletePipeline(pipelineId, userId);

    assertThatThrownBy(() -> executionService.executePipeline(pipelineId, userId))
        .isInstanceOf(PipelineNotFoundException.class);
  }

  // ── 러너 정지 판정 ──────────────────────────────────────────────────

  /** 실행 행이 사라졌으면(파이프라인 삭제 CASCADE 등) 러너는 다음 체크포인트에서 멈춘다. 정상 진행 행만 계속 돈다. */
  @Test
  void isStopRequested_missingRowStops_activeRowContinues() {
    Long pipelineId = createPipeline("wd437-stop");
    long pending = insertExecution(pipelineId, "PENDING", false);
    long running = insertExecution(pipelineId, "RUNNING", false);
    long cancelling = insertExecution(pipelineId, "RUNNING", true);
    long completed = insertExecution(pipelineId, "COMPLETED", false);

    assertThat(inTenantFixture(() -> executionRepository.isStopRequested(pending))).isFalse();
    assertThat(inTenantFixture(() -> executionRepository.isStopRequested(running))).isFalse();
    assertThat(inTenantFixture(() -> executionRepository.isStopRequested(cancelling))).isTrue();
    assertThat(inTenantFixture(() -> executionRepository.isStopRequested(completed))).isTrue();

    // 실행 행을 지우면(삭제 CASCADE 재현) 멈춘다.
    inTenantFixture(
        () ->
            dsl.deleteFrom(PIPELINE_EXECUTION).where(PIPELINE_EXECUTION.ID.eq(running)).execute());
    assertThat(inTenantFixture(() -> executionRepository.isStopRequested(running))).isTrue();
  }
}
