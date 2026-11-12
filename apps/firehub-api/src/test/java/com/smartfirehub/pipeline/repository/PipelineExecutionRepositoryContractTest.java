package com.smartfirehub.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.dto.ExecutionDetailResponse;
import com.smartfirehub.pipeline.dto.PipelineExecutionResponse;
import com.smartfirehub.pipeline.dto.StepExecutionResponse;
import com.smartfirehub.pipeline.exception.PipelineExecutionCancelledException;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository.ActiveExecutionState;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository.CancelRequestResult;
import com.smartfirehub.user.repository.AccessFixtures.ExecutionStored;
import com.smartfirehub.user.repository.AccessFixtures.StepExecutionStored;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * PipelineExecutionRepository 계약 테스트 — 공개 메서드 26 개 전부를 구현마다 하위 클래스로 실행한다(WD-230 하트비트 계약을 넓힘,
 * WD-708). jOOQ·MyBatis 는 PG, TiberoMyBatis 는 Tibero 실서버(두 계정 RLS)다.
 *
 * <p>pipeline_execution·pipeline_step_execution 은 테넌트 정책(RLS) 대상이다. 픽스처는 정책을 우회하는 소유자 연결로 만들고 읽는다.
 * 리포지토리 호출은 두 가지로 감싼다:
 *
 * <ul>
 *   <li>{@code self(...)} — 테넌트 컨텍스트만 세우고 바깥 트랜잭션은 열지 않는다. 구현 클래스의 {@code @Transactional} 이 스스로
 *       트랜잭션을 열어 테넌트를 심는지(배경 잡 경로 — 정리기·스윕 게이트), 한 호출의 쓰기가 원자적인지 확인하는 시나리오가 쓴다.
 *   <li>{@code inA(...)} — 호출자 트랜잭션(서비스 경로) 안에서 부른다.
 * </ul>
 *
 * <p>하트비트·stale 비교 시각은 jOOQ 와 같이 자바 UTC 시각을 바인딩한다. 오류 메시지 열은 Tibero 에서 바이트 한도 VARCHAR2 라 MyBatis
 * 구현이 앞부분만 남길 수 있어 「원문의 접두어」로 본다. 스텝 로그는 CLOB 이라 한도가 없다. 빈 문자열은 DB 마다 달라 쓰지 않는다.
 */
