package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.event.DatasetSecurityLevelChangedEvent;
import com.smartfirehub.securitylevel.event.SecurityLevelsChangedEvent;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantBackend.NarrowResult;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 등급 이벤트 리스너의 단계 나눔·합치기 규칙(WD-624) — 스프링 없이 생성자로 만든다. 파사드는 가짜, 실행기는 실제 {@link
 * PythonReadSyncExecutor} (테스트용 작은 큐 생성자)라 "큐에 몇 번 들어갔는가" 를 운영 API 에 카운터를 두지 않고 호출 횟수로 센다.
 */
class PythonReadGrantListenerTest {

  private static final long TENANT = 5L;
  private static final SecurityLevelsChangedEvent LEVELS =
      new SecurityLevelsChangedEvent(TENANT, SecurityLevelsChangedEvent.Kind.REORDERED, null);
  private static final DatasetSecurityLevelChangedEvent DATASET =
      new DatasetSecurityLevelChangedEvent(
          TENANT, 42L, 1L, 3L, DatasetSecurityLevelChangedEvent.Cause.AUTO_RAISE);

  private static final Set<Integer> ALL = PythonReadSlots.ALL_SLOTS;
  private final PythonReadGrants grants = mock(PythonReadGrants.class);
  private PythonReadSyncExecutor executor = new PythonReadSyncExecutor(16);
  private PythonReadGrantListener listener = new PythonReadGrantListener(grants, executor);

