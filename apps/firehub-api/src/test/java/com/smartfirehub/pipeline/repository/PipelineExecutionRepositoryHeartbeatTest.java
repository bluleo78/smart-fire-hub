package com.smartfirehub.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파이프라인 실행 하트비트·중단 정리용 저장소 쓰기 통합 테스트(WD-230, V148).
 *
 * <p>api 재시작으로 고아가 된 RUNNING 실행을 찾아 닫는 조회·조건부 쓰기가 실제 DB(RLS 포함)에서 의도대로 동작하는지 고정한다. {@code
 * pipeline_execution} 은 RLS 대상이라 검증 대상 호출이 테스트 트랜잭션의 GUC 를 빌려 쓰지 않도록 테스트 트랜잭션은 끄고, 픽스처만 {@code
 * inTenantFixture} 안에서 만든다. 다른 테스트가 남긴 기본 테넌트의 실행에 흔들리지 않도록 스크래치 테넌트 둘을 쓴다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PipelineExecutionRepositoryHeartbeatTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PipelineExecutionRepository repository;

  /** DDL(임시 트리거)은 앱 롤에 권한이 없어 스키마 소유자 연결로 건다. */
  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  private Long userId;
  private Long tenantA;
  private Long tenantB;
  private Long pipelineA;
  private Long pipelineB;

  @BeforeEach
  void setUp() {
    userId = TenantRlsTestSupport.insertUser(dsl, "wd230_hb_user_");
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "wd230-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "wd230-b");
    pipelineA = insertPipeline(tenantA);
    pipelineB = insertPipeline(tenantB);
  }

  @AfterEach
  void tearDown() {
    TenantRlsTestSupport.cleanupAll(
        () -> deletePipelines(tenantA),
        () -> deletePipelines(tenantB),
        () -> TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB),
        () -> TenantRlsTestSupport.deleteUser(dsl, userId));
  }

  private Long insertPipeline(long tenantId) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.fetchOne(
                    "INSERT INTO pipeline (name, created_by, tenant_id) VALUES (?, ?, ?)"
                        + " RETURNING id",
                    "wd230-" + TenantRlsTestSupport.nextTenantId(),
                    userId,
                    tenantId)
                .get(0, Long.class));
  }

  private void deletePipelines(Long tenantId) {
    if (tenantId != null) {
      inTenantFixture(
          tenantId, () -> dsl.execute("DELETE FROM pipeline WHERE tenant_id = ?", tenantId));
    }
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }

  /** 지정한 하트비트·시작 시각으로 실행 행을 직접 만든다. */
  private long insertExecution(
      long tenantId,
      long pipelineId,
      String status,
      LocalDateTime heartbeatAt,
      LocalDateTime startedAt) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.fetchOne(
                    "INSERT INTO pipeline_execution (pipeline_id, status, executed_by, tenant_id,"
                        + " heartbeat_at, started_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                    pipelineId,
                    status,
                    userId,
                    tenantId,
                    heartbeatAt,
                    startedAt)
                .get(0, Long.class));
  }

  private long insertStep(long tenantId, long executionId, String status) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.fetchOne(
                    "INSERT INTO pipeline_step_execution (execution_id, step_name, status,"
                        + " tenant_id) VALUES (?, 's', ?, ?) RETURNING id",
                    executionId,
                    status,
                    tenantId)
                .get(0, Long.class));
  }

  private String execStatus(long tenantId, long executionId) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.fetchOne("SELECT status FROM pipeline_execution WHERE id = ?", executionId)
                .get(0, String.class));
  }

  private LocalDateTime execHeartbeat(long tenantId, long executionId) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.fetchOne("SELECT heartbeat_at FROM pipeline_execution WHERE id = ?", executionId)
                .get(0, LocalDateTime.class));
  }

  private String stepStatus(long tenantId, long stepExecId) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.fetchOne("SELECT status FROM pipeline_step_execution WHERE id = ?", stepExecId)
                .get(0, String.class));
  }

  private List<PipelineExecutionRepository.StaleExecution> stale(
      long tenantId, LocalDateTime threshold) {
    return TenantContext.runScopedGet(
        tenantId, () -> repository.findStaleExecutions(tenantId, threshold));
  }

  private List<Long> staleIds(long tenantId, LocalDateTime threshold) {
    return stale(tenantId, threshold).stream()
        .map(PipelineExecutionRepository.StaleExecution::executionId)
        .toList();
  }

  // ---------- findStaleExecutions ----------

  @Test
  void stale_oldHeartbeat_isFoundWithPipelineId() {
    long old = insertExecution(tenantA, pipelineA, "RUNNING", now().minusMinutes(30), now());
    var found = stale(tenantA, now().minusMinutes(5));
    assertThat(found)
        .anySatisfy(
            s -> {
              assertThat(s.executionId()).isEqualTo(old);
              assertThat(s.pipelineId()).isEqualTo(pipelineA);
            });
  }

  @Test
  void stale_recentHeartbeat_isNotFound() {
    long recent = insertExecution(tenantA, pipelineA, "RUNNING", now(), now().minusHours(2));
    assertThat(staleIds(tenantA, now().minusMinutes(5))).doesNotContain(recent);
  }

  @Test
  void stale_pendingWithOldHeartbeat_isFound() {
    long pending = insertExecution(tenantA, pipelineA, "PENDING", now().minusMinutes(30), null);
    assertThat(staleIds(tenantA, now().minusMinutes(5))).contains(pending);
  }

  @Test
  void stale_nullHeartbeat_fallsBackToStartedAt() {
    long oldStart = insertExecution(tenantA, pipelineA, "RUNNING", null, now().minusHours(1));
    long freshStart = insertExecution(tenantA, pipelineA, "RUNNING", null, now());
    List<Long> ids = staleIds(tenantA, now().minusMinutes(5));
    assertThat(ids).contains(oldStart).doesNotContain(freshStart);
  }

  @Test
  void stale_nullHeartbeatAndStartedAt_fallsBackToCreatedAt() {
    // created_at 은 세션 시간대 NOW() 기본값이라 UTC 임계값과 어긋나므로, 명시적 UTC 값을 넣어 폴백 규칙만 고정한다.
    long old = insertExecution(tenantA, pipelineA, "PENDING", null, null);
    long fresh = insertExecution(tenantA, pipelineA, "PENDING", null, null);
    setCreatedAt(tenantA, old, now().minusHours(1));
    setCreatedAt(tenantA, fresh, now());
    List<Long> ids = staleIds(tenantA, now().minusMinutes(5));
    assertThat(ids).contains(old).doesNotContain(fresh);
  }

  @Test
  void stale_heartbeatWinsOverFreshStartedAt() {
    // COALESCE 우선순위 — 하트비트가 있으면 started_at 이 최근이어도 하트비트가 오래됐으면 오래됨.
    long id = insertExecution(tenantA, pipelineA, "RUNNING", now().minusHours(1), now());
    assertThat(staleIds(tenantA, now().minusMinutes(5))).contains(id);
  }

  private void setCreatedAt(long tenantId, long executionId, LocalDateTime t) {
    inTenantFixture(
        tenantId,
        () ->
            dsl.execute(
                "UPDATE pipeline_execution SET created_at = ? WHERE id = ?", t, executionId));
  }

  @Test
  void stale_finishedExecutions_areNotFound() {
    long c = insertExecution(tenantA, pipelineA, "COMPLETED", now().minusHours(1), now());
    long f = insertExecution(tenantA, pipelineA, "FAILED", now().minusHours(1), now());
    long x = insertExecution(tenantA, pipelineA, "CANCELLED", now().minusHours(1), now());
    assertThat(staleIds(tenantA, now().minusMinutes(5))).doesNotContain(c, f, x);
  }

  @Test
  void stale_otherTenantExecution_isNotFound() {
    long other = insertExecution(tenantB, pipelineB, "RUNNING", now().minusHours(1), now());
    assertThat(staleIds(tenantA, now().minusMinutes(5))).doesNotContain(other);
    // 컨텍스트는 A 인데 tenantId 인자만 B 인 호출도 RLS 가 가린다(이중 방어).
    List<Long> viaWrongContext =
        TenantContext.runScopedGet(
            tenantA,
            () ->
                repository.findStaleExecutions(tenantB, now().minusMinutes(5)).stream()
                    .map(PipelineExecutionRepository.StaleExecution::executionId)
                    .toList());
    assertThat(viaWrongContext).doesNotContain(other);
  }

  // ---------- failInterruptedAndCloseSteps / cancelAndSkipPendingSteps ----------

  @Test
  void failInterruptedAndCloseSteps_firstCallWins_secondReturnsFalse() {
    long id = insertExecution(tenantA, pipelineA, "RUNNING", now().minusHours(1), now());
    boolean first =
        TenantContext.runScopedGet(
            tenantA, () -> repository.failInterruptedAndCloseSteps(id, now(), "중단"));
    boolean second =
        TenantContext.runScopedGet(
            tenantA, () -> repository.failInterruptedAndCloseSteps(id, now(), "중단"));
    assertThat(first).isTrue();
    assertThat(second).isFalse();
    assertThat(execStatus(tenantA, id)).isEqualTo("FAILED");
    String msg =
        inTenantFixture(
            tenantA,
            () ->
                dsl.fetchOne("SELECT error_message FROM pipeline_execution WHERE id = ?", id)
                    .get(0, String.class));
    assertThat(msg).isEqualTo("중단");
  }

  @Test
  void failInterruptedAndCloseSteps_losingCall_changesNothing() {
    long id = insertExecution(tenantA, pipelineA, "COMPLETED", now().minusHours(1), now());
    long running = insertStep(tenantA, id, "RUNNING");
    long pending = insertStep(tenantA, id, "PENDING");
    boolean r =
        TenantContext.runScopedGet(
            tenantA, () -> repository.failInterruptedAndCloseSteps(id, now(), "중단"));
    assertThat(r).isFalse();
    assertThat(execStatus(tenantA, id)).isEqualTo("COMPLETED");
    // 진 호출은 단계도 건드리지 않는다.
    assertThat(stepStatus(tenantA, running)).isEqualTo("RUNNING");
    assertThat(stepStatus(tenantA, pending)).isEqualTo("PENDING");
  }

  @Test
  void failInterruptedAndCloseSteps_closesStepsInSameCall() {
    long id = insertExecution(tenantA, pipelineA, "RUNNING", now().minusHours(1), now());
    long running = insertStep(tenantA, id, "RUNNING");
    long pending = insertStep(tenantA, id, "PENDING");
    long done = insertStep(tenantA, id, "COMPLETED");
    assertThat(
            TenantContext.runScopedGet(
                tenantA, () -> repository.failInterruptedAndCloseSteps(id, now(), "중단")))
        .isTrue();
    assertThat(stepStatus(tenantA, running)).isEqualTo("FAILED");
    assertThat(stepStatus(tenantA, pending)).isEqualTo("SKIPPED");
    assertThat(stepStatus(tenantA, done)).isEqualTo("COMPLETED");
  }

  /** 단계 쓰기가 실패하면 실행 FAILED 도 함께 되돌려진다 — 같은 트랜잭션이라는 증거(DB 트리거로 단계 UPDATE 를 터뜨린다). */
  @Test
  void failInterruptedAndCloseSteps_stepWriteFailure_rollsBackExecutionToo() {
    long id = insertExecution(tenantA, pipelineA, "RUNNING", now().minusHours(1), now());
    long running = insertStep(tenantA, id, "RUNNING");
    withFailingStepTrigger(
        "wd230_atomic_boom",
        () -> {
          assertThatThrownBy(
                  () ->
                      TenantContext.runScoped(
                          tenantA,
                          () ->
                              repository.failInterruptedAndCloseSteps(
                                  id, now(), "wd230_atomic_boom")))
              .isInstanceOf(RuntimeException.class);
        });
    assertThat(execStatus(tenantA, id)).isEqualTo("RUNNING");
    assertThat(stepStatus(tenantA, running)).isEqualTo("RUNNING");
  }

  @Test
  void cancelAndSkipPendingSteps_wins_skipsPendingInSameCall() {
    long id = insertExecution(tenantA, pipelineA, "RUNNING", now().minusHours(1), now());
    long pending = insertStep(tenantA, id, "PENDING");
    long done = insertStep(tenantA, id, "COMPLETED");
    boolean won =
        TenantContext.runScopedGet(tenantA, () -> repository.cancelAndSkipPendingSteps(id, now()));
    assertThat(won).isTrue();
    assertThat(execStatus(tenantA, id)).isEqualTo("CANCELLED");
    assertThat(stepStatus(tenantA, pending)).isEqualTo("SKIPPED");
    assertThat(stepStatus(tenantA, done)).isEqualTo("COMPLETED");
  }

  @Test
  void cancelAndSkipPendingSteps_losingCall_changesNothing() {
    // 정리기가 이미 FAILED 로 닫은 실행 — 취소 쓰기는 지고 단계(정리기가 닫은 값)는 그대로여야 한다.
    long id = insertExecution(tenantA, pipelineA, "FAILED", now().minusHours(1), now());
    long pending = insertStep(tenantA, id, "PENDING");
    boolean won =
        TenantContext.runScopedGet(tenantA, () -> repository.cancelAndSkipPendingSteps(id, now()));
    assertThat(won).isFalse();
    assertThat(execStatus(tenantA, id)).isEqualTo("FAILED");
    assertThat(stepStatus(tenantA, pending)).isEqualTo("PENDING");
  }

  /** 단계 쓰기가 실패하면 CANCELLED 도 되돌려진다 — 같은 트랜잭션이라는 증거. */
  @Test
  void cancelAndSkipPendingSteps_stepWriteFailure_rollsBackExecutionToo() {
    long id = insertExecution(tenantA, pipelineA, "RUNNING", now().minusHours(1), now());
    long pending = insertStep(tenantA, id, "PENDING");
    // 취소 사유 문구가 고정이라 트리거는 SKIPPED 로 바뀌는 모든 단계 UPDATE 를 터뜨린다.
    withFailingStepTrigger(
        null,
        () ->
            assertThatThrownBy(
                    () ->
                        TenantContext.runScoped(
                            tenantA, () -> repository.cancelAndSkipPendingSteps(id, now())))
                .isInstanceOf(RuntimeException.class));
    assertThat(execStatus(tenantA, id)).isEqualTo("RUNNING");
    assertThat(stepStatus(tenantA, pending)).isEqualTo("PENDING");
  }

  /**
   * pipeline_step_execution UPDATE 가 {@code error_message} 가 {@code marker} 일 때(null 이면 SKIPPED 로
   * 바뀔 때) 예외를 던지게 하는 임시 트리거를 건 채 {@code body} 를 실행하고, 끝나면 반드시 지운다.
   */
  private void withFailingStepTrigger(String marker, Runnable body) {
    String cond =
        marker != null ? "NEW.error_message = '" + marker + "'" : "NEW.status = 'SKIPPED'";
    DSLContext ownerDsl = DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
    ownerDsl.execute(
        "CREATE OR REPLACE FUNCTION wd230_step_boom() RETURNS trigger AS $$ BEGIN IF "
            + cond
            + " THEN RAISE EXCEPTION 'wd230 boom'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
    ownerDsl.execute(
        "CREATE TRIGGER wd230_step_boom_trg BEFORE UPDATE ON pipeline_step_execution"
            + " FOR EACH ROW EXECUTE FUNCTION wd230_step_boom()");
    try {
      body.run();
    } finally {
      ownerDsl.execute("DROP TRIGGER IF EXISTS wd230_step_boom_trg ON pipeline_step_execution");
      ownerDsl.execute("DROP FUNCTION IF EXISTS wd230_step_boom()");
    }
  }

  // ---------- touchHeartbeat ----------

  @Test
  void touchHeartbeat_updatesActiveOnly() {
    long run = insertExecution(tenantA, pipelineA, "RUNNING", now().minusHours(1), now());
    long pend = insertExecution(tenantA, pipelineA, "PENDING", now().minusHours(1), null);
    long done = insertExecution(tenantA, pipelineA, "COMPLETED", now().minusHours(1), now());
    LocalDateTime t = now();
    int n =
        TenantContext.runScopedGet(
            tenantA, () -> repository.touchHeartbeat(List.of(run, pend, done), t));
    assertThat(n).isEqualTo(2);
    assertThat(execHeartbeat(tenantA, run)).isEqualToIgnoringNanos(t);
    assertThat(execHeartbeat(tenantA, pend)).isEqualToIgnoringNanos(t);
    assertThat(execHeartbeat(tenantA, done)).isBefore(t.minusMinutes(30));
  }

  @Test
  void touchHeartbeat_emptyCollection_isNoop() {
    int n = TenantContext.runScopedGet(tenantA, () -> repository.touchHeartbeat(List.of(), now()));
    assertThat(n).isZero();
  }

  // ---------- updateExecutionStatus ----------

  @Test
  void updateExecutionStatus_returnsFalseForFinishedExecution() {
    long id = insertExecution(tenantA, pipelineA, "FAILED", now().minusHours(1), now());
    boolean r =
        TenantContext.runScopedGet(
            tenantA, () -> repository.updateExecutionStatus(id, "RUNNING", now(), null));
    assertThat(r).isFalse();
    assertThat(execStatus(tenantA, id)).isEqualTo("FAILED");
  }

  @Test
  void updateExecutionStatus_running_setsHeartbeatToStartedAt() {
    long id = insertExecution(tenantA, pipelineA, "PENDING", null, null);
    LocalDateTime started = now().minusSeconds(3);
    boolean r =
        TenantContext.runScopedGet(
            tenantA, () -> repository.updateExecutionStatus(id, "RUNNING", started, null));
    assertThat(r).isTrue();
    assertThat(execStatus(tenantA, id)).isEqualTo("RUNNING");
    assertThat(execHeartbeat(tenantA, id)).isEqualToIgnoringNanos(started);
  }

  @Test
  void createExecution_stampsHeartbeat() {
    long id =
        TenantContext.runScopedGet(tenantA, () -> repository.createExecution(pipelineA, userId));
    assertThat(execHeartbeat(tenantA, id)).isNotNull();
  }

  // ---------- isStopRequested ----------

  @Test
  void isStopRequested_threeCases() {
    long active = insertExecution(tenantA, pipelineA, "RUNNING", now(), now());
    long cancelReq = insertExecution(tenantA, pipelineA, "RUNNING", now(), now());
    inTenantFixture(
        tenantA,
        () ->
            dsl.execute(
                "UPDATE pipeline_execution SET cancel_requested_at = ? WHERE id = ?",
                now(),
                cancelReq));
    long finished = insertExecution(tenantA, pipelineA, "FAILED", now(), now());
    assertThat(TenantContext.runScopedGet(tenantA, () -> repository.isStopRequested(active)))
        .isFalse();
    assertThat(TenantContext.runScopedGet(tenantA, () -> repository.isStopRequested(cancelReq)))
        .isTrue();
    assertThat(TenantContext.runScopedGet(tenantA, () -> repository.isStopRequested(finished)))
        .isTrue();
    // 행이 없으면 false.
    assertThat(TenantContext.runScopedGet(tenantA, () -> repository.isStopRequested(-1L)))
        .isFalse();
  }

  // ---------- updateStepExecution 게이트 ----------

  @Test
  void updateStepExecution_onFinishedExecution_isIgnored() {
    long id = insertExecution(tenantA, pipelineA, "FAILED", now().minusHours(1), now());
    long step = insertStep(tenantA, id, "FAILED");
    TenantContext.runScoped(
        tenantA,
        () -> repository.updateStepExecution(step, "COMPLETED", 1, null, null, null, now()));
    assertThat(stepStatus(tenantA, step)).isEqualTo("FAILED");
  }

  @Test
  void updateStepExecution_onActiveExecution_isApplied() {
    long id = insertExecution(tenantA, pipelineA, "RUNNING", now(), now());
    long step = insertStep(tenantA, id, "PENDING");
    TenantContext.runScoped(
        tenantA,
        () -> repository.updateStepExecution(step, "RUNNING", null, null, null, now(), null));
    assertThat(stepStatus(tenantA, step)).isEqualTo("RUNNING");
  }
}
