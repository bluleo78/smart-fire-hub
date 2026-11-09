package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 슬롯 세션 끊기 장치(WD-624) — 부르는 쪽의 기다림 상한, 시작 전 작업으로의 합치기(슬롯 합집합), 슬롯별 미확인 표식의 지우기 규칙. 실제 끊기는 가짜로 갈아 끼워
 * 소유자 풀 막힘을 흉내 낸다.
 */
class PythonReadSessionTerminatorTest {

  private static final long TENANT = 7L;

  private final AtomicInteger kills = new AtomicInteger();
  private volatile CountDownLatch gate = new CountDownLatch(0);
  private volatile CountDownLatch entered = new CountDownLatch(1);
  private volatile RuntimeException failure;
  private final List<Set<Integer>> killedSlots = new CopyOnWriteArrayList<>();
  private static final Set<Integer> ALL = PythonReadSlots.ALL_SLOTS;

  private final PythonReadSessionTerminator terminator =
      new PythonReadSessionTerminator(
          (tenantId, slots) -> {
            entered.countDown();
            try {
              gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            kills.incrementAndGet();
            killedSlots.add(Set.copyOf(slots));
            if (failure != null) {
              throw failure;
            }
            return 2;
          });

  @AfterEach
  void tearDown() {
    gate.countDown();
    terminator.shutdown();
  }

  @Test
  void terminateWithin_returnsCount_andRecordsTerminationTime() {
    long before = System.currentTimeMillis();
    assertThat(terminator.terminateWithin(TENANT, ALL, Duration.ofSeconds(5))).isEqualTo(2);
    assertThat(terminator.terminatedSince(TENANT, before)).isTrue();
    assertThat(terminator.terminatedSince(TENANT + 1, 0L)).isFalse();
  }

  /**
   * 끊기가 막혀 있어도(소유자 풀 대기) 부르는 쪽은 상한만큼만 기다리고 예외로 돌아온다 — 커밋 콜백이 플랫폼 커넥션을 쥔 채 30초씩 멈추지 않게. 끊기 자체는 계속 돌아
   * 막힘이 풀리면 끝난다.
   */
  @Test
  void blockedKill_callerTimesOutQuickly_butKillKeepsRunning() throws Exception {
    gate = new CountDownLatch(1);
    long start = System.nanoTime();
    assertThatThrownBy(() -> terminator.terminateWithin(TENANT, ALL, Duration.ofMillis(200)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2_000L);
    assertThat(kills.get()).isZero();
    gate.countDown();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (kills.get() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(kills.get()).as("기다림이 끝나도 끊기는 끝까지 돈다").isEqualTo(1);
  }

  /**
   * 합치기: 실행 중인 끊기에는 합류하지 않고(시작 뒤 요청의 세션을 못 봤을 수 있다), 아직 시작하지 않은 끊기에는 합류해 슬롯을 더한다 — 실행 중 1 + 대기 3
   * 요청이어도 끊기는 2번만 돌고, 두 번째 끊기는 대기 요청 슬롯의 합집합을 끊는다.
   */
  @Test
  void requests_joinOnlyNotYetStartedKill_withSlotUnion() throws Exception {
    gate = new CountDownLatch(1);
    CompletableFuture<Integer> first =
        CompletableFuture.supplyAsync(
            () -> terminator.terminateWithin(TENANT, Set.of(1), Duration.ofSeconds(5)));
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); // 첫 끊기가 시작했다
    List<Set<Integer>> laterSlots = List.of(Set.of(2), Set.of(3, 4), Set.of(2, 5));
    CompletableFuture<?>[] later = new CompletableFuture<?>[laterSlots.size()];
    for (int i = 0; i < later.length; i++) {
      Set<Integer> slots = laterSlots.get(i);
      later[i] =
          CompletableFuture.supplyAsync(
              () -> terminator.terminateWithin(TENANT, slots, Duration.ofSeconds(5)));
    }
    Thread.sleep(200); // 나중 요청들이 큐의 같은 작업에 합류할 시간
    gate.countDown();
    first.get(5, TimeUnit.SECONDS);
    CompletableFuture.allOf(later).get(5, TimeUnit.SECONDS);
    assertThat(kills.get()).isEqualTo(2);
    assertThat(killedSlots).containsExactly(Set.of(1), Set.of(2, 3, 4, 5));
  }

  /** 빈 슬롯 요청은 끊기를 돌리지 않는다(넓어지는 변경의 대체 경로가 아무것도 끊지 않게). */
  @Test
  void emptySlots_doNothing() {
    assertThat(terminator.terminateWithin(TENANT, Set.of(), Duration.ofSeconds(1))).isZero();
    assertThat(kills.get()).isZero();
    assertThat(terminator.unconfirmedSlots(TENANT)).isEmpty();
  }

  /** 미확인 표식(슬롯별)은 표식 뒤에 시작한 끊기가 성공해야 지워진다. 실패한 끊기는 지우지 않고, 다른 슬롯 끊기는 그 슬롯 표식만 지운다. */
  @Test
  void unconfirmedMark_clearedOnlyByLaterSuccessfulKillOfThatSlot() {
    gate = new CountDownLatch(1);
    CompletableFuture<Integer> original = terminator.markAndRequest(TENANT, Set.of(1, 2));
    assertThat(original.isDone()).as("막힌 끊기를 기다리지 않는다").isFalse();
    assertThat(terminator.unconfirmedSlots(TENANT)).containsExactlyInAnyOrder(1, 2);
    failure = new IllegalStateException("still alive");
    gate.countDown(); // 늦게 끝난 원래 끊기 — 실패라 지우지 않는다
    assertThatThrownBy(
            () -> terminator.terminateWithin(TENANT, Set.of(1, 2), Duration.ofSeconds(5)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(terminator.unconfirmedSlots(TENANT))
        .as("실패한 끊기는 표식을 지우지 않는다")
        .containsExactlyInAnyOrder(1, 2);

    failure = null;
    terminator.terminateWithin(TENANT, Set.of(2), Duration.ofSeconds(5));
    assertThat(terminator.unconfirmedSlots(TENANT)).as("슬롯 2 만 지운다").containsExactly(1);
    terminator.terminateWithin(TENANT, Set.of(1), Duration.ofSeconds(5));
    assertThat(terminator.unconfirmedSlots(TENANT)).isEmpty();
  }

  /**
   * 리뷰 6 — 표식은 요청하기 <b>전에</b> 세운다. 이미 돌고 있던(표식 전에 시작한) 끊기가 끝나도 표식은 남고, 새 요청이 만든 작업(표식 뒤 시작)이 끝나야
   * 지워진다. 표식을 기다림 실패 뒤에 세우면, 그 사이 끝난 끊기가 표식을 못 지워 영원히 남거나(무해) 반대로 늦게 세운 표식을 옛 작업이 지우는 경합이 생긴다.
   */
  @Test
  void unconfirmedMark_setBeforeWaiting_notClearedByKillThatStartedEarlier() throws Exception {
    gate = new CountDownLatch(1);
    CompletableFuture<Integer> running =
        CompletableFuture.supplyAsync(
            () -> terminator.terminateWithin(TENANT, Set.of(3), Duration.ofSeconds(5)));
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); // 표식 전에 시작한 끊기가 돌고 있다
    CompletableFuture<Integer> confirming = terminator.markAndRequest(TENANT, Set.of(3));
    assertThat(terminator.unconfirmedSlots(TENANT)).as("요청하기 전에 표식").containsExactly(3);
    assertThat(confirming.isDone()).isFalse();
    gate.countDown();
    running.get(5, TimeUnit.SECONDS);
    assertThat(confirming.get(5, TimeUnit.SECONDS)).isEqualTo(2);
    assertThat(kills.get()).as("표식 뒤 요청은 실행 중 작업에 합류하지 않고 새로 돈다").isEqualTo(2);
    assertThat(terminator.unconfirmedSlots(TENANT)).isEmpty();
  }

  /**
   * WD-664 9 — 커밋 콜백용 "표식 + 요청" 은 끊기가 막혀 있어도(소유자 풀 바쁨) 곧바로 돌아온다. 표식은 돌아오기 전에 이미 서 있고(실행 준비가 먼저 끊는다
   * — fail-closed), 끊기는 끊기 전용 스레드에서 계속 돌아 성공하면 표식을 지운다. 변이: 요청 뒤 결과를 기다리면 막힌 끊기만큼 늦게 돌아온다.
   */
  @Test
  void markAndRequest_returnsWithoutWaiting_markerStaysUntilKillSucceeds() throws Exception {
    gate = new CountDownLatch(1);
    long start = System.nanoTime();
    CompletableFuture<Integer> result = terminator.markAndRequest(TENANT, Set.of(1, 2));
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(300L);
    assertThat(result.isDone()).isFalse();
    assertThat(terminator.unconfirmedSlots(TENANT))
        .as("돌아오기 전에 표식")
        .containsExactlyInAnyOrder(1, 2);
    gate.countDown();
    assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(2);
    assertThat(terminator.unconfirmedSlots(TENANT)).as("끊기 성공이 표식을 지운다").isEmpty();
  }

  /** WD-664 9 — "표식 + 요청" 의 끊기가 실패하면 표식이 남는다(실행 준비가 넓히기 전에 먼저 끊는다). 실패는 돌려준 결과로만 알린다(던지지 않는다). */
  @Test
  void markAndRequest_failedKill_keepsMarker() throws Exception {
    failure = new IllegalStateException("owner pool busy");
    CompletableFuture<Integer> result = terminator.markAndRequest(TENANT, Set.of(4));
    assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(IllegalStateException.class);
    assertThat(terminator.unconfirmedSlots(TENANT)).containsExactly(4);
  }

  /** WD-664 7 — 표식 없는 요청(확인된 좁히기 뒤 끊기)은 표식을 세우지 않고 곧바로 돌아온다. 빈 슬롯은 끊기를 돌리지 않는다. */
  @Test
  void request_withoutMarker_returnsImmediately() throws Exception {
    gate = new CountDownLatch(1);
    long start = System.nanoTime();
    CompletableFuture<Integer> result = terminator.requestTermination(TENANT, Set.of(5));
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(300L);
    assertThat(terminator.unconfirmedSlots(TENANT)).isEmpty();
    gate.countDown();
    assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(2);
    assertThat(killedSlots).containsExactly(Set.of(5));
    assertThat(terminator.requestTermination(TENANT, Set.of()).get(1, TimeUnit.SECONDS)).isZero();
    assertThat(terminator.markAndRequest(TENANT, Set.of()).get(1, TimeUnit.SECONDS)).isZero();
    assertThat(kills.get()).isEqualTo(1);
  }

  /** 종료된 뒤의 요청은 기다리지 않고 바로 실패한다(부르는 쪽이 fail-closed 처리). */
  @Test
  void afterShutdown_requestsFailFast() {
    terminator.shutdown();
    long start = System.nanoTime();
    assertThatThrownBy(() -> terminator.terminateWithin(TENANT, ALL, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1_000L);
  }
}