  @AfterEach
  void tearDown() {
    executor.shutdown();
    TenantContext.clear();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /**
   * 실행기에 일반 작업 하나를 넣는다(테스트용) — WD-644 실행기에는 Runnable 제출 API 가 없으므로, 겹치지 않는 그룹의 재시도형 작업으로 넣고 곧바로
   * DONE 을 돌려준다. 실행기를 막아 "커밋 뒤 단계가 아직 안 돈" 창을 고정하는 데 쓴다.
   */
  private boolean submitBlocking(Runnable work) {
    return executor.submitRetryable(
        "test-block-" + BLOCK_SEQ.incrementAndGet(),
        "block",
        (items, lockWait) -> {
          work.run();
          return PythonReadSyncExecutor.Outcome.DONE;
        });
  }

  private static final java.util.concurrent.atomic.AtomicInteger BLOCK_SEQ =
      new java.util.concurrent.atomic.AtomicInteger();

  /** 실행기를 막는 작업을 넣고 풀어 줄 래치를 돌려준다. */
  private CountDownLatch block() {
    CountDownLatch release = new CountDownLatch(1);
    submitBlocking(
        () -> {
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    return release;
  }

  private void drain() throws InterruptedException {
    assertThat(executor.awaitIdle(Duration.ofSeconds(10))).isTrue();
  }

  /**
   * 아직 시작하지 않은 전체 동기화가 있으면 같은 테넌트의 다음 변경은 합쳐진다 — 큐에는 하나만 들어간다. 세션 끊기는 변경마다 커밋 직후 호출 스레드에서 돈다(실행기가
   * 막혀 있어도). 변이: 합치기 표식을 지우면 3번 돈다.
   */
  @Test
  void levelsChanges_whileSyncQueued_coalesceIntoOne_butTerminateEachTime() throws Exception {
    CountDownLatch release = block();
    for (int i = 0; i < 3; i++) {
      listener.onLevelsChanged(LEVELS);
    }
    verify(grants, times(3)).terminateSlotSessionsAfterCommit(TENANT, ALL, true);
    verify(grants, never()).syncTenantForLevelsChange();
    release.countDown();
    drain();
    verify(grants, times(1)).syncTenantForLevelsChange();
  }

  /**
   * 합치기 경합: 전체 동기화가 <b>실행 중일 때</b> 커밋된 변경은 그 작업에 반영되지 않았을 수 있으므로 작업을 하나 더 넣어야 한다(표식은 작업 시작 때 지운다).
   * 변이: 표식을 작업 끝(finally)에서 지우면 두 번째 변경이 합쳐진 것으로 보고 버려져 1번만 돈다.
   */
  @Test
  void levelsChange_arrivingWhileSyncRuns_triggersAnotherSync() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            inv -> {
              if (calls.incrementAndGet() == 1) {
                started.countDown();
                finish.await(10, TimeUnit.SECONDS);
              }
              return null;
            })
        .when(grants)
        .syncTenantForLevelsChange();

    listener.onLevelsChanged(LEVELS);
    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    listener.onLevelsChanged(LEVELS); // 첫 작업이 등급을 이미 읽은 뒤 커밋된 변경
    finish.countDown();
    drain();
    verify(grants, times(2)).syncTenantForLevelsChange();
  }

  /**
   * WD-713 (a) — 등급 정의 변경 전체 동기화를 실행기가 끝내 버리면(여기서는 "세션이 계속 다시 붙음" 영구 실패) 포기 지점에서 ERROR 한 줄에 테넌트·슬롯
   * 범위· 남는 표식 종류(REDEFINED·REDEFINED_APPLIED)와 다시 돌리는 방법이 남는다. 고치기 전에는 일반 영구 실패·버림 ERROR 뿐이었고, 버림
   * 문구는 "일 1회 동기화가 회복한다" 고 잘못 안내했다(일반 동기화는 표식 슬롯을 넓히지 않는다).
   */
  @Test
  void levelsSync_givenUp_logsErrorWithTenantSlotsAndRemainingMarkKinds() throws Exception {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(PythonReadGrantListener.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
        new ch.qos.logback.core.read.ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      doThrow(new IllegalStateException("PYTHON 슬롯 롤 세션이 계속 다시 붙어 넓히지 않았다"))
          .when(grants)
          .syncTenantForLevelsChange();
      listener.onLevelsChanged(LEVELS);
      drain();
      assertThat(logs.list)
          .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
          .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .anySatisfy(
              msg ->
                  assertThat(msg)
                      .contains("끝내")
                      .contains("tenant=" + TENANT)
                      .contains("slots=1~10")
                      .contains("REDEFINED")
                      .contains("REDEFINED_APPLIED")
                      .contains("등급 정의"));
    } finally {
      logger.detachAppender(logs);
    }
  }

  /** 동기화가 예외로 끝나도 표식이 남지 않는다 — 다음 변경이 다시 작업을 넣는다(지우기가 작업의 첫 문장이다). */
  @Test
  void failedSync_doesNotLeavePendingMark() throws Exception {
    doThrow(new IllegalStateException("db down"))
        .doNothing()
        .when(grants)
        .syncTenantForLevelsChange();
    listener.onLevelsChanged(LEVELS);
    drain();
    listener.onLevelsChanged(LEVELS);
    drain();
    verify(grants, times(2)).syncTenantForLevelsChange();
  }

  /**
   * 큐가 포화여도 등급 정의 변경 전체 동기화는 버리지 않는다(WD-664 1a) — 커밋 직전 토큰 번호 올리기가 실패했을 때의 유일한 대체 경로다. 같은 포화에서 데이터셋
   * 등급 변경 재동기화(일반 작업)는 여전히 버린다. 변이: 회수 필수 표시를 떼면 등급 정의 변경이 버려져 한 번도 돌지 않는다.
   */
  @Test
  void saturatedQueue_stillAcceptsLevelsSync_butDropsDatasetResync() throws Exception {
    executor.shutdown();
    executor = new PythonReadSyncExecutor(1);
    listener = new PythonReadGrantListener(grants, executor);
    CountDownLatch release = block(); // 워커 점유 — 상한 1 이 찼다
    listener.onLevelsChanged(LEVELS); // 회수 필수 — 포화여도 받아들인다
    listener.onDatasetLevelChanged(DATASET); // 일반 — 포화라 버려진다
    release.countDown();
    drain();
    verify(grants, times(1)).syncTenantForLevelsChange();
    verify(grants, never()).syncDatasetsWith(any(), any());
    // 버려진 일반 제출은 그룹 작업을 남기지 않는다 — 다음 변경이 합쳐진 것으로 보고 영영 돌지 않는 일이 없다(옛 표식 회귀 방어).
    when(grants.syncDatasetsWith(any(), any()))
        .thenReturn(PythonReadGrantBackend.BatchResult.done(java.util.Map.of()));
    listener.onDatasetLevelChanged(DATASET);
    drain();
    verify(grants, times(1)).syncDatasetsWith(any(), any());
  }

  /**
   * 커밋 직후 세션 끊기 요청(표식 + 요청, 기다리지 않음 — WD-664 9)이 실행기 작업보다 먼저, 호출 스레드에서 돈다. 끊기 요청이 예외여도 커밋 콜백은 실패하지
   * 않고 동기화는 넘긴다.
   */
  @Test
  void levelsChange_terminatesOnCallerThreadBeforeQueueing_andSurvivesTerminateErrors()
      throws Exception {
    doThrow(new IllegalStateException("x"))
        .when(grants)
        .terminateSlotSessionsAfterCommit(TENANT, ALL, true);
    listener.onLevelsChanged(LEVELS);
    drain();
    InOrder order = inOrder(grants);
    order.verify(grants).terminateSlotSessionsAfterCommit(TENANT, ALL, true);
    order.verify(grants).syncTenantForLevelsChange();
  }

  /**
   * 리뷰 4+5 — 커밋 직전 토큰 무효화가 실패해도(false·예상 밖 예외) 리스너는 예외를 내지 않는다: 게시자(등급 변경)를 롤백시키지 않는다. 그때는 커밋 뒤 전체
   * 동기화가 넓히기 전에 번호를 올린다.
   */
  @Test
  void levelsBeforeCommit_invalidatesTokens_neverThrows() {
    listener.invalidateScriptTokensBeforeLevelsCommit(LEVELS);
    verify(grants).invalidateScriptTokensInCurrentTx(TENANT);

    doThrow(new IllegalStateException("db down"))
        .when(grants)
        .invalidateScriptTokensInCurrentTx(TENANT);
    assertThatCode(() -> listener.invalidateScriptTokensBeforeLevelsCommit(LEVELS))
        .doesNotThrowAnyException();
  }

  /** 좁히기가 확인되면 대체 경로(토큰 무효화·세션 끊기·뒷정리)를 쓰지 않는다 — 잦은 자동 상향이 동시 실행을 끊지 않게. */
  @Test
  void datasetBeforeCommit_narrowed_doesNotTouchOtherRuns() throws Exception {
    TenantContext.set(TENANT);
    TransactionSynchronizationManager.initSynchronization();
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L))
        .thenReturn(NarrowResult.confirmed(Set.of(1, 2)));