public abstract class PipelineExecutionRepositoryContractTest extends TenantPairContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract PipelineExecutionRepository repository();

  private long user;
  private long pipelineA;
  private long pipelineB;

  @BeforeEach
  void createPipelines() {
    user = fx.createUser(prefix + "-u", null);
    pipelineA = fx.createPipeline(tenantA, prefix + "-pa", true);
    pipelineB = fx.createPipeline(tenantB, prefix + "-pb", true);
  }

  /** UTC 벽시계 현재(마이크로초 — 두 DB 의 TIMESTAMP 정밀도에서 반올림이 생기지 않게). */
  private static LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  /** 테넌트 컨텍스트만 세우고(바깥 트랜잭션 없이) 리포지토리를 부른다 — 구현이 스스로 트랜잭션을 연다. */
  private <T> T self(long tenantId, Supplier<T> call) {
    return TenantContext.runScopedGet(tenantId, call::get);
  }

  private long exec(long pipelineId, String status, LocalDateTime hb, LocalDateTime started) {
    return fx.createExecution(pipelineId, user, status, hb, started);
  }

  private long step(long executionId, String status) {
    return fx.createStepExecution(executionId, null, "s", status, null, null);
  }

  private ExecutionStored stored(long executionId) {
    return fx.executionStored(executionId);
  }

  private String execStatus(long executionId) {
    return stored(executionId).status();
  }

  private String stepStatus(long stepExecId) {
    return fx.stepExecutionStored(stepExecId).status();
  }

  private List<Long> staleIds(long tenantId, LocalDateTime threshold) {
    return self(tenantId, () -> repository().findStaleExecutions(tenantId, threshold)).stream()
        .map(PipelineExecutionRepository.StaleExecution::executionId)
        .toList();
  }

  /** 긴 오류 메시지 — 한글 3바이트 × 33000 자라 Tibero VARCHAR2(65532) 바이트 한도를 넘는다. */
  private static String longError() {
    return "오류가".repeat(11_000);
  }

  /** 저장된 오류 메시지가 원문의 (충분히 긴) 접두어인지 — jOOQ 는 원문 전체, MyBatis 는 한도까지 자른 앞부분이다. */
  private static void assertPrefixOf(String stored, String original) {
    assertThat(stored).isNotNull();
    assertThat(original).startsWith(stored);
    assertThat(stored.length()).isGreaterThanOrEqualTo(TiberoTextLimits.MAX_ERROR_CHARS);
  }

  // ---------- findStaleExecutions ----------

  @Test
  @DisplayName("findStaleExecutions — 하트비트가 오래된 실행을 파이프라인 id 와 함께 찾는다")
  void stale_oldHeartbeat_isFoundWithPipelineId() {
    long old = exec(pipelineA, "RUNNING", now().minusMinutes(30), now());
    var found =
        self(tenantA, () -> repository().findStaleExecutions(tenantA, now().minusMinutes(5)));
    assertThat(found)
        .anySatisfy(
            s -> {
              assertThat(s.executionId()).isEqualTo(old);
              assertThat(s.pipelineId()).isEqualTo(pipelineA);
            });
  }

  @Test
  @DisplayName("findStaleExecutions — 최근 하트비트는 시작이 오래돼도 찾지 않는다")
  void stale_recentHeartbeat_isNotFound() {
    long recent = exec(pipelineA, "RUNNING", now(), now().minusHours(2));
    assertThat(staleIds(tenantA, now().minusMinutes(5))).doesNotContain(recent);
  }

  @Test
  @DisplayName("findStaleExecutions — 하트비트가 오래된 PENDING 도 찾는다")
  void stale_pendingWithOldHeartbeat_isFound() {
    long pending = exec(pipelineA, "PENDING", now().minusMinutes(30), null);
    assertThat(staleIds(tenantA, now().minusMinutes(5))).contains(pending);
  }

  @Test
  @DisplayName("findStaleExecutions — 하트비트가 없으면 started_at 으로 판정한다")
  void stale_nullHeartbeat_fallsBackToStartedAt() {
    long oldStart = exec(pipelineA, "RUNNING", null, now().minusHours(1));
    long freshStart = exec(pipelineA, "RUNNING", null, now());
    List<Long> ids = staleIds(tenantA, now().minusMinutes(5));
    assertThat(ids).contains(oldStart).doesNotContain(freshStart);
  }

  @Test
  @DisplayName("findStaleExecutions — 하트비트·시작이 모두 없으면 created_at 으로 판정한다")
  void stale_nullHeartbeatAndStartedAt_fallsBackToCreatedAt() {
    // created_at 기본값은 DB 마다 시간대가 달라 UTC 임계값과 어긋나므로, 명시적 UTC 값을 넣어 폴백 규칙만 고정한다.
    long old = exec(pipelineA, "PENDING", null, null);
    long fresh = exec(pipelineA, "PENDING", null, null);
    fx.setExecutionField(old, "created_at", now().minusHours(1));
    fx.setExecutionField(fresh, "created_at", now());
    List<Long> ids = staleIds(tenantA, now().minusMinutes(5));
    assertThat(ids).contains(old).doesNotContain(fresh);
  }

  @Test
  @DisplayName("findStaleExecutions — 하트비트가 있으면 started_at 이 최근이어도 하트비트로 판정한다(COALESCE 순서)")
  void stale_heartbeatWinsOverFreshStartedAt() {
    long id = exec(pipelineA, "RUNNING", now().minusHours(1), now());
    assertThat(staleIds(tenantA, now().minusMinutes(5))).contains(id);
  }

  @Test
  @DisplayName("findStaleExecutions — 끝난 실행(COMPLETED·FAILED·CANCELLED)은 찾지 않는다")
  void stale_finishedExecutions_areNotFound() {
    long c = exec(pipelineA, "COMPLETED", now().minusHours(1), now());
    long f = exec(pipelineA, "FAILED", now().minusHours(1), now());
    long x = exec(pipelineA, "CANCELLED", now().minusHours(1), now());
    assertThat(staleIds(tenantA, now().minusMinutes(5))).doesNotContain(c, f, x);
  }

  @Test
  @DisplayName("findStaleExecutions — 다른 테넌트 실행은 인자로 그 테넌트를 넘겨도 보이지 않는다")
  void stale_otherTenantExecution_isNotFound() {
    long other = exec(pipelineB, "RUNNING", now().minusHours(1), now());
    assertThat(staleIds(tenantA, now().minusMinutes(5))).doesNotContain(other);
    // 컨텍스트는 A 인데 tenantId 인자만 B 인 호출도 정책이 가린다(이중 방어).
    List<Long> viaWrongContext =
        self(tenantA, () -> repository().findStaleExecutions(tenantB, now().minusMinutes(5)))
            .stream()
            .map(PipelineExecutionRepository.StaleExecution::executionId)
            .toList();
    assertThat(viaWrongContext).doesNotContain(other);
    assertThat(staleIds(tenantB, now().minusMinutes(5))).contains(other);
  }

  // ---------- failInterruptedAndCloseSteps / cancelAndSkipPendingSteps ----------

  @Test
  @DisplayName("failInterruptedAndCloseSteps — 첫 호출만 이기고 두 번째는 false, 실행은 FAILED·메시지·완료 시각을 남긴다")
  void failInterruptedAndCloseSteps_firstCallWins_secondReturnsFalse() {
    long id = exec(pipelineA, "RUNNING", now().minusHours(1), now());
    LocalDateTime t = now();
    boolean first = self(tenantA, () -> repository().failInterruptedAndCloseSteps(id, t, "중단"));
    boolean second =
        self(tenantA, () -> repository().failInterruptedAndCloseSteps(id, now(), "중단2"));
    assertThat(first).isTrue();
    assertThat(second).isFalse();
    ExecutionStored s = stored(id);
    assertThat(s.status()).isEqualTo("FAILED");
    assertThat(s.errorMessage()).isEqualTo("중단");
    assertThat(s.completedAt()).isEqualTo(t);
  }

  @Test
  @DisplayName("failInterruptedAndCloseSteps — 진 호출은 실행도 단계도 바꾸지 않는다")
  void failInterruptedAndCloseSteps_losingCall_changesNothing() {
    long id = exec(pipelineA, "COMPLETED", now().minusHours(1), now());
    long running = step(id, "RUNNING");
    long pending = step(id, "PENDING");
    boolean r = self(tenantA, () -> repository().failInterruptedAndCloseSteps(id, now(), "중단"));
    assertThat(r).isFalse();
    assertThat(execStatus(id)).isEqualTo("COMPLETED");
    assertThat(stepStatus(running)).isEqualTo("RUNNING");
    assertThat(stepStatus(pending)).isEqualTo("PENDING");
  }

  @Test
  @DisplayName(
      "failInterruptedAndCloseSteps — 같은 호출에서 RUNNING 단계는 FAILED, PENDING 은 SKIPPED 로 닫고 끝난 단계는 둔다")
  void failInterruptedAndCloseSteps_closesStepsInSameCall() {
    long id = exec(pipelineA, "RUNNING", now().minusHours(1), now());
    long running = step(id, "RUNNING");
    long pending = step(id, "PENDING");
    long done = step(id, "COMPLETED");
    LocalDateTime t = now();
    assertThat(self(tenantA, () -> repository().failInterruptedAndCloseSteps(id, t, "중단")))
        .isTrue();
    StepExecutionStored r = fx.stepExecutionStored(running);
    assertThat(r.status()).isEqualTo("FAILED");
    assertThat(r.errorMessage()).isEqualTo("중단");
    assertThat(r.completedAt()).isEqualTo(t);
    StepExecutionStored p = fx.stepExecutionStored(pending);
    assertThat(p.status()).isEqualTo("SKIPPED");
    assertThat(p.errorMessage()).isEqualTo("중단");
    assertThat(p.completedAt()).isEqualTo(t);
    assertThat(stepStatus(done)).isEqualTo("COMPLETED");
  }

  /** 단계 쓰기가 실패하면 실행 FAILED 도 함께 되돌려진다 — 같은 트랜잭션이라는 증거(DB 트리거로 단계 UPDATE 를 터뜨린다). */
  @Test
  @DisplayName("failInterruptedAndCloseSteps — 단계 쓰기가 실패하면 실행 FAILED 도 되돌려진다(한 트랜잭션)")
  void failInterruptedAndCloseSteps_stepWriteFailure_rollsBackExecutionToo() {
    long id = exec(pipelineA, "RUNNING", now().minusHours(1), now());
    long running = step(id, "RUNNING");
    fx.withFailingStepExecutionUpdate(
        "wd230_atomic_boom",
        () ->
            assertThatThrownBy(
                    () ->
                        self(
                            tenantA,
                            () ->
                                repository()
                                    .failInterruptedAndCloseSteps(id, now(), "wd230_atomic_boom")))
                .isInstanceOf(RuntimeException.class));
    assertThat(execStatus(id)).isEqualTo("RUNNING");
    assertThat(stepStatus(running)).isEqualTo("RUNNING");
  }

  @Test
  @DisplayName("cancelAndSkipPendingSteps — 이기면 같은 호출에서 PENDING 단계를 SKIPPED 로 닫는다")
  void cancelAndSkipPendingSteps_wins_skipsPendingInSameCall() {
    long id = exec(pipelineA, "RUNNING", now().minusHours(1), now());
    long pending = step(id, "PENDING");
    long done = step(id, "COMPLETED");
    LocalDateTime t = now();
    boolean won = self(tenantA, () -> repository().cancelAndSkipPendingSteps(id, t));
    assertThat(won).isTrue();
    ExecutionStored s = stored(id);
    assertThat(s.status()).isEqualTo("CANCELLED");
    assertThat(s.completedAt()).isEqualTo(t);
    assertThat(s.errorMessage()).isEqualTo(PipelineExecutionCancelledException.CANCELLED_MESSAGE);
    StepExecutionStored p = fx.stepExecutionStored(pending);
    assertThat(p.status()).isEqualTo("SKIPPED");
    assertThat(p.errorMessage())
        .isEqualTo(PipelineExecutionCancelledException.SKIPPED_BY_CANCEL_MESSAGE);
    assertThat(stepStatus(done)).isEqualTo("COMPLETED");
  }

  @Test
  @DisplayName("cancelAndSkipPendingSteps — 이미 닫힌 실행에서는 지고 단계도 그대로다")
  void cancelAndSkipPendingSteps_losingCall_changesNothing() {
    // 정리기가 이미 FAILED 로 닫은 실행 — 취소 쓰기는 지고 단계(정리기가 닫은 값)는 그대로여야 한다.
    long id = exec(pipelineA, "FAILED", now().minusHours(1), now());
    long pending = step(id, "PENDING");
    boolean won = self(tenantA, () -> repository().cancelAndSkipPendingSteps(id, now()));
    assertThat(won).isFalse();
    assertThat(execStatus(id)).isEqualTo("FAILED");
    assertThat(stepStatus(pending)).isEqualTo("PENDING");
  }

  /** 단계 쓰기가 실패하면 CANCELLED 도 되돌려진다 — 같은 트랜잭션이라는 증거. */
  @Test
  @DisplayName("cancelAndSkipPendingSteps — 단계 쓰기가 실패하면 CANCELLED 도 되돌려진다(한 트랜잭션)")
  void cancelAndSkipPendingSteps_stepWriteFailure_rollsBackExecutionToo() {
    long id = exec(pipelineA, "RUNNING", now().minusHours(1), now());
    long pending = step(id, "PENDING");
    // 취소 사유 문구가 고정이라 트리거는 SKIPPED 로 바뀌는 모든 단계 UPDATE 를 터뜨린다.
    fx.withFailingStepExecutionUpdate(
        null,
        () ->
            assertThatThrownBy(
                    () -> self(tenantA, () -> repository().cancelAndSkipPendingSteps(id, now())))
                .isInstanceOf(RuntimeException.class));
    assertThat(execStatus(id)).isEqualTo("RUNNING");
    assertThat(stepStatus(pending)).isEqualTo("PENDING");
  }

  // ---------- touchHeartbeat ----------

  @Test
  @DisplayName("touchHeartbeat — 살아 있는(PENDING·RUNNING) 실행만 하트비트를 갱신한다")
  void touchHeartbeat_updatesActiveOnly() {
    long run = exec(pipelineA, "RUNNING", now().minusHours(1), now());
    long pend = exec(pipelineA, "PENDING", now().minusHours(1), null);
    long done = exec(pipelineA, "COMPLETED", now().minusHours(1), now());
    LocalDateTime t = now();
    int n = self(tenantA, () -> repository().touchHeartbeat(List.of(run, pend, done), t));
    assertThat(n).isEqualTo(2);
    assertThat(stored(run).heartbeatAt()).isEqualTo(t);
    assertThat(stored(pend).heartbeatAt()).isEqualTo(t);
    assertThat(stored(done).heartbeatAt()).isBefore(t.minusMinutes(30));
  }

  @Test
  @DisplayName("touchHeartbeat — 빈 목록은 아무것도 하지 않는다")
  void touchHeartbeat_emptyCollection_isNoop() {
    assertThat(self(tenantA, () -> repository().touchHeartbeat(List.of(), now()))).isZero();
  }

  @Test
  @DisplayName("touchHeartbeat — 1000 개 넘는 id 목록도 받고 다른 테넌트 실행은 건드리지 않는다")
  void touchHeartbeat_overThousandIds_andOtherTenantUntouched() {
    long run = exec(pipelineA, "RUNNING", now().minusHours(1), now());
    long other = exec(pipelineB, "RUNNING", now().minusHours(1), now());
    List<Long> ids = new ArrayList<>();
    for (long i = 1; i <= 1001; i++) {
      ids.add(-i);
    }
    ids.add(run);
    ids.add(other);
    LocalDateTime t = now();
    assertThat(self(tenantA, () -> repository().touchHeartbeat(ids, t))).isEqualTo(1);
    assertThat(stored(run).heartbeatAt()).isEqualTo(t);
    assertThat(stored(other).heartbeatAt()).isBefore(t.minusMinutes(30));
  }

  // ---------- createExecution ----------

  @Test
  @DisplayName("createExecution — PENDING·MANUAL 로 만들고 하트비트(자바 UTC)와 생성 시각(DB 시계)을 찍는다")
  void createExecution_defaults() {
    LocalDateTime before = now().minusSeconds(1);
    long id = inA(() -> repository().createExecution(pipelineA, user));
    ExecutionStored s = stored(id);
    assertThat(s.pipelineId()).isEqualTo(pipelineA);
    assertThat(s.status()).isEqualTo("PENDING");
    assertThat(s.executedBy()).isEqualTo(user);
    assertThat(s.triggeredBy()).isEqualTo("MANUAL");
    assertThat(s.triggerId()).isNull();
    assertThat(s.heartbeatAt()).isAfterOrEqualTo(before).isBeforeOrEqualTo(now().plusSeconds(1));
    assertThat(Duration.between(s.createdAt(), fx.dbWallClockNow()).abs())
        .isLessThan(Duration.ofMinutes(2));
    assertThat(s.startedAt()).isNull();
    assertThat(s.cancelRequestedAt()).isNull();
  }

  @Test
  @DisplayName("createExecution(4 인자) — 트리거 출처·id 를 남기고, 출처가 null 이면 MANUAL 이다")
  void createExecution_withTrigger() {
    long trigger =
        fx.createTrigger(pipelineA, prefix + "-t", "SCHEDULE", "{\"cron\":\"0 0 * * *\"}");
    long id = inA(() -> repository().createExecution(pipelineA, user, "SCHEDULE", trigger));
    assertThat(stored(id).triggeredBy()).isEqualTo("SCHEDULE");
    assertThat(stored(id).triggerId()).isEqualTo(trigger);
    long manual = inA(() -> repository().createExecution(pipelineA, user, null, null));
    assertThat(stored(manual).triggeredBy()).isEqualTo("MANUAL");
    assertThat(stored(manual).triggerId()).isNull();
  }

  @Test
  @DisplayName("createExecution — 파이프라인이 없으면 PipelineNotFoundException, 다른 FK 위반은 무결성 예외 그대로")
  void createExecution_foreignKeyViolations() {
    assertThatThrownBy(() -> self(tenantA, () -> repository().createExecution(-1L, user)))
        .isInstanceOf(PipelineNotFoundException.class);
    // 실행자 FK 위반은 파이프라인 FK 가 아니므로 404 로 바꾸지 않는다(제약 이름 판정이 실제로 걸러야 한다).
    assertThatThrownBy(() -> self(tenantA, () -> repository().createExecution(pipelineA, -1L)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ---------- updateExecutionStatus ----------

  @Test
  @DisplayName("updateExecutionStatus — 끝난 실행에는 false 이고 상태를 바꾸지 않는다")
  void updateExecutionStatus_returnsFalseForFinishedExecution() {
    long id = exec(pipelineA, "FAILED", now().minusHours(1), now());
    boolean r = self(tenantA, () -> repository().updateExecutionStatus(id, "RUNNING", now(), null));
    assertThat(r).isFalse();
    assertThat(execStatus(id)).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("updateExecutionStatus — RUNNING 전환은 시작 시각을 하트비트로도 쓴다")
  void updateExecutionStatus_running_setsHeartbeatToStartedAt() {
    long id = exec(pipelineA, "PENDING", null, null);
    LocalDateTime started = now().minusSeconds(3);
    boolean r =
        self(tenantA, () -> repository().updateExecutionStatus(id, "RUNNING", started, null));
    assertThat(r).isTrue();
    ExecutionStored s = stored(id);
    assertThat(s.status()).isEqualTo("RUNNING");
    assertThat(s.startedAt()).isEqualTo(started);
    assertThat(s.heartbeatAt()).isEqualTo(started);
  }

  @Test
  @DisplayName("updateExecutionStatus — null 인자는 기존 값을 두고, 완료 시각·오류 메시지를 쓴다")
  void updateExecutionStatus_partialFields() {
    LocalDateTime started = now().minusMinutes(5);
    LocalDateTime hb = now().minusMinutes(1);
    long id = exec(pipelineA, "RUNNING", hb, started);
    LocalDateTime done = now();
    assertThat(
            inA(() -> repository().updateExecutionStatus(id, "FAILED", null, done, "스텝 생성 전 실패")))
        .isTrue();
    ExecutionStored s = stored(id);
    assertThat(s.status()).isEqualTo("FAILED");
    assertThat(s.startedAt()).isEqualTo(started);
    assertThat(s.heartbeatAt()).isEqualTo(hb);
    assertThat(s.completedAt()).isEqualTo(done);
    assertThat(s.errorMessage()).isEqualTo("스텝 생성 전 실패");

    long id2 = exec(pipelineA, "RUNNING", hb, started);
    assertThat(inA(() -> repository().updateExecutionStatus(id2, "COMPLETED", null, done)))
        .isTrue();
    assertThat(stored(id2).errorMessage()).isNull();
    assertThat(stored(id2).completedAt()).isEqualTo(done);
  }

  @Test
  @DisplayName("오류 메시지 열 — 바이트 한도를 넘는 긴 메시지도 실패 없이 원문의 앞부분이 남는다")
  void longErrorMessages_areStoredAsPrefix() {
    String msg = longError();
    long a = exec(pipelineA, "RUNNING", now(), now());
    assertThat(inA(() -> repository().updateExecutionStatus(a, "FAILED", null, now(), msg)))
        .isTrue();
    assertPrefixOf(stored(a).errorMessage(), msg);

    long b = exec(pipelineA, "RUNNING", now(), now());
    long running = step(b, "RUNNING");
    assertThat(self(tenantA, () -> repository().failInterruptedAndCloseSteps(b, now(), msg)))
        .isTrue();
    assertPrefixOf(stored(b).errorMessage(), msg);
    assertPrefixOf(fx.stepExecutionStored(running).errorMessage(), msg);

    long c = exec(pipelineA, "RUNNING", now(), now());
    long s = step(c, "RUNNING");
    assertThat(
            inA(() -> repository().updateStepExecution(s, "FAILED", null, null, msg, null, now())))
        .isTrue();
    assertPrefixOf(fx.stepExecutionStored(s).errorMessage(), msg);
  }

  // ---------- finishUnlessCancelRequested ----------

  @Test
  @DisplayName("finishUnlessCancelRequested — 취소 요청이 없을 때만 마감하고, 요청이 있거나 끝난 실행이면 false")
  void finishUnlessCancelRequested_cases() {
    long ok = exec(pipelineA, "RUNNING", now(), now());
    long cancelled = exec(pipelineA, "RUNNING", now(), now());
    fx.setExecutionField(cancelled, "cancel_requested_at", now());
    long finished = exec(pipelineA, "FAILED", now(), now());
    LocalDateTime t = now();

    assertThat(inA(() -> repository().finishUnlessCancelRequested(ok, "COMPLETED", t))).isTrue();
    assertThat(stored(ok).status()).isEqualTo("COMPLETED");
    assertThat(stored(ok).completedAt()).isEqualTo(t);
    assertThat(inA(() -> repository().finishUnlessCancelRequested(cancelled, "COMPLETED", t)))
        .isFalse();
    assertThat(stored(cancelled).status()).isEqualTo("RUNNING");
    assertThat(stored(cancelled).completedAt()).isNull();
    assertThat(inA(() -> repository().finishUnlessCancelRequested(finished, "COMPLETED", t)))
        .isFalse();
    assertThat(stored(finished).status()).isEqualTo("FAILED");
  }

  // ---------- isStopRequested ----------

  @Test
  @DisplayName("isStopRequested — 진행 중 false, 취소 요청·끝난 실행·없는 행·다른 테넌트 행은 true")
  void isStopRequested_cases() {
    long active = exec(pipelineA, "RUNNING", now(), now());
    long cancelReq = exec(pipelineA, "RUNNING", now(), now());
    fx.setExecutionField(cancelReq, "cancel_requested_at", now());
    long finished = exec(pipelineA, "FAILED", now(), now());
    long other = exec(pipelineB, "RUNNING", now(), now());
    assertThat(inA(() -> repository().isStopRequested(active))).isFalse();
    assertThat(inA(() -> repository().isStopRequested(cancelReq))).isTrue();
    assertThat(inA(() -> repository().isStopRequested(finished))).isTrue();
    // 행이 없으면 true(WD-437) — 파이프라인 삭제 등으로 실행 행이 사라진 러너는 멈춘다.
    assertThat(inA(() -> repository().isStopRequested(-1L))).isTrue();
    // 다른 테넌트 실행은 정책이 가려 「없는 행」과 같다.
    assertThat(inA(() -> repository().isStopRequested(other))).isTrue();
    assertThat(inB(() -> repository().isStopRequested(other))).isFalse();
  }

  // ---------- findActiveExecutionState ----------

  @Test
  @DisplayName(
      "findActiveExecutionState — 진행 중 실행이 없으면 NONE, 취소 요청 안 된 것이 있으면 ACTIVE, 모두 요청됐으면 CANCELLING")
  void findActiveExecutionState_cases() {
    assertThat(inA(() -> repository().findActiveExecutionState(pipelineA)))
        .isEqualTo(ActiveExecutionState.NONE);
    exec(pipelineA, "COMPLETED", now(), now());
    assertThat(inA(() -> repository().findActiveExecutionState(pipelineA)))
        .isEqualTo(ActiveExecutionState.NONE);
    long running = exec(pipelineA, "RUNNING", now(), now());
    fx.setExecutionField(running, "cancel_requested_at", now());
    assertThat(inA(() -> repository().findActiveExecutionState(pipelineA)))
        .isEqualTo(ActiveExecutionState.CANCELLING);
    exec(pipelineA, "PENDING", now(), null);
    assertThat(inA(() -> repository().findActiveExecutionState(pipelineA)))
        .isEqualTo(ActiveExecutionState.ACTIVE);
    // 다른 테넌트 파이프라인의 진행 중 실행은 보이지 않는다.
    exec(pipelineB, "RUNNING", now(), now());
    assertThat(inA(() -> repository().findActiveExecutionState(pipelineB)))
        .isEqualTo(ActiveExecutionState.NONE);
  }

  // ---------- requestCancel / skipPendingStepsForCancel ----------

  @Test
  @DisplayName("requestCancel — PENDING 은 즉시 CANCELLED 로 마감하고 대기 스텝을 SKIPPED 로, 요청 시각은 저장값 그대로")
  void requestCancel_pending_closesImmediately() {
    long id = exec(pipelineA, "PENDING", now(), null);
    long pending = step(id, "PENDING");
    Optional<CancelRequestResult> r = inA(() -> repository().requestCancel(id, user));
    assertThat(r).isPresent();
    assertThat(r.get().status()).isEqualTo("CANCELLED");
    ExecutionStored s = stored(id);
    assertThat(s.status()).isEqualTo("CANCELLED");
    assertThat(s.cancelRequestedAt()).isNotNull().isEqualTo(r.get().cancelRequestedAt());
    assertThat(s.completedAt()).isEqualTo(s.cancelRequestedAt());
    assertThat(s.cancelRequestedBy()).isEqualTo(user);
    assertThat(s.errorMessage()).isEqualTo(PipelineExecutionCancelledException.CANCELLED_MESSAGE);
    assertThat(Duration.between(s.cancelRequestedAt(), now()).abs())
        .isLessThan(Duration.ofMinutes(2));
    StepExecutionStored p = fx.stepExecutionStored(pending);
    assertThat(p.status()).isEqualTo("SKIPPED");
    assertThat(p.errorMessage())
        .isEqualTo(PipelineExecutionCancelledException.SKIPPED_BY_CANCEL_MESSAGE);
  }

  @Test
  @DisplayName("requestCancel — RUNNING 은 요청만 남기고 재요청은 첫 요청 시각·요청자를 덮지 않는다, 끝난·없는 실행은 빈 값")
  void requestCancel_running_andFinished() {
    long other = fx.createUser(prefix + "-u2", null);
    long id = exec(pipelineA, "RUNNING", now(), now());
    Optional<CancelRequestResult> first = inA(() -> repository().requestCancel(id, user));
    assertThat(first).isPresent();
    assertThat(first.get().status()).isEqualTo("RUNNING");
    ExecutionStored s = stored(id);
    assertThat(s.status()).isEqualTo("RUNNING");
    assertThat(s.cancelRequestedAt()).isEqualTo(first.get().cancelRequestedAt());
    assertThat(s.cancelRequestedBy()).isEqualTo(user);
    assertThat(s.completedAt()).isNull();

    Optional<CancelRequestResult> again = inA(() -> repository().requestCancel(id, other));
    assertThat(again).isPresent();
    assertThat(again.get().status()).isEqualTo("RUNNING");
    assertThat(again.get().cancelRequestedAt()).isEqualTo(first.get().cancelRequestedAt());
    assertThat(stored(id).cancelRequestedBy()).isEqualTo(user);

    long done = exec(pipelineA, "COMPLETED", now(), now());
    assertThat(inA(() -> repository().requestCancel(done, user))).isEmpty();
    assertThat(stored(done).cancelRequestedAt()).isNull();
    assertThat(inA(() -> repository().requestCancel(-1L, user))).isEmpty();
    // 다른 테넌트 실행은 보이지 않아 빈 값이고 행도 그대로다.
    long hidden = exec(pipelineB, "PENDING", now(), null);
    assertThat(inA(() -> repository().requestCancel(hidden, user))).isEmpty();
    assertThat(stored(hidden).status()).isEqualTo("PENDING");
  }

  /**
   * 두 트랜잭션이 같은 PENDING 실행을 동시에 취소한다(Review Focus 4). 첫 트랜잭션이 조건부 UPDATE 로 행을 잡은 채 커밋을 늦추면 두 번째는 행
   * 잠금에서 기다렸다가 첫 커밋 뒤 조건을 다시 보고 0행이 된다 — 승자는 정확히 하나다.
   */
  @Test
  @DisplayName("requestCancel 경쟁 — PENDING 을 두 트랜잭션이 동시에 취소하면 정확히 하나만 CANCELLED 로 이긴다")
  void requestCancel_pendingRace_exactlyOneWinner() throws Exception {
    long other = fx.createUser(prefix + "-u2", null);
    long id = exec(pipelineA, "PENDING", now(), null);
    List<Optional<CancelRequestResult>> results =
        race(
            () -> repository().requestCancel(id, user),
            () -> repository().requestCancel(id, other));
    assertThat(results.get(0)).isPresent();
    assertThat(results.get(0).get().status()).isEqualTo("CANCELLED");
    assertThat(results.get(1)).isEmpty();
    ExecutionStored s = stored(id);
    assertThat(s.status()).isEqualTo("CANCELLED");
    assertThat(s.cancelRequestedBy()).isEqualTo(user);
    assertThat(s.cancelRequestedAt()).isEqualTo(results.get(0).get().cancelRequestedAt());
  }

  @Test
  @DisplayName("requestCancel 경쟁 — RUNNING 을 두 트랜잭션이 동시에 요청하면 둘 다 RUNNING 이고 첫 요청 시각·요청자만 남는다")
  void requestCancel_runningRace_firstRequestKept() throws Exception {
    long other = fx.createUser(prefix + "-u2", null);
    long id = exec(pipelineA, "RUNNING", now(), now());
    List<Optional<CancelRequestResult>> results =
        race(
            () -> repository().requestCancel(id, user),
            () -> repository().requestCancel(id, other));
    assertThat(results).allSatisfy(r -> assertThat(r).isPresent());
    assertThat(results.get(0).get().status()).isEqualTo("RUNNING");
    assertThat(results.get(1).get().status()).isEqualTo("RUNNING");
    assertThat(results.get(1).get().cancelRequestedAt())
        .isEqualTo(results.get(0).get().cancelRequestedAt());
    ExecutionStored s = stored(id);
    assertThat(s.cancelRequestedBy()).isEqualTo(user);
    assertThat(s.cancelRequestedAt()).isEqualTo(results.get(0).get().cancelRequestedAt());
  }

  /**
   * 두 작업자 스레드가 테넌트 A 의 각자 트랜잭션에서 호출한다. 첫째는 호출을 마친 뒤(행 잠금을 쥔 채) 1초 쉬고 커밋하고, 둘째는 첫째의 호출이 끝난 다음 시작해 그
   * 행 잠금에서 기다린다. 결과는 [첫째, 둘째] 순서다. 검증 읽기는 소유자 연결(픽스처)로만 한다 — Tibero 런타임 풀(2)을 두 작업자가 쥐고 있다.
   */
  private <T> List<T> race(Supplier<T> first, Supplier<T> second) throws Exception {
    CountDownLatch firstDone = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<T> f1 =
          pool.submit(
              () ->
                  inA(
                      () -> {
                        T r = first.get();
                        firstDone.countDown();
                        try {
                          Thread.sleep(1000);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return r;
                      }));
      Future<T> f2 =
          pool.submit(
              () -> {
                firstDone.await(60, TimeUnit.SECONDS);
                return inA(second);
              });
      T r1 = f1.get(60, TimeUnit.SECONDS);
      T r2 = f2.get(60, TimeUnit.SECONDS);
      return List.of(r1, r2);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("skipPendingStepsForCancel — PENDING 스텝만 취소 사유·완료 시각과 함께 SKIPPED 로 바꾼다")
  void skipPendingStepsForCancel_onlyPending() {
    long id = exec(pipelineA, "RUNNING", now(), now());
    long pending = step(id, "PENDING");
    long running = step(id, "RUNNING");
    long done = step(id, "COMPLETED");
    runA(() -> repository().skipPendingStepsForCancel(id));
    StepExecutionStored p = fx.stepExecutionStored(pending);
    assertThat(p.status()).isEqualTo("SKIPPED");
    assertThat(p.errorMessage())
        .isEqualTo(PipelineExecutionCancelledException.SKIPPED_BY_CANCEL_MESSAGE);
    assertThat(Duration.between(p.completedAt(), now()).abs()).isLessThan(Duration.ofMinutes(2));
    assertThat(stepStatus(running)).isEqualTo("RUNNING");
    assertThat(stepStatus(done)).isEqualTo("COMPLETED");
  }

  // ---------- hasRunningExecution ----------

  @Test
  @DisplayName("hasRunningExecution — 그 테넌트에 RUNNING 이 있을 때만 true, 다른 테넌트 실행은 인자를 바꿔도 보이지 않는다")
  void hasRunningExecution_cases() {
    exec(pipelineA, "PENDING", now(), null);
    exec(pipelineA, "COMPLETED", now(), now());
    assertThat(self(tenantA, () -> repository().hasRunningExecution(tenantA))).isFalse();
    exec(pipelineB, "RUNNING", now(), now());
    assertThat(self(tenantB, () -> repository().hasRunningExecution(tenantB))).isTrue();
    assertThat(self(tenantA, () -> repository().hasRunningExecution(tenantB))).isFalse();
    assertThat(self(tenantA, () -> repository().hasRunningExecution(tenantA))).isFalse();
    exec(pipelineA, "RUNNING", now(), now());
    assertThat(self(tenantA, () -> repository().hasRunningExecution(tenantA))).isTrue();
  }

  // ---------- findPipelineIdOfExecution / findExecutedById ----------

  @Test
  @DisplayName("findPipelineIdOfExecution·findExecutedById — 있으면 값, 없거나 다른 테넌트면 빈 값")
  void findPipelineIdAndExecutor() {
    long id = exec(pipelineA, "RUNNING", now(), now());
    long other = exec(pipelineB, "RUNNING", now(), now());
    assertThat(inA(() -> repository().findPipelineIdOfExecution(id))).contains(pipelineA);
    assertThat(inA(() -> repository().findExecutedById(id))).contains(user);
    assertThat(inA(() -> repository().findPipelineIdOfExecution(-1L))).isEmpty();
    assertThat(inA(() -> repository().findExecutedById(-1L))).isEmpty();
    assertThat(inA(() -> repository().findPipelineIdOfExecution(other))).isEmpty();
    assertThat(inA(() -> repository().findExecutedById(other))).isEmpty();
  }

  // ---------- 스텝 실행 ----------

  @Test
  @DisplayName("createStepExecution — PENDING 으로 만들고 스텝 id·이름 스냅샷을 남긴다(스텝 id null 허용)")
  void createStepExecution_storesSnapshot() {
    long stepId = fx.createStep(pipelineA, prefix + "-st", "SQL", "select 1", null, "REPLACE");
    long id = exec(pipelineA, "RUNNING", now(), now());
    long se = inA(() -> repository().createStepExecution(id, stepId, "이름 스냅샷"));
    StepExecutionStored s = fx.stepExecutionStored(se);
    assertThat(s.status()).isEqualTo("PENDING");
    assertThat(s.stepId()).isEqualTo(stepId);
    assertThat(s.stepName()).isEqualTo("이름 스냅샷");
    assertThat(s.startedAt()).isNull();
    long noStep = inA(() -> repository().createStepExecution(id, null, "지워진 스텝"));
    assertThat(fx.stepExecutionStored(noStep).stepId()).isNull();
    assertThat(noStep).isGreaterThan(se);
  }

  @Test
  @DisplayName(
      "hasOverlappingStepExecution — 같은 스텝의 RUNNING·내 시작 뒤 끝난 실행은 겹침, 앞서 끝난·PENDING·다른 스텝은 아님, 판정 불가면 겹침")
  void hasOverlappingStepExecution_cases() {
    LocalDateTime start = now().minusMinutes(10);
    long exec = exec(pipelineA, "RUNNING", now(), now());
    long s1 = fx.createStep(pipelineA, prefix + "-s1", "SQL", "select 1", null, "REPLACE");
    long s2 = fx.createStep(pipelineA, prefix + "-s2", "SQL", "select 1", null, "REPLACE");
    long s3 = fx.createStep(pipelineA, prefix + "-s3", "SQL", "select 1", null, "REPLACE");
    long s4 = fx.createStep(pipelineA, prefix + "-s4", "SQL", "select 1", null, "REPLACE");

    // 판정 불가: 행 없음·시작 시각 없음·스텝 id 없음.
    assertThat(inA(() -> repository().hasOverlappingStepExecution(-1L))).isTrue();
    long noStart = fx.createStepExecution(exec, s1, "x", "RUNNING", null, null);
    assertThat(inA(() -> repository().hasOverlappingStepExecution(noStart))).isTrue();
    long noStep = fx.createStepExecution(exec, null, "x", "RUNNING", start, null);
    assertThat(inA(() -> repository().hasOverlappingStepExecution(noStep))).isTrue();

    // s2: 같은 스텝의 다른 실행이 RUNNING → 겹침.
    long me2 = fx.createStepExecution(exec, s2, "x", "RUNNING", start, null);
    fx.createStepExecution(exec, s2, "x", "RUNNING", start.minusMinutes(5), null);
    assertThat(inA(() -> repository().hasOverlappingStepExecution(me2))).isTrue();

    // s3: 내 시작 뒤(같은 시각 포함)에 끝난 실행 → 겹침.
    long me3 = fx.createStepExecution(exec, s3, "x", "RUNNING", start, null);
    fx.createStepExecution(exec, s3, "x", "COMPLETED", start.minusMinutes(5), start);
    assertThat(inA(() -> repository().hasOverlappingStepExecution(me3))).isTrue();

    // s4: 내 시작 전에 끝난 실행·PENDING 실행·다른 스텝의 RUNNING → 겹치지 않음.
    long me4 = fx.createStepExecution(exec, s4, "x", "RUNNING", start, null);
    fx.createStepExecution(exec, s4, "x", "FAILED", start.minusMinutes(5), start.minusSeconds(1));
    fx.createStepExecution(exec, s4, "x", "PENDING", null, null);
    fx.createStepExecution(exec, s1, "x", "RUNNING", start, null);
    assertThat(inA(() -> repository().hasOverlappingStepExecution(me4))).isFalse();
  }

  @Test
  @DisplayName("updateStepProgress — 상태는 두고 출력 행 수·긴 로그(CLOB, 바이트 한도 없음)만 바꾸며 null 로그는 비운다")
  void updateStepProgress_onlyProgress() {
    long id = exec(pipelineA, "RUNNING", now(), now());
    long se = step(id, "RUNNING");
    String log = "진행 로그 한 줄입니다\n".repeat(8000);
    runA(() -> repository().updateStepProgress(se, 42, log));
    StepExecutionStored s = fx.stepExecutionStored(se);
    assertThat(s.status()).isEqualTo("RUNNING");
    assertThat(s.outputRows()).isEqualTo(42);
    assertThat(s.log()).isEqualTo(log);
    runA(() -> repository().updateStepProgress(se, null, null));
    assertThat(fx.stepExecutionStored(se).outputRows()).isNull();
    assertThat(fx.stepExecutionStored(se).log()).isNull();
  }

  @Test
  @DisplayName("setInjectedLastRunAt — 오프셋이 있는 시각을 같은 순간으로 남기고 null 은 비운다")
  void setInjectedLastRunAt_roundTripsInstant() {
    long id = exec(pipelineA, "RUNNING", now(), now());
    long se = step(id, "RUNNING");
    OffsetDateTime at =
        OffsetDateTime.now(ZoneOffset.ofHours(9)).minusDays(1).truncatedTo(ChronoUnit.MICROS);
    runA(() -> repository().setInjectedLastRunAt(se, at));
    assertThat(fx.stepExecutionStored(se).injectedLastRunAt()).isEqualTo(at.toInstant());
    runA(() -> repository().setInjectedLastRunAt(se, null));
    assertThat(fx.stepExecutionStored(se).injectedLastRunAt()).isNull();
  }

  @Test
  @DisplayName("updateStepExecution — 활성 실행이면 모든 필드를 쓰고, null 인자는 기존 값을 둔다")
  void updateStepExecution_allFields() {
    long id = exec(pipelineA, "RUNNING", now(), now());
    long se = step(id, "PENDING");
    LocalDateTime started = now().minusMinutes(2);
    LocalDateTime done = now();
    String log = "스텝 로그\n".repeat(10_000);
    assertThat(
            inA(
                () ->
                    repository()
                        .updateStepExecution(se, "RUNNING", null, null, null, started, null)))
        .isTrue();
    assertThat(
            inA(() -> repository().updateStepExecution(se, "FAILED", 7, log, "스텝 오류", null, done)))
        .isTrue();
    StepExecutionStored s = fx.stepExecutionStored(se);
    assertThat(s.status()).isEqualTo("FAILED");
    assertThat(s.outputRows()).isEqualTo(7);
    assertThat(s.log()).isEqualTo(log);
    assertThat(s.errorMessage()).isEqualTo("스텝 오류");
    assertThat(s.startedAt()).isEqualTo(started);
    assertThat(s.completedAt()).isEqualTo(done);
    // 다른 테넌트 스텝 실행은 보이지 않아 false.
    long otherExec = exec(pipelineB, "RUNNING", now(), now());
    long otherStep = step(otherExec, "PENDING");
    assertThat(
            inA(
                () ->
                    repository()
                        .updateStepExecution(otherStep, "RUNNING", null, null, null, now(), null)))
        .isFalse();
    assertThat(stepStatus(otherStep)).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("updateStepExecution — 부모 실행이 끝났으면 무시하고 false")
  void updateStepExecution_onFinishedExecution_isIgnored() {
    long id = exec(pipelineA, "FAILED", now().minusHours(1), now());
    long se = step(id, "FAILED");
    boolean r =
        self(
            tenantA,
            () -> repository().updateStepExecution(se, "COMPLETED", 1, null, null, null, now()));
    assertThat(r).isFalse();
    assertThat(stepStatus(se)).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("updateStepExecution — 부모 실행이 진행 중이면 반영하고 true")
  void updateStepExecution_onActiveExecution_isApplied() {
    long id = exec(pipelineA, "RUNNING", now(), now());
    long se = step(id, "PENDING");
    boolean r =
        self(
            tenantA,
            () -> repository().updateStepExecution(se, "RUNNING", null, null, null, now(), null));
    assertThat(r).isTrue();
    assertThat(stepStatus(se)).isEqualTo("RUNNING");
  }

  // ---------- 조회 ----------

  @Test
  @DisplayName(
      "findExecutionsByPipelineId·countExecutionsByPipelineId — 최신 id 순 페이지, 실행자·트리거 이름·취소 요청 시각을 싣는다")
  void findExecutionsByPipelineId_pagesNewestFirst() {
    fx.setUserName(user, "실행자 이름");
    long trigger =
        fx.createTrigger(pipelineA, prefix + "-trg", "SCHEDULE", "{\"cron\":\"0 0 * * *\"}");
    LocalDateTime started = now().minusMinutes(3);
    long e1 = exec(pipelineA, "COMPLETED", now(), started);
    fx.setExecutionField(e1, "completed_at", now());
    long e2 = inA(() -> repository().createExecution(pipelineA, user, "SCHEDULE", trigger));
    long e3 = exec(pipelineA, "RUNNING", now(), started);
    LocalDateTime cancelAt = now();
    fx.setExecutionField(e3, "cancel_requested_at", cancelAt);
    exec(pipelineB, "RUNNING", now(), now());

    List<PipelineExecutionResponse> page0 =
        inA(() -> repository().findExecutionsByPipelineId(pipelineA, 0, 2));
    assertThat(page0).extracting(PipelineExecutionResponse::id).containsExactly(e3, e2);
    PipelineExecutionResponse r3 = page0.get(0);
    assertThat(r3.pipelineId()).isEqualTo(pipelineA);
    assertThat(r3.status()).isEqualTo("RUNNING");
    assertThat(r3.executedBy()).isEqualTo("실행자 이름");
    assertThat(r3.startedAt()).isEqualTo(started);
    assertThat(r3.triggeredBy()).isEqualTo("MANUAL");
    assertThat(r3.triggerName()).isNull();
    assertThat(r3.cancelRequestedAt()).isEqualTo(cancelAt);
    assertThat(r3.createdAt()).isNotNull();
    PipelineExecutionResponse r2 = page0.get(1);
    assertThat(r2.triggeredBy()).isEqualTo("SCHEDULE");
    assertThat(r2.triggerName()).isEqualTo(prefix + "-trg");
    assertThat(r2.cancelRequestedAt()).isNull();

    List<PipelineExecutionResponse> page1 =
        inA(() -> repository().findExecutionsByPipelineId(pipelineA, 1, 2));
    assertThat(page1).extracting(PipelineExecutionResponse::id).containsExactly(e1);
    assertThat(page1.get(0).completedAt()).isNotNull();
    assertThat(inA(() -> repository().findExecutionsByPipelineId(pipelineA, 2, 2))).isEmpty();
    assertThat(inA(() -> repository().countExecutionsByPipelineId(pipelineA))).isEqualTo(3);
    // 다른 테넌트 파이프라인은 보이지 않는다.
    assertThat(inA(() -> repository().findExecutionsByPipelineId(pipelineB, 0, 10))).isEmpty();
    assertThat(inA(() -> repository().countExecutionsByPipelineId(pipelineB))).isZero();
  }

  @Test
  @DisplayName("findExecutionById — 파이프라인·실행자·취소 요청자 이름, 오류 메시지, 스텝 기록(id 순)을 싣는다")
  void findExecutionById_detail() {
    fx.setUserName(user, "실행자");
    long canceller = fx.createUser(prefix + "-c", null);
    fx.setUserName(canceller, "취소자");
    LocalDateTime started = now().minusMinutes(3);
    long id = exec(pipelineA, "RUNNING", now(), started);
    long se1 = step(id, "COMPLETED");
    long se2 = step(id, "PENDING");
    assertThat(inA(() -> repository().requestCancel(id, canceller))).isPresent();
    assertThat(inA(() -> repository().updateExecutionStatus(id, "FAILED", null, now(), "실패 원인")))
        .isTrue();

    ExecutionDetailResponse d = inA(() -> repository().findExecutionById(id)).orElseThrow();
    assertThat(d.id()).isEqualTo(id);
    assertThat(d.pipelineId()).isEqualTo(pipelineA);
    assertThat(d.pipelineName()).isEqualTo(prefix + "-pa");
    assertThat(d.status()).isEqualTo("FAILED");
    assertThat(d.executedBy()).isEqualTo("실행자");
    assertThat(d.startedAt()).isEqualTo(started);
    assertThat(d.completedAt()).isNotNull();
    assertThat(d.createdAt()).isNotNull();
    assertThat(d.errorMessage()).isEqualTo("실패 원인");
    assertThat(d.errorMasked()).isFalse();
    assertThat(d.cancelRequestedAt()).isEqualTo(stored(id).cancelRequestedAt());
    assertThat(d.cancelRequestedBy()).isEqualTo("취소자");
    assertThat(d.stepExecutions()).extracting(StepExecutionResponse::id).containsExactly(se1, se2);

    long plain = exec(pipelineA, "PENDING", now(), null);
    ExecutionDetailResponse p = inA(() -> repository().findExecutionById(plain)).orElseThrow();
    assertThat(p.cancelRequestedAt()).isNull();
    assertThat(p.cancelRequestedBy()).isNull();
    assertThat(p.errorMessage()).isNull();
    assertThat(p.stepExecutions()).isEmpty();

    assertThat(inA(() -> repository().findExecutionById(-1L))).isEmpty();
    long other = exec(pipelineB, "RUNNING", now(), now());
    assertThat(inA(() -> repository().findExecutionById(other))).isEmpty();
  }

  @Test
  @DisplayName(
      "findStepExecutionsByExecutionId — 이름은 스냅샷 우선·없으면 현재 스텝 이름, 지워진 스텝은 stepId null, 로그·시각 왕복")
  void findStepExecutionsByExecutionId_namesAndFields() {
    long stepId = fx.createStep(pipelineA, prefix + "-현재", "SQL", "select 1", null, "REPLACE");
    long id = exec(pipelineA, "RUNNING", now(), now());
    LocalDateTime started = now().minusMinutes(1);
    LocalDateTime done = now();
    long snap = fx.createStepExecution(id, stepId, "당시 이름", "COMPLETED", started, done);
    long fallback = fx.createStepExecution(id, stepId, null, "RUNNING", started, null);
    long deleted = fx.createStepExecution(id, null, "지워진 스텝", "FAILED", null, null);
    String log = "로그\n".repeat(30_000);
    runA(() -> repository().updateStepProgress(snap, 5, log));

    List<StepExecutionResponse> rows = inA(() -> repository().findStepExecutionsByExecutionId(id));
    assertThat(rows).extracting(StepExecutionResponse::id).containsExactly(snap, fallback, deleted);
    StepExecutionResponse a = rows.get(0);
    assertThat(a.stepId()).isEqualTo(stepId);
    assertThat(a.stepName()).isEqualTo("당시 이름");
    assertThat(a.status()).isEqualTo("COMPLETED");
    assertThat(a.outputRows()).isEqualTo(5);
    assertThat(a.log()).isEqualTo(log);
    assertThat(a.errorMessage()).isNull();
    assertThat(a.startedAt()).isEqualTo(started);
    assertThat(a.completedAt()).isEqualTo(done);
    assertThat(a.errorMasked()).isFalse();
    assertThat(rows.get(1).stepName()).isEqualTo(prefix + "-현재");
    assertThat(rows.get(2).stepId()).isNull();
    assertThat(rows.get(2).stepName()).isEqualTo("지워진 스텝");

    assertThat(inA(() -> repository().findStepExecutionsByExecutionId(-1L))).isEmpty();
    long other = exec(pipelineB, "RUNNING", now(), now());
    step(other, "PENDING");
    assertThat(inA(() -> repository().findStepExecutionsByExecutionId(other))).isEmpty();
  }

  // ---------- deleteCompletedCreatedBefore ----------

  @Test
  @DisplayName("deleteCompletedCreatedBefore — 기준보다 먼저 만든 COMPLETED 만 지우고 FAILED·최근 행·다른 테넌트는 둔다")
  void deleteCompletedCreatedBefore_onlyOldCompleted() {
    LocalDateTime old = now().minusDays(10);
    long oldDone = exec(pipelineA, "COMPLETED", now(), now());
    fx.setExecutionField(oldDone, "created_at", old);
    long oldStep = step(oldDone, "COMPLETED");
    long oldFailed = exec(pipelineA, "FAILED", now(), now());
    fx.setExecutionField(oldFailed, "created_at", old);
    long recentDone = exec(pipelineA, "COMPLETED", now(), now());
    fx.setExecutionField(recentDone, "created_at", now());
    long otherOld = exec(pipelineB, "COMPLETED", now(), now());
    fx.setExecutionField(otherOld, "created_at", old);

    int deleted = inA(() -> repository().deleteCompletedCreatedBefore(now().minusDays(1)));
    assertThat(deleted).isEqualTo(1);
    assertThat(stored(oldDone)).isNull();
    assertThat(fx.stepExecutionStored(oldStep)).as("스텝 실행은 FK CASCADE 로 함께 지워진다").isNull();
    assertThat(stored(oldFailed)).isNotNull();
    assertThat(stored(recentDone)).isNotNull();
    assertThat(stored(otherOld)).isNotNull();
  }
}
