package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.config.TransactionTemplateConfig;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 차트 데이터·SQL 편집기 동시 실행 게이트 단위 테스트(WD-599) — 상한 안 즉시 통과, 전체·사용자 상한에서 기다렸다 통과, 기다림 초과 429, 중복 close
 * 1회 반납, 인터럽트 시 카운터 유지, 0 이하 끔.
 *
 * <p>스프링 없이 게이트만 직접 만든다. 실제 경로 배선은 InteractiveQueryConcurrencyIntegrationTest 가 본다.
 */
class InteractiveQueryConcurrencyGateTest {

  /** 거절이 429 + QUERY_CONCURRENCY_LIMIT + 재시도 안내인지 확인한다. */
  private static void assertRejected(Throwable e) {
    assertThat(e).isInstanceOf(CodedApiException.class);
    CodedApiException c = (CodedApiException) e;
    assertThat(c.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(c.code()).isEqualTo(AnalyticsQueryConcurrencyGate.LIMIT_CODE);
    assertThat(c.getMessage()).contains("다시 시도");
  }

  /** 대기자 수가 n 이 될 때까지 기다린다 — sleep 으로 「기다리는 중」을 가정하지 않는다(부하가 큰 CI 에서 스레드가 아직 줄에 서지 않았을 수 있다). */
  private static void awaitWaiters(InteractiveQueryConcurrencyGate gate, int n) {
    await().atMost(10, TimeUnit.SECONDS).until(() -> gate.waitingCount() == n);
  }

  /** 다른 스레드에서 허가를 얻는다 — 기다리는 동안 테스트 스레드가 반납할 수 있게. */
  private static CompletableFuture<AnalyticsQueryConcurrencyGate.Permit> acquireAsync(
      InteractiveQueryConcurrencyGate gate, long userId) {
    return CompletableFuture.supplyAsync(() -> gate.acquire(userId));
  }

  @Test
  void withinLimits_passesImmediately() {
    InteractiveQueryConcurrencyGate gate = new InteractiveQueryConcurrencyGate(4, 3, 10_000);
    long start = System.nanoTime();
    AnalyticsQueryConcurrencyGate.Permit a = gate.acquire(1L);
    AnalyticsQueryConcurrencyGate.Permit b = gate.acquire(1L);
    AnalyticsQueryConcurrencyGate.Permit c = gate.acquire(2L);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1_000);
    assertThat(gate.activeTotal()).isEqualTo(3);
    assertThat(gate.activeFor(1L)).isEqualTo(2);
    a.close();
    b.close();
    c.close();
    assertThat(gate.activeTotal()).isZero();
    assertThat(gate.trackedUsers()).isEmpty();
  }

  @Test
  void totalLimit_waitsUntilReleased() throws Exception {
    InteractiveQueryConcurrencyGate gate = new InteractiveQueryConcurrencyGate(1, 3, 10_000);
    AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(1L);

    CompletableFuture<AnalyticsQueryConcurrencyGate.Permit> waiter = acquireAsync(gate, 2L);
    awaitWaiters(gate, 1);
    // 전체 몫이 없으니 기다리는 중이어야 한다(바로 429 가 아니다).
    assertThat(waiter).isNotDone();

    held.close();
    AnalyticsQueryConcurrencyGate.Permit got = waiter.get(5, TimeUnit.SECONDS);
    assertThat(gate.activeTotal()).isEqualTo(1);
    assertThat(gate.activeFor(2L)).isEqualTo(1);
    got.close();
    assertThat(gate.activeTotal()).isZero();
  }

  @Test
  void perUserLimit_waitsForSameUser_butOtherUserPassesImmediately() throws Exception {
    InteractiveQueryConcurrencyGate gate = new InteractiveQueryConcurrencyGate(4, 1, 10_000);
    AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(1L);

    CompletableFuture<AnalyticsQueryConcurrencyGate.Permit> sameUser = acquireAsync(gate, 1L);
    awaitWaiters(gate, 1);
    assertThat(sameUser).isNotDone();

    // 다른 사용자는 전체 몫이 남아 있어 기다리는 사용자 뒤에 막히지 않는다.
    AnalyticsQueryConcurrencyGate.Permit other = acquireAsync(gate, 2L).get(2, TimeUnit.SECONDS);
    assertThat(sameUser).isNotDone();

    held.close();
    AnalyticsQueryConcurrencyGate.Permit got = sameUser.get(5, TimeUnit.SECONDS);
    got.close();
    other.close();
    assertThat(gate.activeTotal()).isZero();
    assertThat(gate.trackedUsers()).isEmpty();
  }

