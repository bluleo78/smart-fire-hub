package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.CodedApiException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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
    Thread.sleep(300);
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
    Thread.sleep(300);
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
    Thread.sleep(300);
    CompletableFuture<AnalyticsQueryConcurrencyGate.Permit> b = acquireAsync(gate, 3L);
    Thread.sleep(300);
    assertThat(a).isNotDone();
    assertThat(b).isNotDone();

    held.close();
    AnalyticsQueryConcurrencyGate.Permit gotA = a.get(5, TimeUnit.SECONDS);
    Thread.sleep(200);
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
    Thread.sleep(300);
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