    listener.narrowDatasetBeforeCommit(DATASET);

    verify(grants, never()).invalidateScriptTokensInCurrentTx(anyLong());
    assertOnlyRollbackResyncRegistered();
  }

  /** 등록된 커밋 콜백이 롤백 뒤 재동기화(WD-701 3차) 하나뿐인지 본다 — 커밋되면 아무것도 하지 않는다(끊기·표식·뒷정리 없음). */
  private void assertOnlyRollbackResyncRegistered() throws InterruptedException {
    List<TransactionSynchronization> syncs =
        TransactionSynchronizationManager.getSynchronizations();
    assertThat(syncs).hasSize(1);
    syncs.get(0).afterCommit();
    syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    verify(grants, never()).requestSlotSessionTermination(anyLong(), any());
    verify(grants, never()).terminateSlotSessionsAfterCommit(anyLong(), any(), anyBoolean());
    assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
    verify(grants, never()).syncDatasetsWith(any(), any());
  }

  /**
   * WD-664 7(설계 결정 Q2 A) — 회수가 확인되고 실제로 회수한 슬롯이 있으면, 커밋된 뒤에만 <b>그 슬롯만</b>(막을 슬롯 전부가 아니라) 끊기를 요청한다.
   * 커밋 전(커밋 직전 단계)에는 요청하지 않고, 롤백되면(회수도 되돌려진다) 요청하지 않는다. 표식·토큰 무효화·뒷정리 같은 대체 경로는 쓰지 않는다. 고치기 전에는 확인된
   * 경로가 회수만 하고 아무것도 끊지 않았다.
   */
  @Test
  void datasetBeforeCommit_confirmedWithRevoke_requestsKillOfRevokedSlotsOnlyAfterCommit() {
    TenantContext.set(TENANT);
    TransactionSynchronizationManager.initSynchronization();
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L))
        .thenReturn(NarrowResult.confirmed(Set.of(1, 2), Set.of(1)));
    when(grants.requestSlotSessionTermination(TENANT, Set.of(1)))
        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(1));

    listener.narrowDatasetBeforeCommit(DATASET);
    verify(grants, never()).requestSlotSessionTermination(anyLong(), any());
    List<TransactionSynchronization> syncs =
        TransactionSynchronizationManager.getSynchronizations();
    assertThat(syncs).as("끊기 요청 + 롤백 뒤 재동기화(WD-701 3차)").hasSize(2);
    TransactionSynchronizationManager.clearSynchronization();

    syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    verify(grants, never()).requestSlotSessionTermination(anyLong(), any());

    syncs.get(0).afterCommit();
    verify(grants).requestSlotSessionTermination(TENANT, Set.of(1));
    verify(grants, never()).terminateSlotSessionsAfterCommit(anyLong(), any(), anyBoolean());
    verify(grants, never()).invalidateScriptTokensInCurrentTx(anyLong());
  }

  /**
   * WD-664 9 — 커밋 콜백은 끊기를 기다리지 않는다: 끊기가 영영 끝나지 않아도(소유자 풀 막힘) 커밋 콜백은 곧바로 돌아온다. 회수 미확인 대체 경로도 같다(표식 +
   * 요청만).
   */
  @Test
  void afterCommitCallbacks_neverWaitForKill() {
    TenantContext.set(TENANT);
    TransactionSynchronizationManager.initSynchronization();
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L))
        .thenReturn(NarrowResult.confirmed(Set.of(1, 2), Set.of(1, 2)));
    when(grants.requestSlotSessionTermination(TENANT, Set.of(1, 2)))
        .thenReturn(new java.util.concurrent.CompletableFuture<>()); // 영영 끝나지 않는 끊기
    listener.narrowDatasetBeforeCommit(DATASET);
    List<TransactionSynchronization> syncs =
        TransactionSynchronizationManager.getSynchronizations();
    TransactionSynchronizationManager.clearSynchronization();
    long start = System.nanoTime();
    syncs.get(0).afterCommit();
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(300L);
  }

  /**
   * WD-664 7 + 1b — 확인된 회수 뒤 끊기 요청이 실패하면(큐 포화·소유자 풀 오류) 실행기에서 다시 한다. 다시 해도 못 끊으면 일시
   * 실패(SessionKillFailed)라 재시도 상한까지 다시 한다 — 끊으면 멈춘다. 이벤트 테넌트로 돈다.
   */
  @Test
  void datasetConfirmedKill_requestFails_retriedOnExecutorUntilKilled() throws Exception {
    TenantContext.set(TENANT);
    TransactionSynchronizationManager.initSynchronization();
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L))
        .thenReturn(NarrowResult.confirmed(Set.of(1, 2), Set.of(2)));
    when(grants.requestSlotSessionTermination(TENANT, Set.of(2)))
        .thenReturn(
            java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("owner pool busy")));
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger seenTenant = new AtomicInteger(-1);
    doAnswer(
            inv -> {
              seenTenant.set((int) TenantContext.require("t"));
              if (calls.incrementAndGet() == 1) {
                throw new PythonReadGrantBackend.SessionKillFailed("x", null);
              }
              return null;
            })
        .when(grants)
        .terminateSlotSessionsOrThrow(TENANT, Set.of(2));

    listener.narrowDatasetBeforeCommit(DATASET);
    List<TransactionSynchronization> syncs =
        TransactionSynchronizationManager.getSynchronizations();
    TransactionSynchronizationManager.clearSynchronization();
    TenantContext.clear();
    syncs.get(0).afterCommit();
    drain();

    assertThat(calls.get()).as("첫 재시도는 못 끊어 한 번 더").isEqualTo(2);
    assertThat(seenTenant.get()).isEqualTo((int) TENANT);
    verify(grants, never()).markSlotsUnconfirmed(anyLong(), any());
  }

  /**
   * PR #1015 리뷰 반영(WD-701 3차) — 확인된 회수 뒤 끊기 재시도가 <b>모두</b> 실패해 실행기가 버리면 그 슬롯에 미확인 표식을
   * 단다(fail-closed — 실행 준비가 먼저 끊고, 못 끊으면 거부한다). 첫 요청 실패만으로는 달지 않는다. 고치기 전에는 ERROR 로그만 남고 표식이 없었다.
   */
  @Test
  void datasetConfirmedKill_allRetriesFail_marksSlotsUnconfirmed() throws Exception {
    PythonReadSyncExecutor shortRetry =
        new PythonReadSyncExecutor(
            16,
            new PythonReadSyncExecutor.RetryPolicy(
                Duration.ofMillis(2), Duration.ofMillis(10), 3, 2));
    PythonReadGrantListener l = new PythonReadGrantListener(grants, shortRetry);
    try {
      TenantContext.set(TENANT);
      TransactionSynchronizationManager.initSynchronization();
      when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L))
          .thenReturn(NarrowResult.confirmed(Set.of(1, 2), Set.of(2)));
      when(grants.requestSlotSessionTermination(TENANT, Set.of(2)))
          .thenReturn(
              java.util.concurrent.CompletableFuture.failedFuture(
                  new IllegalStateException("owner pool busy")));
      AtomicInteger calls = new AtomicInteger();
      doAnswer(
              inv -> {
                calls.incrementAndGet();
                throw new PythonReadGrantBackend.SessionKillFailed("x", null);
              })
          .when(grants)
          .terminateSlotSessionsOrThrow(TENANT, Set.of(2));

      l.narrowDatasetBeforeCommit(DATASET);
      List<TransactionSynchronization> syncs =
          TransactionSynchronizationManager.getSynchronizations();
      TransactionSynchronizationManager.clearSynchronization();
      TenantContext.clear();
      syncs.get(0).afterCommit();
      verify(grants, never()).markSlotsUnconfirmed(anyLong(), any());
      assertThat(shortRetry.awaitIdle(Duration.ofSeconds(10))).isTrue();

      assertThat(calls.get()).as("첫 시도 + 재시도 2번 모두 실패").isEqualTo(3);
      verify(grants, times(1)).markSlotsUnconfirmed(TENANT, Set.of(2));
    } finally {
      shortRetry.shutdown();
    }
  }

  /** 끊기 재시도를 실행기가 받지 않으면(종료 중) 곧바로 미확인 표식을 단다(WD-701 3차) — 버려진 재시도도 막기를 빠뜨리지 않는다. */
  @Test
  void datasetConfirmedKill_retryRejected_marksSlotsUnconfirmed() {
    TenantContext.set(TENANT);
    TransactionSynchronizationManager.initSynchronization();
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L))
        .thenReturn(NarrowResult.confirmed(Set.of(1, 2), Set.of(1)));
    when(grants.requestSlotSessionTermination(TENANT, Set.of(1)))
        .thenReturn(
            java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("x")));

    listener.narrowDatasetBeforeCommit(DATASET);
    List<TransactionSynchronization> syncs =
        TransactionSynchronizationManager.getSynchronizations();
    TransactionSynchronizationManager.clearSynchronization();
    executor.shutdown();
    syncs.get(0).afterCommit();

    verify(grants).markSlotsUnconfirmed(TENANT, Set.of(1));
  }

  /**
   * WD-701 3차 — 발행 트랜잭션이 롤백되면(커밋 직전 회수가 데이터 저장소·Tibero 에서 따로 커밋됐을 수 있다) 그 데이터셋 재동기화를 이벤트 테넌트로 실행기에
   * 넣어 줄어든 권한을 되살린다. 확인된 경로·대체 경로 모두다. 커밋되면 이 콜백은 아무것도 넣지 않는다(커밋 뒤 재동기화는 {@code
   * onDatasetLevelChanged} 몫). 고치기 전에는 롤백 뒤 아무것도 돌지 않아, 분리 설치에서 슬롯이 실행 준비·일 1회 동기화까지 그 테이블을 못 읽었다.
   */
  @Test
  void datasetBeforeCommit_rolledBack_queuesResyncUnderEventTenant() throws Exception {
    AtomicInteger seenTenant = new AtomicInteger(-1);
    when(grants.syncDatasetsWith(any(), any()))
        .thenAnswer(
            inv -> {
              seenTenant.set((int) TenantContext.require("t"));
              return PythonReadGrantBackend.BatchResult.done(java.util.Map.of());
            });
    for (NarrowResult result :
        List.of(
            NarrowResult.confirmed(Set.of(1, 2), Set.of(1)),
            NarrowResult.unconfirmed(Set.of(1, 2)))) {
      TenantContext.set(TENANT);
      TransactionSynchronizationManager.initSynchronization();
      when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L)).thenReturn(result);
      listener.narrowDatasetBeforeCommit(DATASET);
      List<TransactionSynchronization> syncs =
          TransactionSynchronizationManager.getSynchronizations();
      TransactionSynchronizationManager.clearSynchronization();
      TenantContext.clear();
      for (TransactionSynchronization sync : syncs) {
        sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
      }
      drain();
    }
    verify(grants, times(2))
        .syncDatasetsWith(org.mockito.ArgumentMatchers.eq(List.of("42")), any());
    assertThat(seenTenant.get()).isEqualTo((int) TENANT);
    verify(grants, never()).requestSlotSessionTermination(anyLong(), any());
    verify(grants, never()).terminateSlotSessionsAfterCommit(anyLong(), any(), anyBoolean());
  }

  /** 리뷰 2 — 확인 못 했어도 막을 슬롯이 비면(넓어지는 변경·권한 없는 새 테이블의 클론) 아무것도 끊지 않는다. */
  @Test
  void datasetBeforeCommit_unconfirmedButNothingExposed_doesNothing() throws Exception {
    TenantContext.set(TENANT);
    TransactionSynchronizationManager.initSynchronization();
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L))
        .thenReturn(NarrowResult.unconfirmed(Set.of()));

    listener.narrowDatasetBeforeCommit(DATASET);

    verify(grants, never()).invalidateScriptTokensInCurrentTx(anyLong());
    assertOnlyRollbackResyncRegistered();
  }

  /**
   * 리뷰 1·2 — 좁히기를 확인하지 못하면 같은 트랜잭션에서 토큰을 무효로 하고, 커밋 직후 <b>그 테이블을 더는 읽으면 안 되는 슬롯만</b> 끊은 뒤, 실행기에서
   * 뒷정리(다시 끊기 + 잠금 안 회수)를 이벤트 테넌트로 돌린다. 토큰 무효화 결과가 뒷정리에 전달된다(실패했으면 뒷정리가 번호를 올린다).
   */
  @Test
  void datasetBeforeCommit_unconfirmed_blocksOnlyExposedSlots_andQueuesCleanup() throws Exception {
    TenantContext.set(TENANT);
    TransactionSynchronizationManager.initSynchronization();
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L))
        .thenReturn(NarrowResult.unconfirmed(Set.of(1, 2)));
    when(grants.invalidateScriptTokensInCurrentTx(TENANT)).thenReturn(false);
    AtomicInteger seenTenant = new AtomicInteger(-1);
    doAnswer(
            inv -> {
              seenTenant.set((int) TenantContext.require("t"));
              return null;
            })
        .when(grants)
        .syncDatasetAfterUnconfirmedNarrowing(42L, Set.of(1, 2), false);

    listener.narrowDatasetBeforeCommit(DATASET);
    List<TransactionSynchronization> syncs =
        TransactionSynchronizationManager.getSynchronizations();
    assertThat(syncs).as("대체 경로 + 롤백 뒤 재동기화(WD-701 3차)").hasSize(2);
    TransactionSynchronizationManager.clearSynchronization();
    TenantContext.clear();
    syncs.get(0).afterCommit();
    drain();

    InOrder order = inOrder(grants);
    order.verify(grants).invalidateScriptTokensInCurrentTx(TENANT);
    order.verify(grants).terminateSlotSessionsAfterCommit(TENANT, Set.of(1, 2), false);
    order.verify(grants).syncDatasetAfterUnconfirmedNarrowing(42L, Set.of(1, 2), false);
    assertThat(seenTenant.get()).isEqualTo((int) TENANT);
  }

  /**
   * 리뷰 4+5 — 이벤트 테넌트가 트랜잭션 테넌트와 다르면(또는 컨텍스트가 없으면) 좁히기를 부르지 않고, 예외 없이(게시자 롤백 없음) ERROR 와 함께 이벤트 테넌트의
   * 모든 슬롯을 막는다.
   */
  @Test
  void datasetBeforeCommit_tenantMismatch_failsClosedWithoutThrowing() {
    for (Long context : new Long[] {TENANT + 1, null}) {
      if (context == null) {
        TenantContext.clear();
      } else {
        TenantContext.set(context);
      }
      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThatCode(() -> listener.narrowDatasetBeforeCommit(DATASET))
            .doesNotThrowAnyException();
        assertThat(TransactionSynchronizationManager.getSynchronizations())
            .as("대체 경로 + 롤백 뒤 재동기화(WD-701 3차)")
            .hasSize(2);
        TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
    }
    verify(grants, never()).narrowDatasetInCurrentTx(anyLong(), any(), anyLong());
    verify(grants, times(2)).invalidateScriptTokensInCurrentTx(TENANT);
    verify(grants, times(2)).terminateSlotSessionsAfterCommit(TENANT, ALL, false);
  }

  /** 예상 밖 예외(대체 경로 등록 실패 등)도 게시자에게 새지 않는다. */
  @Test
  void datasetBeforeCommit_unexpectedError_neverPropagates() {
    TenantContext.set(TENANT);
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L)).thenThrow(new IllegalStateException("x"));
    assertThatCode(() -> listener.narrowDatasetBeforeCommit(DATASET)).doesNotThrowAnyException();
  }

  /**
   * WD-701 3차 리뷰 SF4 — 좁히기가 예외로 끝나도 롤백 재동기화는 등록된다(회수가 데이터 저장소에 이미 커밋됐을 수 있다). 롤백되면 그 데이터셋 재동기화가
   * 실행기에 들어간다. 고치기 전에는 등록이 try 안 끝에 있어 예외면 건너뛰었다.
   */
  @Test
  void datasetBeforeCommit_unexpectedError_stillRegistersRollbackResync() throws Exception {
    TenantContext.set(TENANT);
    TransactionSynchronizationManager.initSynchronization();
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L)).thenThrow(new IllegalStateException("x"));
    when(grants.syncDatasetsWith(any(), any()))
        .thenReturn(PythonReadGrantBackend.BatchResult.done(java.util.Map.of()));

    assertThatCode(() -> listener.narrowDatasetBeforeCommit(DATASET)).doesNotThrowAnyException();
    List<TransactionSynchronization> syncs =
        TransactionSynchronizationManager.getSynchronizations();
    assertThat(syncs).as("롤백 뒤 재동기화만").hasSize(1);
    TransactionSynchronizationManager.clearSynchronization();
    TenantContext.clear();
    syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    drain();
    verify(grants).syncDatasetsWith(org.mockito.ArgumentMatchers.eq(List.of("42")), any());
  }

  /**
   * WD-701 3차 리뷰 B1 — 분리 설치에서 확인된 회수가 플랫폼보다 먼저 커밋됐으면(테넌트 잠금도 그때 풀려 옛 등급으로 다시 GRANT 될 수 있다) 커밋 뒤 회수
   * 필수 뒷정리(잠금 안 새 등급 회수 → 회수·되살아난 슬롯만 끊기)를 넣는다. 실행기가 포화여도 받아들이고, 회수한 슬롯 끊기 요청도 곧바로 넣는다. 대체 경로(토큰
   * 무효화·표식)는 쓰지 않고, 롤백되면 아무것도 넣지 않는다. 고치기 전에는 끊기 요청만 해 되살아난 GRANT 가 남았다.
   */
  @Test
  void datasetBeforeCommit_confirmedSeparately_queuesCriticalLockedCleanup_evenWhenSaturated()
      throws Exception {
    executor.shutdown();
    executor = new PythonReadSyncExecutor(1);
    listener = new PythonReadGrantListener(grants, executor);
    TenantContext.set(TENANT);
    TransactionSynchronizationManager.initSynchronization();
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L))
        .thenReturn(NarrowResult.confirmed(Set.of(2), Set.of(1)).separately());
    when(grants.requestSlotSessionTermination(TENANT, Set.of(1)))
        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(1));
    AtomicInteger seenTenant = new AtomicInteger(-1);
    doAnswer(
            inv -> {
              seenTenant.set((int) TenantContext.require("t"));
              return null;
            })
        .when(grants)
        .syncDatasetAfterSeparateNarrowing(42L, Set.of(1));

    listener.narrowDatasetBeforeCommit(DATASET);
    List<TransactionSynchronization> syncs =
        TransactionSynchronizationManager.getSynchronizations();
    assertThat(syncs).as("분리 설치 뒷정리 + 롤백 뒤 재동기화").hasSize(2);
    TransactionSynchronizationManager.clearSynchronization();
    TenantContext.clear();

    syncs.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    verify(grants, never()).requestSlotSessionTermination(anyLong(), any());

    CountDownLatch release = block(); // 워커 점유 — 상한 1 이 찼다
    syncs.get(0).afterCommit();
    verify(grants).requestSlotSessionTermination(TENANT, Set.of(1));
    release.countDown();
    drain();
    verify(grants).syncDatasetAfterSeparateNarrowing(42L, Set.of(1));
    verify(grants, never()).syncDatasetAfterUnconfirmedNarrowing(anyLong(), any(), anyBoolean());
    assertThat(seenTenant.get()).isEqualTo((int) TENANT);
    verify(grants, never()).invalidateScriptTokensInCurrentTx(anyLong());
    verify(grants, never()).terminateSlotSessionsAfterCommit(anyLong(), any(), anyBoolean());
    verify(grants, never()).markSlotsUnconfirmed(anyLong(), any());
  }

  /**
   * WD-701 3차 리뷰 B1 — 분리 설치 뒷정리를 실행기가 끝내 버리면(여기선 종료 중 제출 거부) 그 슬롯에 미확인 표식을 단다(실행 준비가 먼저 끊고 새 등급으로
   * 맞춘다).
   */
  @Test
  void datasetConfirmedSeparately_cleanupRejected_marksSlotsUnconfirmed() {
    TenantContext.set(TENANT);
    TransactionSynchronizationManager.initSynchronization();
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L))
        .thenReturn(NarrowResult.confirmed(Set.of(), Set.of(1)).separately());
    when(grants.requestSlotSessionTermination(TENANT, Set.of(1)))
        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(1));
    listener.narrowDatasetBeforeCommit(DATASET);
    List<TransactionSynchronization> syncs =
        TransactionSynchronizationManager.getSynchronizations();
    TransactionSynchronizationManager.clearSynchronization();
    TenantContext.clear();
    executor.shutdown();

    syncs.get(0).afterCommit();
    verify(grants).markSlotsUnconfirmed(TENANT, Set.of(1));
    verify(grants, never()).syncDatasetAfterSeparateNarrowing(anyLong(), any());
  }

  /**
   * 데이터셋 등급 변경의 넓히기(테이블 재동기화)는 실행기에서, 이벤트 테넌트로 돈다 — WD-644 재시도형 경로라 처음에는 시도형 잠금(대기 0)으로 부르고, 바쁘면
   * (BUSY) 다시 부른다. 변이: 대기형으로 올리면(잠금 대기 값을 무시) 바쁨을 돌려줄 길이 없어 두 번째 호출이 없다.
   */
  @Test
  void datasetAfterCommit_queuesRetryableSyncUnderEventTenant() throws Exception {
    AtomicInteger seenTenant = new AtomicInteger(-1);
    AtomicInteger calls = new AtomicInteger();
    List<Duration> waits = new java.util.concurrent.CopyOnWriteArrayList<>();
    doAnswer(
            inv -> {
              seenTenant.set((int) TenantContext.require("t"));
              waits.add(inv.getArgument(1));
              // 첫 시도는 잠금이 바빴다
              return calls.incrementAndGet() > 1
                  ? PythonReadGrantBackend.BatchResult.done(java.util.Map.of())
                  : PythonReadGrantBackend.BatchResult.BUSY;
            })
        .when(grants)
        .syncDatasetsWith(any(), any());
    listener.onDatasetLevelChanged(DATASET);
    drain();
    verify(grants, times(2)).syncDatasetsWith(List.of("42"), waits.get(0));
    assertThat(waits.get(0)).isZero();
    assertThat(seenTenant.get()).isEqualTo((int) TENANT);
  }

  /** 실행기가 막혀 있는 동안 같은 테넌트의 데이터셋 등급 변경들은 한 작업으로 모여 한 번에 동기화된다(WD-644 테넌트 묶음). */
  @Test
  void datasetAfterCommit_sameTenantChanges_areBatched() throws Exception {
    when(grants.syncDatasetsWith(any(), any()))
        .thenReturn(PythonReadGrantBackend.BatchResult.done(java.util.Map.of()));
    CountDownLatch release = block();
    listener.onDatasetLevelChanged(DATASET);
    listener.onDatasetLevelChanged(
        new DatasetSecurityLevelChangedEvent(
            TENANT, 43L, 1L, 3L, DatasetSecurityLevelChangedEvent.Cause.AUTO_RAISE));
    release.countDown();
    drain();
    verify(grants, times(1)).syncDatasetsWith(List.of("42", "43"), Duration.ZERO);
  }
}