  /** 도착 순서 입장 — 자리가 하나 나면 먼저 줄 선 A 가 얻고, 뒤에 온 B 는 계속 기다린다. */
  @Test
  void fifo_earlierWaiterGetsFreedSlot_laterKeepsWaiting() throws Exception {
    InteractiveQueryConcurrencyGate gate = new InteractiveQueryConcurrencyGate(1, 3, 10_000);
    AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(1L);

    CompletableFuture<AnalyticsQueryConcurrencyGate.Permit> a = acquireAsync(gate, 2L);
    awaitWaiters(gate, 1);
    CompletableFuture<AnalyticsQueryConcurrencyGate.Permit> b = acquireAsync(gate, 3L);
    awaitWaiters(gate, 2);
    assertThat(a).isNotDone();
    assertThat(b).isNotDone();

    held.close();
    AnalyticsQueryConcurrencyGate.Permit gotA = a.get(5, TimeUnit.SECONDS);
    // A 가 입장하며 줄에서 빠졌고 B 만 남아 기다린다 — A 가 얻은 뒤의 상태라 sleep 없이 바로 확인할 수 있다.
    awaitWaiters(gate, 1);
    assertThat(b).as("뒤에 온 B 는 A 가 반납할 때까지 기다린다").isNotDone();
    assertThat(gate.activeFor(2L)).isEqualTo(1);

    gotA.close();
    b.get(5, TimeUnit.SECONDS).close();
    assertThat(gate.activeTotal()).isZero();
    assertThat(gate.trackedUsers()).isEmpty();
  }

  /** 앞선 대기자가 시간 초과로 빠지면 번호표도 지워져 뒤 대기자를 막지 않는다. */
  @Test
  void timedOutWaiter_doesNotBlockLaterWaiters() throws Exception {
    InteractiveQueryConcurrencyGate gate = new InteractiveQueryConcurrencyGate(1, 3, 300);
    AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(1L);
    Throwable e = org.assertj.core.api.Assertions.catchThrowable(() -> gate.acquire(2L));
    assertRejected(e);
    held.close();
    // 빠진 대기자의 번호표가 남아 있으면 자리가 있어도 양보하다 시간 초과된다.
    gate.acquire(3L).close();
    assertThat(gate.activeTotal()).isZero();
  }

  /**
   * 반납은 들어갈 수 있는 대기자를 골라 깨운다. 가장 오래 기다린 A(사용자 1)는 사용자 상한에 걸려 있어 다른 사용자의 반납으로는 못 들어간다. 그 반납이 A 만 깨우고
   * 끝나면(공용 조건의 signal 하나) 뒤의 B(사용자 3)는 자리가 있는데도 시간 초과(429)까지 잔다.
   */
  @Test
  void release_wakesFirstAdmissibleWaiter_skippingPerUserCappedOne() throws Exception {
    // 대기 마감을 크게(30초) 잡는다 — 신호를 잃으면 B 는 마감까지 자다 429 가 되므로, 그보다 훨씬 짧은 20초 안에 B 가 허가를 받는지로 판정한다.
    // 절대 시간 상한(수 초)을 두지 않아 부하가 큰 CI 에서도 흔들리지 않는다.
    InteractiveQueryConcurrencyGate gate = new InteractiveQueryConcurrencyGate(2, 1, 30_000);
    AnalyticsQueryConcurrencyGate.Permit user1 = gate.acquire(1L);
    AnalyticsQueryConcurrencyGate.Permit user2 = gate.acquire(2L);

    CompletableFuture<AnalyticsQueryConcurrencyGate.Permit> a = acquireAsync(gate, 1L);
    awaitWaiters(gate, 1);
    CompletableFuture<AnalyticsQueryConcurrencyGate.Permit> b = acquireAsync(gate, 3L);
    awaitWaiters(gate, 2);

    user2.close();
    // 전체 몫 하나가 났다 — A 는 사용자 상한(1)에 걸려 못 들어가므로 B 가 깨어 얻어야 한다(429 가 아니라 허가). 신호를 잃었다면 B 는 30초 마감까지
    // 자므로 20초 안에 끝나지 않는다(TimeoutException 으로 실패).
    AnalyticsQueryConcurrencyGate.Permit gotB = b.get(20, TimeUnit.SECONDS);
    assertThat(a).isNotDone();
    assertThat(gate.activeFor(3L)).isEqualTo(1);

    // 사용자 1 의 몫이 돌아오면 A 도 깨어 들어간다(전체 몫: user1 반납 → B 와 A 가 2 자리).
    user1.close();
    AnalyticsQueryConcurrencyGate.Permit gotA = a.get(5, TimeUnit.SECONDS);
    gotA.close();
    gotB.close();
    assertThat(gate.activeTotal()).isZero();
    assertThat(gate.waitingCount()).isZero();
    assertThat(gate.trackedUsers()).isEmpty();
  }

