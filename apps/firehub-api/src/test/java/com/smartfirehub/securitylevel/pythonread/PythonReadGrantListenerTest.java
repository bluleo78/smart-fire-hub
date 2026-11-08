package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
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

  /** 실행기를 막는 작업을 넣고 풀어 줄 래치를 돌려준다. */
  private CountDownLatch block() {
    CountDownLatch release = new CountDownLatch(1);
    executor.submit(
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
    verify(grants, times(3)).terminateSlotSessionsAfterCommit(TENANT, ALL);
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

  /** 큐 포화로 버려지면 표식을 지운다 — 다음 변경이 다시 넣는다. 변이: 버림 뒤 표식을 남기면 다음 변경이 합쳐진 것으로 보고 영영 돌지 않는다. */
  @Test
  void rejectedSubmit_releasesPendingMark() throws Exception {
    executor.shutdown();
    executor = new PythonReadSyncExecutor(1);
    listener = new PythonReadGrantListener(grants, executor);
    CountDownLatch release = block(); // 워커 점유
    executor.submit(() -> {}); // 큐(1) 점유
    listener.onLevelsChanged(LEVELS); // 버려진다
    release.countDown();
    drain();
    verify(grants, never()).syncTenantForLevelsChange();
    listener.onLevelsChanged(LEVELS);
    drain();
    verify(grants, times(1)).syncTenantForLevelsChange();
  }

  /** 커밋 직후 세션 끊기가 실행기 작업보다 먼저, 호출 스레드에서 돈다. 끊기 요청이 예외여도 커밋 콜백은 실패하지 않고 동기화는 넘긴다. */
  @Test
  void levelsChange_terminatesOnCallerThreadBeforeQueueing_andSurvivesTerminateErrors()
      throws Exception {
    when(grants.terminateSlotSessionsAfterCommit(TENANT, ALL))
        .thenThrow(new IllegalStateException("x"));
    listener.onLevelsChanged(LEVELS);
    drain();
    InOrder order = inOrder(grants);
    order.verify(grants).terminateSlotSessionsAfterCommit(TENANT, ALL);
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
  void datasetBeforeCommit_narrowed_doesNotTouchOtherRuns() {
    TenantContext.set(TENANT);
    TransactionSynchronizationManager.initSynchronization();
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L))
        .thenReturn(NarrowResult.confirmed(Set.of(1, 2)));

    listener.narrowDatasetBeforeCommit(DATASET);

    verify(grants, never()).invalidateScriptTokensInCurrentTx(anyLong());
    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
  }

  /** 리뷰 2 — 확인 못 했어도 막을 슬롯이 비면(넓어지는 변경·권한 없는 새 테이블의 클론) 아무것도 끊지 않는다. */
  @Test
  void datasetBeforeCommit_unconfirmedButNothingExposed_doesNothing() {
    TenantContext.set(TENANT);
    TransactionSynchronizationManager.initSynchronization();
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L))
        .thenReturn(NarrowResult.unconfirmed(Set.of()));

    listener.narrowDatasetBeforeCommit(DATASET);

    verify(grants, never()).invalidateScriptTokensInCurrentTx(anyLong());
    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
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
    assertThat(syncs).hasSize(1);
    TransactionSynchronizationManager.clearSynchronization();
    TenantContext.clear();
    syncs.get(0).afterCommit();
    drain();

    InOrder order = inOrder(grants);
    order.verify(grants).invalidateScriptTokensInCurrentTx(TENANT);
    order.verify(grants).terminateSlotSessionsAfterCommit(TENANT, Set.of(1, 2));
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
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
    }
    verify(grants, never()).narrowDatasetInCurrentTx(anyLong(), any(), anyLong());
    verify(grants, times(2)).invalidateScriptTokensInCurrentTx(TENANT);
    verify(grants, times(2)).terminateSlotSessionsAfterCommit(TENANT, ALL);
  }

  /** 예상 밖 예외(대체 경로 등록 실패 등)도 게시자에게 새지 않는다. */
  @Test
  void datasetBeforeCommit_unexpectedError_neverPropagates() {
    TenantContext.set(TENANT);
    when(grants.narrowDatasetInCurrentTx(42L, 1L, 3L)).thenThrow(new IllegalStateException("x"));
    assertThatCode(() -> listener.narrowDatasetBeforeCommit(DATASET)).doesNotThrowAnyException();
  }

  /** 데이터셋 등급 변경의 넓히기(테이블 재동기화)는 실행기에서, 이벤트 테넌트로 돈다. */
  @Test
  void datasetAfterCommit_queuesTableSyncUnderEventTenant() throws Exception {
    AtomicInteger seenTenant = new AtomicInteger(-1);
    doAnswer(
            inv -> {
              seenTenant.set((int) TenantContext.require("t"));
              return null;
            })
        .when(grants)
        .syncDataset(42L);
    listener.onDatasetLevelChanged(DATASET);
    drain();
    verify(grants).syncDataset(42L);
    assertThat(seenTenant.get()).isEqualTo((int) TENANT);
  }
}