  /** 반납이 겹쳐도 연쇄 깨우기로 들어갈 수 있는 대기자가 모두 들어간다 — 입장한 대기자가 나가며 다음 대기자를 깨운다. */
  @Test
  void multipleReleases_chainWakeAllAdmissibleWaiters() throws Exception {
    // 대기 마감은 크게(30초) — 연쇄가 끊기면 남은 대기자는 마감까지 자므로 20초 안에 허가를 못 받아 실패한다.
    InteractiveQueryConcurrencyGate gate = new InteractiveQueryConcurrencyGate(3, 3, 30_000);
    AnalyticsQueryConcurrencyGate.Permit h1 = gate.acquire(1L);
    AnalyticsQueryConcurrencyGate.Permit h2 = gate.acquire(2L);
    AnalyticsQueryConcurrencyGate.Permit h3 = gate.acquire(3L);
    CompletableFuture<AnalyticsQueryConcurrencyGate.Permit> w1 = acquireAsync(gate, 4L);
    awaitWaiters(gate, 1);
    CompletableFuture<AnalyticsQueryConcurrencyGate.Permit> w2 = acquireAsync(gate, 5L);
    awaitWaiters(gate, 2);
    CompletableFuture<AnalyticsQueryConcurrencyGate.Permit> w3 = acquireAsync(gate, 6L);
    awaitWaiters(gate, 3);

    // 깨어난 대기자가 잠금을 다시 잡기 전에 연달아 반납한다 — 반납 하나가 하나씩만 깨워도 연쇄로 셋 다 들어가야 한다.
    h1.close();
    h2.close();
    h3.close();
    // 셋 다 허가를 쥔 채로 받는다 — 하나를 받자마자 반납하면 그 반납이 다음 대기자를 깨워 연쇄가 끊겨도 통과해 버린다.
    AnalyticsQueryConcurrencyGate.Permit p1 = w1.get(20, TimeUnit.SECONDS);
    AnalyticsQueryConcurrencyGate.Permit p2 = w2.get(20, TimeUnit.SECONDS);
    AnalyticsQueryConcurrencyGate.Permit p3 = w3.get(20, TimeUnit.SECONDS);
    assertThat(gate.activeTotal()).isEqualTo(3);
    p1.close();
    p2.close();
    p3.close();
    assertThat(gate.activeTotal()).isZero();
    assertThat(gate.waitingCount()).isZero();
  }

  /** Tibero 테넌트는 허가를 얻지 않는다(게이트가 꽉 차 있어도 바로 통과, 카운터 그대로) — PG 는 상한을 따른다. */
  @Test
  void acquireUnlessTibero_skipsGateOnlyForTibero() {
    InteractiveQueryConcurrencyGate gate = new InteractiveQueryConcurrencyGate(1, 3, 0);
    AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(1L);
    PipelineDataSchema tibero = mock(PipelineDataSchema.class);
    when(tibero.isTibero()).thenReturn(true);
    PipelineDataSchema pg = mock(PipelineDataSchema.class);

    AnalyticsQueryConcurrencyGate.Permit noop = gate.acquireUnlessTibero(tibero, 2L);
    assertThat(gate.activeTotal()).isEqualTo(1);
    noop.close();
    assertThat(gate.activeTotal()).as("Tibero 허가 반납이 다른 실행의 몫을 빼앗지 않는다").isEqualTo(1);

    assertThatThrownBy(() -> gate.acquireUnlessTibero(pg, 2L)).satisfies(e -> assertRejected(e));
    held.close();
    gate.acquireUnlessTibero(pg, 2L).close();
    assertThat(gate.activeTotal()).isZero();
  }

  /** runGated — 일이 예외로 끝나도 허가를 반납하고, Tibero 는 게이트가 차 있어도 허가 없이 실행한다(PG 는 429). */
  @Test
  void runGated_releasesOnException_andSkipsGateForTibero() {
    InteractiveQueryConcurrencyGate gate = new InteractiveQueryConcurrencyGate(1, 3, 0);
    TransactionTemplate tx =
        TransactionTemplateConfig.readOnly(mock(PlatformTransactionManager.class));
    PipelineDataSchema tibero = mock(PipelineDataSchema.class);
    when(tibero.isTibero()).thenReturn(true);
    PipelineDataSchema pg = mock(PipelineDataSchema.class);

    assertThatThrownBy(
            () ->
                gate.runGated(
                    pg,
                    tx,
                    1L,
                    () -> {
                      throw new IllegalStateException("실행 실패");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(gate.activeTotal()).as("예외로 끝나도 반납").isZero();

    AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(9L);
    assertThat(gate.runGated(tibero, tx, 1L, () -> "tibero")).isEqualTo("tibero");
    assertThatThrownBy(() -> gate.runGated(pg, tx, 1L, () -> "pg"))
        .satisfies(e -> assertRejected(e));
    held.close();
    assertThat(gate.runGated(pg, tx, 1L, () -> "pg")).isEqualTo("pg");
    assertThat(gate.activeTotal()).isZero();
  }

  @Test
  void waitExceeded_rejectsWith429_andLeavesCountersUntouched() {
    InteractiveQueryConcurrencyGate gate = new InteractiveQueryConcurrencyGate(1, 3, 200);
    AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(1L);

    long start = System.nanoTime();
    Throwable e = org.assertj.core.api.Assertions.catchThrowable(() -> gate.acquire(2L));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertRejected(e);
    // 바로 거절하지 않고 wait-ms 만큼 기다렸다.
    assertThat(elapsedMs).isGreaterThanOrEqualTo(150);
    assertThat(gate.activeTotal()).isEqualTo(1);
    assertThat(gate.activeFor(2L)).isZero();
    assertThat(gate.trackedUsers()).containsExactly(1L);

    held.close();
    assertThat(gate.activeTotal()).isZero();
  }

  @Test
  void zeroWait_rejectsImmediatelyWhenFull() {
    InteractiveQueryConcurrencyGate gate = new InteractiveQueryConcurrencyGate(1, 3, 0);
    AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(1L);
    assertThatThrownBy(() -> gate.acquire(2L)).satisfies(e -> assertRejected(e));
    held.close();
  }

  @Test
  void doubleClose_releasesOnlyOnce() {
    InteractiveQueryConcurrencyGate gate = new InteractiveQueryConcurrencyGate(2, 2, 100);
    AnalyticsQueryConcurrencyGate.Permit a = gate.acquire(1L);
    AnalyticsQueryConcurrencyGate.Permit b = gate.acquire(2L);
    a.close();
    a.close();
    // 중복 close 가 b 의 몫을 빼앗지 않는다.
    assertThat(gate.activeTotal()).isEqualTo(1);
    assertThat(gate.activeFor(2L)).isEqualTo(1);
    b.close();
    assertThat(gate.activeTotal()).isZero();
  }

  @Test
  void interruptedWhileWaiting_restoresFlag_andDoesNotLeak() throws Exception {
    InteractiveQueryConcurrencyGate gate = new InteractiveQueryConcurrencyGate(1, 3, 10_000);
    AnalyticsQueryConcurrencyGate.Permit held = gate.acquire(1L);

    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicBoolean flagAfter = new AtomicBoolean();
    CountDownLatch done = new CountDownLatch(1);
    Thread t =
        new Thread(
            () -> {
              try {
                gate.acquire(2L);
              } catch (Throwable e) {
                thrown.set(e);
              } finally {
                flagAfter.set(Thread.currentThread().isInterrupted());
                done.countDown();
              }
            });
    t.start();
    // 줄에 선 뒤(대기 중)에 인터럽트해야 「기다리다 인터럽트」 경로를 본다.
    awaitWaiters(gate, 1);
    t.interrupt();
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

    assertRejected(thrown.get());
    assertThat(flagAfter.get()).as("인터럽트 플래그 복원").isTrue();
    assertThat(gate.activeTotal()).isEqualTo(1);
    assertThat(gate.activeFor(2L)).isZero();
    assertThat(gate.trackedUsers()).containsExactly(1L);

    held.close();
    assertThat(gate.activeTotal()).isZero();
    assertThat(gate.trackedUsers()).isEmpty();
  }

  @Test
  void nonPositiveLimits_disableEachLimit() {
    // 둘 다 끔 — 몇 개든 바로 통과.
    InteractiveQueryConcurrencyGate off = new InteractiveQueryConcurrencyGate(0, -1, 0);
    for (int i = 0; i < 20; i++) {
      off.acquire(1L);
    }
    assertThat(off.activeTotal()).isZero();

    // 전체만 끔 — 사용자 상한은 유효.
    InteractiveQueryConcurrencyGate userOnly = new InteractiveQueryConcurrencyGate(0, 1, 0);
    AnalyticsQueryConcurrencyGate.Permit a = userOnly.acquire(1L);
    assertThatThrownBy(() -> userOnly.acquire(1L)).satisfies(e -> assertRejected(e));
    AnalyticsQueryConcurrencyGate.Permit b = userOnly.acquire(2L);
    a.close();
    b.close();
    assertThat(userOnly.trackedUsers()).isEmpty();

    // 사용자만 끔 — 전체 상한은 유효.
    InteractiveQueryConcurrencyGate totalOnly = new InteractiveQueryConcurrencyGate(1, 0, 0);
    AnalyticsQueryConcurrencyGate.Permit c = totalOnly.acquire(1L);
    assertThatThrownBy(() -> totalOnly.acquire(2L)).satisfies(e -> assertRejected(e));
    c.close();
    assertThat(totalOnly.activeTotal()).isZero();
    assertThat(totalOnly.trackedUsers()).isEmpty();
  }
}
