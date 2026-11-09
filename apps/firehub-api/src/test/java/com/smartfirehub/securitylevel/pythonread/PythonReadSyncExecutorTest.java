package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.smartfirehub.securitylevel.pythonread.PythonReadSyncExecutor.Outcome;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 커밋 뒤 PYTHON 읽기 권한 동기화 전용 실행기(WD-594) 단위 검증 — 다른 스레드에서 돌고, 포화·종료 때 예외 없이 버리며(커밋 콜백을 막지 않는다),
 * awaitIdle 이 넘긴 작업(버린 것 포함)의 끝을 정확히 기다리는지 본다. 잠금이 바쁜 작업은 풀릴 때까지 재시도하고 뒤 작업을 막지 않는지(WD-644)도 본다. DB
 * 는 쓰지 않는다.
 */
class PythonReadSyncExecutorTest {

  /** 작업은 호출 스레드가 아니라 전용 스레드에서 돌고, awaitIdle 은 그 작업이 끝난 뒤에 true 를 돌려준다. */
  @Test
  void submit_runsOnDedicatedThread_andAwaitIdleWaitsForIt() throws Exception {
    PythonReadSyncExecutor executor = new PythonReadSyncExecutor();
    try {
      AtomicReference<String> threadName = new AtomicReference<>();
      CountDownLatch release = new CountDownLatch(1);
      assertThat(
              submit(
                  executor,
                  () -> {
                    threadName.set(Thread.currentThread().getName());
                    awaitQuietly(release);
                  }))
          .isTrue();
      assertThat(executor.awaitIdle(Duration.ofMillis(200))).as("작업이 끝나기 전에는 idle 아님").isFalse();
      release.countDown();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(threadName.get()).isEqualTo("python-read-grant-sync");
    } finally {
      executor.shutdown();
    }
  }

  /** 진행 중 작업이 상한에 차면 예외 없이 버리고(false) 호출자를 막지 않는다. 버린 작업은 pending 에 남지 않아 awaitIdle 이 끝난다. */
  @Test
  void submit_whenSaturated_dropsWithoutThrowing() throws Exception {
    // 상한은 진행 중 작업 수(실행 중 + 대기) — 2 면 실행 중 하나 + 대기 하나까지 받는다.
    PythonReadSyncExecutor executor = new PythonReadSyncExecutor(2);
    try {
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch started = new CountDownLatch(1);
      AtomicInteger ran = new AtomicInteger();
      submit(
          executor,
          () -> {
            started.countDown();
            awaitQuietly(release);
            ran.incrementAndGet();
          });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue(); // 스레드가 첫 작업을 쥐었다
      assertThat(submit(executor, ran::incrementAndGet)).as("상한 안 — 대기 1칸").isTrue();
      assertThat(submit(executor, ran::incrementAndGet)).as("포화 → 버림").isFalse();
      release.countDown();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(ran.get()).as("받아들인 두 작업만 돌았다").isEqualTo(2);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * 회수 필수(critical) 작업은 포화여도 버리지 않는다(WD-664 1a) — 회수 미확인 뒷정리·등급 정의 변경 전체 동기화는 다른 방어선이 없는 마지막 회수라
   * 상한에 막혀 버려지면 과권한이 남는다. 일반 작업은 여전히 버린다.
   */
  @Test
  void submit_critical_bypassesCapacity_whileOrdinaryIsStillDropped() throws Exception {
    PythonReadSyncExecutor executor = new PythonReadSyncExecutor(1);
    try {
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch started = new CountDownLatch(1);
      submit(
          executor,
          () -> {
            started.countDown();
            awaitQuietly(release);
          });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue(); // 상한 1 이 찼다
      AtomicInteger criticalRan = new AtomicInteger();
      AtomicInteger ordinaryRan = new AtomicInteger();
      boolean critical =
          executor.submitRetryable(
              "critical-" + KEYS.incrementAndGet(),
              "t",
              (items, lockWait) -> {
                criticalRan.incrementAndGet();
                return Outcome.DONE;
              },
              true);
      assertThat(critical).as("회수 필수 작업은 포화여도 받아들인다").isTrue();
      assertThat(submit(executor, ordinaryRan::incrementAndGet)).as("일반 작업은 포화 → 버림").isFalse();
      release.countDown();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(criticalRan.get()).as("받아들인 회수 필수 작업이 실제로 돌았다").isEqualTo(1);
      assertThat(ordinaryRan.get()).isZero();
    } finally {
      executor.shutdown();
    }
  }

  /** 작업이 예외를 던져도 워커가 죽지 않고 다음 작업을 돌린다. */
  @Test
  void submit_failingWork_doesNotKillWorker() throws Exception {
    PythonReadSyncExecutor executor = new PythonReadSyncExecutor();
    try {
      AtomicInteger ran = new AtomicInteger();
      submit(
          executor,
          () -> {
            throw new IllegalStateException("동기화 실패 흉내");
          });
      submit(executor, ran::incrementAndGet);
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(ran.get()).isEqualTo(1);
    } finally {
      executor.shutdown();
    }
  }

  /** 종료 뒤 제출은 예외 없이 버린다(종료 중 커밋 콜백이 실패하지 않게). */
  @Test
  void submit_afterShutdown_dropsWithoutThrowing() throws Exception {
    PythonReadSyncExecutor executor = new PythonReadSyncExecutor();
    executor.shutdown();
    AtomicInteger ran = new AtomicInteger();
    assertThatCode(() -> assertThat(submit(executor, ran::incrementAndGet)).isFalse())
        .doesNotThrowAnyException();
    assertThat(executor.awaitIdle(Duration.ofSeconds(1))).isTrue();
    assertThat(ran.get()).isZero();
  }

  /**
   * 라이프사이클 stop 은 큐에 남은 작업을 모두 비운 뒤 돌아오고(DataSource 소멸 전에 비우기 위함), 두 번 불러도(stop 뒤 @PreDestroy 안전망)
   * 예외 없이 아무것도 하지 않는다. 종료 뒤 제출은 예외 없이 버린다.
   */
  @Test
  void stop_drainsQueuedWork_andIsIdempotent() throws Exception {
    PythonReadSyncExecutor executor = new PythonReadSyncExecutor();
    executor.start();
    assertThat(executor.isRunning()).isTrue();
    assertThat(executor.isAutoStartup()).isTrue();
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger ran = new AtomicInteger();
    submit(
        executor,
        () -> {
          awaitQuietly(release);
          ran.incrementAndGet();
        });
    submit(executor, ran::incrementAndGet);
    submit(executor, ran::incrementAndGet);
    // 첫 작업을 잠시 뒤 풀어 stop 이 실제로 기다리는지 본다.
    Thread releaser =
        new Thread(
            () -> {
              try {
                Thread.sleep(200);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              release.countDown();
            });
    releaser.start();

    executor.stop();

    assertThat(ran.get()).as("stop 이 돌아왔을 때 남은 작업이 모두 끝났다").isEqualTo(3);
    assertThat(executor.isRunning()).isFalse();
    assertThatCode(executor::shutdown).as("@PreDestroy 안전망 — 멱등").doesNotThrowAnyException();
    assertThatCode(executor::stop).doesNotThrowAnyException();
    assertThatCode(() -> assertThat(submit(executor, ran::incrementAndGet)).isFalse())
        .doesNotThrowAnyException();
    assertThat(executor.awaitIdle(Duration.ofSeconds(1))).isTrue();
    assertThat(ran.get()).isEqualTo(3);
    releaser.join();
  }

  // ------------------------------------------------- 잠금이 바쁜 작업의 재시도·격상·합치기(WD-644)

  /** 테스트용 짧은 재시도 간격 상한 — 고정 대기·during 구간을 이 값의 몇 배로 잡는다. */
  private static final Duration CAP = Duration.ofMillis(20);

  /** 테스트 작업마다 다른 그룹 — 합치기를 시험하지 않는 테스트가 서로 합쳐지지 않게. */
  private static final AtomicLong KEYS = new AtomicLong();

  /** 짧은 재시도 규칙의 실행기 — 운영(0.5초에서 두 배씩, 2초 상한, 시도형 3번, 일시 실패 5번)과 모양은 같고 빠르다. */
  private static PythonReadSyncExecutor executor(
      Duration initial, Duration cap, int tryAttempts, int maxFailures) {
    return new PythonReadSyncExecutor(
        100, new PythonReadSyncExecutor.RetryPolicy(initial, cap, tryAttempts, maxFailures));
  }

  /** 시도형 3번·일시 실패 5번의 짧은 실행기. */
  private static PythonReadSyncExecutor executor(Duration initial, Duration cap) {
    return executor(initial, cap, 3, 5);
  }

  /** 재시도 없는 작업을 저마다 다른 그룹으로 넘긴다 — 항상 "끝났다(DONE)" 로 보고하는 시도. */
  private static boolean submit(PythonReadSyncExecutor executor, Runnable work) {
    return executor.submitRetryable(
        "test-" + KEYS.incrementAndGet(),
        "t",
        (items, lockWait) -> {
          work.run();
          return Outcome.DONE;
        });
  }

  /** 시도형(잠금 대기 0)인가. */
  private static boolean isTry(Duration lockWait) {
    return lockWait.isZero();
  }

  /** 간격은 initial 에서 두 배씩 늘어 cap 에서 멈추고, 큰 n 에서도 넘치지 않는다. 운영 묶인 대기는 5초다. */
  @Test
  void retryPolicy_doublesUpToCap() {
    var policy = PythonReadSyncExecutor.DEFAULT_RETRY_POLICY;
    assertThat(policy.delayFor(0)).isEqualTo(Duration.ofMillis(500));
    assertThat(policy.delayFor(1)).isEqualTo(Duration.ofSeconds(1));
    assertThat(policy.delayFor(2)).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.delayFor(3)).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.delayFor(1_000)).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.lockWait()).isEqualTo(Duration.ofSeconds(5));
  }

  /**
   * 잠금이 바빠(BUSY) 끝난 작업은 다시 시도되고 잠금이 풀린 시도에서 끝난다. 처음 3번은 시도형(잠금 대기 0), 그 뒤로는 묶인 대기(운영 5초)로 격상한다 —
   * 시도형만으로는 기다리는 쪽이 늘 줄에 있는 테넌트에서 굶기 때문이다. 시간·횟수 상한으로 버리지 않는다(회수가 빠지면 과권한). 격상을 지우면 모드 단언이, 상한을
   * 되살리면 버림 단언이 실패한다(변이).
   */
  @Test
  void submitRetryable_busy_escalatesToWaitingAndNeverGivesUp() throws Exception {
    PythonReadSyncExecutor executor = executor(Duration.ofMillis(2), Duration.ofMillis(5));
    try {
      List<Duration> modes = new CopyOnWriteArrayList<>();
      AtomicBoolean busy = new AtomicBoolean(true);
      executor.submitRetryable(
          "busy-then-free",
          "t",
          (items, lockWait) -> {
            modes.add(lockWait);
            return busy.get() ? Outcome.BUSY : Outcome.DONE;
          });
      // 간격 상한에 닿은 뒤에도 계속 재시도하며, 그동안 줄곧 pending 이고 버리지 않는다.
      await().atMost(Duration.ofSeconds(5)).until(() -> modes.size() >= 30);
      assertThat(executor.awaitIdle(Duration.ZERO)).as("바쁜 동안은 idle 아님").isFalse();
      assertThat(executor.droppedCount()).as("바쁘다고 버리지 않는다").isZero();
      List<Duration> seen = List.copyOf(modes); // 계속 자라는 목록의 스냅숏
      assertThat(seen.subList(0, 3)).as("처음 3번은 시도형").allMatch(PythonReadSyncExecutorTest::isTry);
      assertThat(seen.subList(3, 30))
          .as("그 뒤로는 묶인 대기(5초)")
          .containsOnly(PythonReadSyncExecutor.DEFAULT_LOCK_WAIT);
      busy.set(false);
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      int finalAttempts = modes.size();
      await()
          .during(CAP.multipliedBy(5))
          .atMost(Duration.ofSeconds(2))
          .until(() -> modes.size() == finalAttempts);
    } finally {
      executor.shutdown();
    }
  }

  /** 일시 실패(FAILED)는 같은 간격으로 다시 하고, 회복하면 끝난다(버림 없음). */
  @Test
  void submitRetryable_transientFailure_isRetriedThenSucceeds() throws Exception {
    PythonReadSyncExecutor executor = executor(Duration.ofMillis(2), CAP, 3, 5);
    try {
      AtomicInteger attempts = new AtomicInteger();
      executor.submitRetryable(
          "flaky",
          "t",
          (items, lockWait) -> attempts.incrementAndGet() <= 2 ? Outcome.FAILED : Outcome.DONE);
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(attempts.get()).isEqualTo(3);
      assertThat(executor.droppedCount()).isZero();
    } finally {
      executor.shutdown();
    }
  }

  /** 일시 실패가 상한(maxFailures)을 넘게 이어지면 버리고(ERROR) 세며, pending 이 새지 않는다. */
  @Test
  void submitRetryable_transientFailure_isDroppedAfterMaxFailures() throws Exception {
    PythonReadSyncExecutor executor = executor(Duration.ofMillis(2), CAP, 3, 2);
    try {
      AtomicInteger attempts = new AtomicInteger();
      executor.submitRetryable(
          "broken",
          "t",
          (items, lockWait) -> {
            attempts.incrementAndGet();
            return Outcome.FAILED;
          });
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(attempts.get()).as("첫 시도 + 재시도 2번").isEqualTo(3);
      assertThat(executor.droppedCount()).isEqualTo(1);
    } finally {
      executor.shutdown();
    }
  }

  /** WD-644 리뷰: 영구 실패(PERMANENT_FAILURE)는 재시도하지 않고 곧바로 버려 센다 — 일시 실패처럼 헛돌지 않는다. */
  @Test
  void submitRetryable_permanentFailure_isDroppedImmediately() throws Exception {
    PythonReadSyncExecutor executor = executor(Duration.ofMillis(2), CAP, 3, 5);
    try {
      AtomicInteger attempts = new AtomicInteger();
      executor.submitRetryable(
          "permanent",
          "t",
          (items, lockWait) -> {
            attempts.incrementAndGet();
            return Outcome.PERMANENT_FAILURE;
          });
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(attempts.get()).as("재시도 없음").isEqualTo(1);
      assertThat(executor.droppedCount()).isEqualTo(1);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * 회귀(WD-644): 한 테넌트 작업(A)이 잠금이 바빠 재시도를 기다리는 동안, 그 뒤에 넣은 다른 작업(B)은 A 를 기다리지 않고 곧바로 끝난다. A 는 그 뒤
   * 잠금이 풀리면 재시도로 끝난다. 시도가 잠금을 기다리는(재시도 대신 스레드를 붙잡는) 모양으로 되돌리면 B 단언이 실패한다.
   */
  @Test
  void submitRetryable_retryingJob_doesNotBlockJobBehindIt() throws Exception {
    // 간격을 길게 — A 가 재시도를 기다리는 동안 B 가 끝났음을 분명히 가른다.
    PythonReadSyncExecutor executor = executor(Duration.ofMillis(500), Duration.ofMillis(500));
    try {
      AtomicBoolean aLockBusy = new AtomicBoolean(true);
      AtomicBoolean aDone = new AtomicBoolean();
      CountDownLatch bDone = new CountDownLatch(1);
      executor.submitRetryable(
          "A",
          "t",
          (items, lockWait) -> {
            if (aLockBusy.get()) {
              return Outcome.BUSY;
            }
            aDone.set(true);
            return Outcome.DONE;
          });
      executor.submitRetryable(
          "B",
          "t",
          (items, lockWait) -> {
            bDone.countDown();
            return Outcome.DONE;
          });
      assertThat(bDone.await(400, TimeUnit.MILLISECONDS)).as("B 는 A 의 재시도를 기다리지 않는다").isTrue();
      assertThat(aDone.get()).as("A 는 아직 잠금이 바쁘다").isFalse();
      aLockBusy.set(false);
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(aDone.get()).as("A 는 잠금이 풀린 뒤 재시도로 끝났다").isTrue();
    } finally {
      executor.shutdown();
    }
  }

  /**
   * 회귀(WD-644 리뷰, head-of-line): 잠금이 바쁜 테넌트(A)에 테이블 20개가 밀려 있어도, 격상 뒤 A 가 단일 스레드를 붙잡는 것은 회차마다 묶인 대기
   * <b>한 번</b>이다 — 20개 테이블이 잠금 한 번(시도 한 번)에 함께 실린다. 그래서 그 뒤에 넣은 다른 테넌트(B)는 대기 한 번 남짓 뒤에 끝난다. 테이블마다
   * 작업을 두던 모양이면 A 의 대기 20번(4초) 뒤에야 B 가 돌아 단언이 실패한다(변이).
   */
  @Test
  void submitRetryable_busyTenantWithManyTables_holdsThreadForOneWaitPerCycle() throws Exception {
    Duration lockWait = Duration.ofMillis(200);
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(
            100, new PythonReadSyncExecutor.RetryPolicy(Duration.ofMillis(5), CAP, 0, 5, lockWait));
    try {
      List<List<String>> aBatches = new CopyOnWriteArrayList<>();
      PythonReadSyncExecutor.Attempt aAttempt =
          (items, wait) -> {
            aBatches.add(items);
            sleepQuietly(wait); // 묶인 대기가 시간을 다 쓰고 실패하는 흉내
            return Outcome.BUSY;
          };
      for (int i = 0; i < 20; i++) {
        executor.submitRetryable("A", "a" + i, aAttempt);
      }
      // 첫 시도는 일부만 실었을 수 있다 — 20개가 한 시도에 모두 실릴 때까지 기다린다.
      await().atMost(Duration.ofSeconds(5)).until(() -> lastSize(aBatches) == 20);
      CountDownLatch bDone = new CountDownLatch(1);
      long start = System.nanoTime();
      executor.submitRetryable(
          "B",
          "b",
          (items, wait) -> {
            bDone.countDown();
            return Outcome.DONE;
          });
      assertThat(bDone.await(5, TimeUnit.SECONDS)).isTrue();
      Duration bLatency = Duration.ofNanos(System.nanoTime() - start);
      assertThat(bLatency)
          .as("B 는 A 의 대기 한 번 남짓만 기다린다(테이블 수와 무관)")
          .isLessThan(lockWait.multipliedBy(4));
      List<List<String>> seen = List.copyOf(aBatches);
      assertThat(seen.get(seen.size() - 1)).as("A 의 테이블은 시도 한 번에 함께 실린다").hasSize(20);
      assertThat(executor.droppedCount()).isZero();
    } finally {
      executor.shutdown();
    }
  }

  /**
   * 같은 그룹이 이미 대기 중이면 합친다 — 진행 중 작업 수가 늘지 않아 상한(2)에 걸리지 않고, 시도도 한 번만 돌며 그 시도에 모든 테이블(중복 없이)이 실린다. 한
   * 테넌트의 재시도가 상한을 채워 다른 테넌트 동기화를 밀어내지 않게 하는 장치다. 합치기를 지우면 세 번째 제출이 포화로 버려진다(변이).
   */
  @Test
  void submitRetryable_sameGroupWhileQueued_isCoalesced() throws Exception {
    PythonReadSyncExecutor executor = new PythonReadSyncExecutor(2);
    try {
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch started = new CountDownLatch(1);
      submit(
          executor,
          () -> {
            started.countDown();
            awaitQuietly(release);
          });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      List<List<String>> runs = new CopyOnWriteArrayList<>();
      PythonReadSyncExecutor.Attempt sync =
          (items, lockWait) -> {
            runs.add(items);
            return Outcome.DONE;
          };
      assertThat(executor.submitRetryable("t1", "x", sync)).isTrue();
      assertThat(executor.submitRetryable("t1", "x", sync))
          .as("대기 중인 같은 그룹 — 합친다(상한에 안 걸림)")
          .isTrue();
      assertThat(executor.submitRetryable("t1", "y", sync)).isTrue();
      release.countDown();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(runs).as("합쳐진 작업은 한 번만 돌고 두 테이블을 함께 싣는다").containsExactly(List.of("x", "y"));
      assertThat(executor.droppedCount()).isZero();
    } finally {
      executor.shutdown();
    }
  }

  /**
   * 같은 테이블이 시도 중일 때 들어온 커밋은 놓치지 않는다 — 시도가 이미 상태를 읽었을 수 있으므로 끝난 뒤 정확히 한 번 더 돈다(여러 번 와도 한 번). 뒤따르는
   * 실행을 지우면 횟수가 1 이 되어 실패한다(변이).
   */
  @Test
  void submitRetryable_sameItemWhileRunning_runsExactlyOneFollowUp() throws Exception {
    PythonReadSyncExecutor executor = new PythonReadSyncExecutor();
    try {
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch started = new CountDownLatch(1);
      AtomicInteger runs = new AtomicInteger();
      PythonReadSyncExecutor.Attempt sync =
          (items, lockWait) -> {
            if (runs.incrementAndGet() == 1) {
              started.countDown();
              awaitQuietly(release);
            }
            return Outcome.DONE;
          };
      executor.submitRetryable("t1", "y", sync);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      executor.submitRetryable("t1", "y", sync);
      executor.submitRetryable("t1", "y", sync);
      executor.submitRetryable("t1", "y", sync);
      release.countDown();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(runs.get()).as("처음 실행 + 뒤따르는 실행 한 번").isEqualTo(2);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * WD-644 리뷰: 시도 중 들어온 뒤따르는 항목은 그 시도가 BUSY 로 끝나도 지워지지 않는다 — 다음 시도에 원래 항목과 함께 실린다. 예전에는 BUSY·FAILED
   * 가 뒤따르는 표시를 무조건 지웠다.
   */
  @Test
  void submitRetryable_followUpDuringBusyAttempt_isKept() throws Exception {
    PythonReadSyncExecutor executor = executor(Duration.ofMillis(2), CAP);
    try {
      List<List<String>> runs = new CopyOnWriteArrayList<>();
      AtomicReference<PythonReadSyncExecutor.Attempt> self = new AtomicReference<>();
      self.set(
          (items, lockWait) -> {
            runs.add(items);
            if (runs.size() == 1) {
              executor.submitRetryable("t1", "b", self.get()); // 시도 중 들어온 커밋
              return Outcome.BUSY;
            }
            return Outcome.DONE;
          });
      executor.submitRetryable("t1", "a", self.get());
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(runs).hasSize(2);
      assertThat(runs.get(1))
          .as("BUSY 뒤 다음 시도에 원래 항목과 뒤따르는 항목이 함께")
          .containsExactlyInAnyOrder("a", "b");
      assertThat(executor.droppedCount()).isZero();
    } finally {
      executor.shutdown();
    }
  }

  /**
   * WD-644 리뷰: 일시 실패를 다 써서 항목을 버리는 그 시도 중에 들어온 다른 테이블(뒤따르는 항목)은 함께 버려지지 않고 새 회차로 돈다. 버린 항목만 센다.
   * 예전에는 retire 가 작업째 버려 뒤따르는 실행이 조용히 사라졌다.
   */
  @Test
  void submitRetryable_followUpDuringExhaustingFailure_stillRuns() throws Exception {
    PythonReadSyncExecutor executor = executor(Duration.ofMillis(2), CAP, 3, 1);
    try {
      List<List<String>> runs = new CopyOnWriteArrayList<>();
      AtomicReference<PythonReadSyncExecutor.Attempt> self = new AtomicReference<>();
      self.set(
          (items, lockWait) -> {
            runs.add(items);
            if (runs.size() == 2) {
              executor.submitRetryable("t1", "y", self.get()); // 마지막 실패 시도 중 들어온 커밋
            }
            return runs.size() <= 2 ? Outcome.FAILED : Outcome.DONE;
          });
      executor.submitRetryable("t1", "x", self.get());
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(runs).containsExactly(List.of("x"), List.of("x"), List.of("y"));
      assertThat(executor.droppedCount()).as("버린 것은 실패를 다 쓴 x 하나").isEqualTo(1);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * WD-644 리뷰: 한 번 격상한 작업은 뒤따르는 실행에서도 묶인 대기를 유지한다 — 시도형 단계로 돌아가면 잠금이 다투어지는 테넌트에서 다시 굶는다. 예전에는 뒤따르는
   * 실행이 시도 수를 0 으로 되돌렸다.
   */
  @Test
  void submitRetryable_followUpAfterEscalation_keepsWaitingLock() throws Exception {
    PythonReadSyncExecutor executor = executor(Duration.ofMillis(2), CAP, 2, 5);
    try {
      List<Duration> modes = new CopyOnWriteArrayList<>();
      AtomicReference<PythonReadSyncExecutor.Attempt> self = new AtomicReference<>();
      self.set(
          (items, lockWait) -> {
            modes.add(lockWait);
            if (modes.size() <= 2) {
              return Outcome.BUSY; // 시도형 두 번 실패 → 격상
            }
            if (modes.size() == 3) {
              executor.submitRetryable("t1", "f", self.get()); // 격상된 시도 중 들어온 커밋
            }
            return Outcome.DONE;
          });
      executor.submitRetryable("t1", "x", self.get());
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(modes).hasSize(4);
      assertThat(modes.subList(0, 2)).allMatch(PythonReadSyncExecutorTest::isTry);
      assertThat(modes.get(3))
          .as("뒤따르는 실행도 묶인 대기")
          .isEqualTo(PythonReadSyncExecutor.DEFAULT_LOCK_WAIT);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * 종료는 재시도를 기다리던 작업을 버리지 않는다 — 예약에서 꺼내 곧바로 묶인 대기로 한 번 더 돌리고, 끝나면 버림 없이 pending 에서 빠진다. 재시도
   * 간격(30초)을 기다리지도 않는다.
   */
  @Test
  void shutdown_runsWaitingRetriesInWaitModeInsteadOfDropping() throws Exception {
    PythonReadSyncExecutor executor = executor(Duration.ofSeconds(30), Duration.ofSeconds(30));
    List<Duration> modes = new CopyOnWriteArrayList<>();
    executor.submitRetryable(
        "busy-until-drain",
        "t",
        (items, lockWait) -> {
          modes.add(lockWait);
          return isTry(lockWait) ? Outcome.BUSY : Outcome.DONE;
        });
    // 첫 시도(시도형)가 바빠 30초 재시도를 예약할 때까지 기다린다.
    await().atMost(Duration.ofSeconds(5)).until(() -> modes.size() == 1);
    assertThat(executor.awaitIdle(Duration.ofMillis(50))).as("재시도 대기 중").isFalse();
    long start = System.nanoTime();
    executor.shutdown();
    assertThat(Duration.ofNanos(System.nanoTime() - start))
        .as("종료가 재시도 간격(30초)을 기다리지 않는다")
        .isLessThan(Duration.ofSeconds(5));
    assertThat(executor.awaitIdle(Duration.ofSeconds(1))).isTrue();
    assertThat(modes).hasSize(2);
    assertThat(isTry(modes.get(0))).isTrue();
    assertThat(isTry(modes.get(1))).as("종료 비우기에서 묶인 대기로 한 번 더 돌았다").isFalse();
    assertThat(executor.droppedCount()).as("비우기에서 끝났으니 버림 없음").isZero();
  }

  /**
   * 회귀(WD-644 리뷰): 비우기가 시작되면 재시도 대기분뿐 아니라 <b>실행 대기(지연 0)</b> 작업과 <b>지금 실행 중</b>인 작업의 다음 시도도 묶인 대기로
   * 잡고, 비우기 중 BUSY 는 거절(RejectedExecution)로 버려지지 않고 다시 줄에 선다. 예전에는 재시도 대기분만 대기 모드로 바꿔, 나머지가 시도형으로
   * 바쁘면 재예약이 거절되어 버려졌다.
   */
  @Test
  void shutdown_queuedAndRunningJobsUseWaitingLock_andBusyIsRequeuedNotDropped() throws Exception {
    PythonReadSyncExecutor executor = executor(Duration.ofSeconds(30), Duration.ofSeconds(30));
    CountDownLatch aStarted = new CountDownLatch(1);
    CountDownLatch aRelease = new CountDownLatch(1);
    List<Duration> aModes = new CopyOnWriteArrayList<>();
    List<Duration> bModes = new CopyOnWriteArrayList<>();
    executor.submitRetryable(
        "A",
        "t",
        (items, lockWait) -> {
          aModes.add(lockWait);
          if (aModes.size() == 1) {
            aStarted.countDown();
            awaitQuietly(aRelease);
          }
          return isTry(lockWait) ? Outcome.BUSY : Outcome.DONE;
        });
    assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();
    executor.submitRetryable(
        "B",
        "t",
        (items, lockWait) -> {
          bModes.add(lockWait);
          return isTry(lockWait) ? Outcome.BUSY : Outcome.DONE;
        });
    Thread stopper = new Thread(executor::stop);
    stopper.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isRunning());
    aRelease.countDown(); // A 의 첫 시도(시도형)는 비우기 중 BUSY 로 끝난다
    stopper.join(10_000);
    assertThat(stopper.isAlive()).isFalse();
    assertThat(executor.awaitIdle(Duration.ofSeconds(1))).isTrue();
    assertThat(executor.droppedCount()).as("비우기 중 BUSY 도 버리지 않고 다시 돌려 끝냈다").isZero();
    assertThat(aModes).hasSize(2);
    assertThat(isTry(aModes.get(1))).as("실행 중이던 A 의 다음 시도는 묶인 대기").isFalse();
    assertThat(bModes).hasSize(1);
    assertThat(isTry(bModes.get(0))).as("실행 대기(지연 0) B 도 비우기에서는 묶인 대기").isFalse();
  }

  /** 종료 비우기에서도 여전히 바쁜 작업만 비우기 제한 시간 뒤 버린다(ERROR) — 그때까지는 거절되지 않고 다시 시도하며, pending 은 새지 않는다. */
  @Test
  void shutdown_dropsOnlyRetriesStillBusyAfterDrain() throws Exception {
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(
            100,
            new PythonReadSyncExecutor.RetryPolicy(
                Duration.ofSeconds(30), Duration.ofSeconds(30), 3, 5),
            Duration.ofMillis(300));
    AtomicInteger attempts = new AtomicInteger();
    executor.submitRetryable(
        "always-busy",
        "t",
        (items, lockWait) -> {
          attempts.incrementAndGet();
          sleepQuietly(lockWait); // 묶인 대기가 시간을 다 쓰는 흉내
          return Outcome.BUSY;
        });
    await().atMost(Duration.ofSeconds(5)).until(() -> attempts.get() == 1);
    executor.shutdown();
    assertThat(executor.awaitIdle(Duration.ofSeconds(1))).as("버린 작업은 pending 에서 빠진다").isTrue();
    assertThat(attempts.get()).as("비우기에서 다시 시도했다").isGreaterThanOrEqualTo(2);
    assertThat(executor.droppedCount()).isEqualTo(1);
  }

  /**
   * 회귀(WD-644 리뷰): 비우기 중 묶인 대기 한 번은 비우기 제한 시간의 1/10 이하로 줄어(운영 5초 대기 그대로가 아니다) 바쁜 테넌트 몇 개가 비우기 시간을 다
   * 먹지 않는다. 바쁜 테넌트가 여럿이어도 종료는 제한 시간 남짓에 끝나고, 남은 것은 세어 버린다.
   */
  @Test
  void shutdown_drainUsesShortLockWait_andFinishesWithinDrainTimeout() throws Exception {
    Duration drain = Duration.ofMillis(500);
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(
            100,
            new PythonReadSyncExecutor.RetryPolicy(
                Duration.ofSeconds(30), Duration.ofSeconds(30), 3, 5),
            drain);
    List<Duration> drainWaits = new CopyOnWriteArrayList<>();
    AtomicInteger firstAttempts = new AtomicInteger();
    for (int i = 0; i < 3; i++) {
      executor.submitRetryable(
          "busy-" + i,
          "t",
          (items, lockWait) -> {
            if (isTry(lockWait)) {
              firstAttempts.incrementAndGet();
            } else {
              drainWaits.add(lockWait);
              sleepQuietly(lockWait);
            }
            return Outcome.BUSY;
          });
    }
    await().atMost(Duration.ofSeconds(5)).until(() -> firstAttempts.get() == 3);
    long start = System.nanoTime();
    executor.shutdown();
    Duration took = Duration.ofNanos(System.nanoTime() - start);
    assertThat(took).as("종료는 비우기 제한 시간 남짓에 끝난다").isLessThan(drain.plusSeconds(1));
    assertThat(drainWaits).isNotEmpty();
    assertThat(drainWaits)
        .as("비우기 중 대기 한 번은 제한 시간의 1/10 이하")
        .allMatch(w -> w.compareTo(drain.dividedBy(10)) <= 0);
    assertThat(executor.awaitIdle(Duration.ofSeconds(1))).isTrue();
    assertThat(executor.droppedCount()).as("끝내 바빴던 세 테넌트를 센다").isEqualTo(3);
  }

  /**
   * 회귀(WD-644 리뷰): 비우기가 시작된 뒤에는 이미 있는 작업에 합치는 제출도 받아들였다고(true) 답하지 않는다 — 그 작업이 제한 시간 안에 끝난다는 보장이
   * 없다. 버림으로 세고 false. 새 그룹 제출도 마찬가지.
   */
  @Test
  void submitRetryable_duringDrain_mergeIsRejectedHonestly() throws Exception {
    PythonReadSyncExecutor executor = new PythonReadSyncExecutor();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    PythonReadSyncExecutor.Attempt blocking =
        (items, lockWait) -> {
          started.countDown();
          awaitQuietly(release);
          return Outcome.DONE;
        };
    executor.submitRetryable("t1", "x", blocking);
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    Thread stopper = new Thread(executor::stop);
    stopper.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isRunning());
    assertThat(executor.submitRetryable("t1", "y", blocking)).as("비우기 중 합치기는 false").isFalse();
    assertThat(executor.submitRetryable("t2", "z", blocking)).as("비우기 중 새 그룹도 false").isFalse();
    release.countDown();
    stopper.join(10_000);
    assertThat(executor.awaitIdle(Duration.ofSeconds(1))).isTrue();
    assertThat(executor.droppedCount()).as("거절한 두 제출만 센다").isEqualTo(2);
  }

  /** 목록의 마지막 원소 크기(없으면 0). */
  private static int lastSize(List<List<String>> batches) {
    List<List<String>> copy = List.copyOf(batches);
    return copy.isEmpty() ? 0 : copy.get(copy.size() - 1).size();
  }

  /** 주어진 시간만큼 잔다(인터럽트는 플래그만 되살린다). */
  private static void sleepQuietly(Duration d) {
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ------------------------------------------------- 재시작·비우기(WD-629, WD-644 실행기로 이식)

  /**
   * 회귀(WD-629): 라이프사이클 stop 뒤 start(컨텍스트 stop/start)하면 스케줄러를 새로 만들어 다시 받아들인다. 고치기 전에는 start 가 빈
   * 메서드라 이후 모든 제출이 RejectedExecutionException → 버림이 되어 커밋 뒤 동기화가 영구히 사라졌다.
   */
  @Test
  void stopThenStart_acceptsAndRunsNewWork() throws Exception {
    PythonReadSyncExecutor executor = new PythonReadSyncExecutor();
    try {
      executor.start();
      executor.stop();
      assertThat(executor.isRunning()).as("stop 뒤에는 멈춰 있다").isFalse();

      executor.start();

      assertThat(executor.isRunning()).as("start 가 워커를 되살린다").isTrue();
      AtomicInteger ran = new AtomicInteger();
      assertThat(submit(executor, ran::incrementAndGet)).as("다시 받아들인다").isTrue();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(ran.get()).isEqualTo(1);
    } finally {
      executor.shutdown();
    }
  }

  /** 실행 중 start 는 아무것도 바꾸지 않는다(WD-629) — 진행 중인 작업과 큐에 든 작업이 그대로 같은 워커에서 끝난다. */
  @Test
  void start_whileRunning_isNoOp() throws Exception {
    PythonReadSyncExecutor executor = new PythonReadSyncExecutor();
    try {
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch started = new CountDownLatch(1);
      AtomicReference<Thread> firstThread = new AtomicReference<>();
      AtomicReference<Thread> secondThread = new AtomicReference<>();
      submit(
          executor,
          () -> {
            firstThread.set(Thread.currentThread());
            started.countDown();
            awaitQuietly(release);
          });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      submit(executor, () -> secondThread.set(Thread.currentThread()));

      executor.start();

      assertThat(executor.isRunning()).isTrue();
      release.countDown();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(secondThread.get()).as("큐에 든 작업도 같은 워커 스레드에서 돌았다").isSameAs(firstThread.get());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * 회귀(WD-629): 종료 비우기가 시간 초과로 shutdownNow 에 이르면, 돌지 못하고 버려진 작업도 pending 에서 빼고 버림 건수로 센다. 고치기 전에는
   * 반환 목록의 작업에 finishOne 이 불리지 않아 pending 이 영구히 남고 awaitIdle 이 늘 false 였다(테스트 대기가 매번 시간 초과).
   */
  @Test
  void shutdownNowOnDrainTimeout_releasesPendingAndCountsDropped() throws Exception {
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(PythonReadSyncExecutor.QUEUE_CAPACITY, Duration.ofMillis(100));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch never = new CountDownLatch(1);
    AtomicInteger ran = new AtomicInteger();
    submit(
        executor,
        () -> {
          started.countDown();
          // shutdownNow 의 인터럽트로 풀려난다(awaitQuietly 는 플래그만 되살린다).
          awaitQuietly(never);
        });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    submit(executor, ran::incrementAndGet);
    submit(executor, ran::incrementAndGet);

    executor.shutdown();

    assertThat(executor.awaitIdle(Duration.ofSeconds(5))).as("버려진 작업이 pending 에 남지 않는다").isTrue();
    assertThat(ran.get()).as("큐에 남은 두 작업은 돌지 않았다").isZero();
    assertThat(executor.droppedCount()).as("버린 두 작업을 센다").isEqualTo(2);
  }

  /**
   * 회귀(WD-629, WD-655 에서 조정): 다른 스레드의 stop 이 비우는 도중 start 가 와도 동기화 두 개가 동시에 돌지 않는다. WD-655 부터 start
   * 는 기다리지 않고 새 워커를 바로 만들지만, 새 워커의 첫 작업이 옛 워커의 끝을 기다리므로 start 직후의 제출은 옛 작업이 끝난 뒤에 돈다.
   */
  @Test
  void start_duringDrain_newWorkRunsOnlyAfterOldWorkerEnds() throws Exception {
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(PythonReadSyncExecutor.QUEUE_CAPACITY, Duration.ofSeconds(10));
    try {
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      AtomicBoolean oldRunning = new AtomicBoolean(true);
      submit(
          executor,
          () -> {
            started.countDown();
            awaitQuietly(release);
            oldRunning.set(false);
          });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      // 다른 스레드가 stop — 첫 작업이 풀릴 때까지 비우는 중으로 머문다.
      Thread stopper = new Thread(executor::stop);
      stopper.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (executor.isRunning() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertThat(executor.isRunning()).as("stop 이 꺼짐으로 바꿨다(비우는 중)").isFalse();

      executor.start();

      assertThat(executor.isRunning()).isTrue();
      AtomicReference<Boolean> oldRunningSeenByNew = new AtomicReference<>();
      assertThat(submit(executor, () -> oldRunningSeenByNew.set(oldRunning.get()))).isTrue();
      assertThat(executor.awaitIdle(Duration.ofMillis(100)))
          .as("옛 작업이 끝나기 전에는 새 작업도 돌지 않는다")
          .isFalse();
      release.countDown();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(oldRunningSeenByNew.get()).as("새 워커의 작업은 옛 작업과 겹치지 않았다").isFalse();
      stopper.join();
    } finally {
      executor.shutdown();
    }
  }

  /**
   * WD-655: 비우기 제한 시간이 지나도 옛 워커가 끝나지 않을 때(인터럽트를 무시하는 작업) start 뒤의 제출은 버려지지 않는다 — 새 워커의 큐에 들어가 옛 워커가
   * 끝난 뒤에 돈다. 예전에는 옛 워커가 살아 있는 동안의 제출을 버렸다(버림+WARN). 두 워커의 작업은 겹치지 않는다.
   */
  @Test
  void submitWhileOldWorkerAlive_isExecutedAfterOldEnds_notDropped() throws Exception {
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(PythonReadSyncExecutor.QUEUE_CAPACITY, Duration.ofMillis(200));
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean oldRunning = new AtomicBoolean(true);
    AtomicReference<Thread> oldThread = submitInterruptIgnoringTask(executor, release, oldRunning);
    executor.stop(); // 200ms 비우기 → shutdownNow → 작업이 인터럽트를 무시해 옛 워커가 끝나지 않는다.
    executor.start();

    AtomicInteger ran = new AtomicInteger();
    AtomicReference<Boolean> oldRunningSeenByNew = new AtomicReference<>();
    assertThat(
            submit(
                executor,
                () -> {
                  oldRunningSeenByNew.set(oldRunning.get());
                  ran.incrementAndGet();
                }))
        .as("옛 워커가 살아 있어도 받아들인다")
        .isTrue();
    assertThat(executor.awaitIdle(Duration.ofMillis(100))).as("옛 워커가 끝나기 전에는 돌지 않는다").isFalse();
    assertThat(ran.get()).isZero();

    release.countDown();
    oldThread.get().join(TimeUnit.SECONDS.toMillis(5));
    assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
    assertThat(ran.get()).as("버려지지 않고 옛 워커가 끝난 뒤 돌았다").isEqualTo(1);
    assertThat(oldRunningSeenByNew.get()).as("새 워커의 작업은 옛 작업과 겹치지 않았다").isFalse();
    assertThat(executor.droppedCount()).isZero();
    executor.shutdown();
  }

  /**
   * WD-655: 옛 워커가 안 끝난 채 새 워커가 다시 닫혀도(stop) 그다음 start 의 워커는 가장 오래된 옛 워커까지 기다린다 — 직전 워커만 기다리면 끝난 직전
   * 워커를 보고 바로 돌아 가장 오래된 워커의 작업과 겹친다.
   */
  @Test
  void restartTwiceWhileOldestWorkerAlive_stillWaitsForOldest() throws Exception {
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(PythonReadSyncExecutor.QUEUE_CAPACITY, Duration.ofMillis(200));
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean oldRunning = new AtomicBoolean(true);
    AtomicReference<Thread> oldThread = submitInterruptIgnoringTask(executor, release, oldRunning);
    executor.stop();
    executor.start(); // 두 번째 워커 — 첫 작업이 가장 오래된 워커를 기다린다.
    executor.stop(); // 두 번째 워커는 비우기 시간 초과 → shutdownNow 로 기다리기를 그만두고 끝난다.
    executor.start(); // 세 번째 워커.

    AtomicReference<Boolean> oldRunningSeenByNew = new AtomicReference<>();
    assertThat(submit(executor, () -> oldRunningSeenByNew.set(oldRunning.get()))).isTrue();
    assertThat(executor.awaitIdle(Duration.ofMillis(100)))
        .as("가장 오래된 워커가 끝나기 전에는 돌지 않는다")
        .isFalse();

    release.countDown();
    oldThread.get().join(TimeUnit.SECONDS.toMillis(5));
    assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
    assertThat(oldRunningSeenByNew.get()).as("새 작업은 가장 오래된 워커의 작업과 겹치지 않았다").isFalse();
    executor.shutdown();
  }

  /**
   * 회귀(WD-673): 재시작 뒤 옛 스케줄러에 인터럽트를 무시하고 멈춘 시도가 있어도 새 스케줄러는 상한({@code retiredAwaitTimeout})까지만 기다리고
   * 다른 테넌트의 작업을 진행한다 — 예전에는 1분마다 WARN 만 남기며 무기한 기다려 모든 테넌트의 커밋 뒤 동기화가 멈췄다. 상한 전에는 여전히 기다리고(순서 보장),
   * 멈춘 시도의 테넌트에 온 제출은 그 작업에 합쳐져 겹쳐 돌지 않으며, 멈춘 시도가 끝나면 그 뒤에 돈다. 버림은 없다. 변이: 상한을 지우고 무기한 기다리면 새 작업이
   * 돌지 않아 실패.
   */
  @Test
  void restartWithStuckOldAttempt_proceedsAfterBound_andKeepsStuckTenantSerialized()
      throws Exception {
    Duration bound = Duration.ofMillis(500);
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(
            PythonReadSyncExecutor.QUEUE_CAPACITY, Duration.ofMillis(200), bound);
    String stuckGroup = "stuck-" + KEYS.incrementAndGet();
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean oldRunning = new AtomicBoolean(true);
    AtomicReference<Thread> oldThread = new AtomicReference<>();
    AtomicInteger stuckGroupAttempts = new AtomicInteger();
    AtomicReference<Boolean> oldRunningSeenByFollowUp = new AtomicReference<>();
    executor.submitRetryable(
        stuckGroup,
        "t",
        (items, lockWait) -> {
          if (stuckGroupAttempts.incrementAndGet() == 1) {
            oldThread.set(Thread.currentThread());
            started.countDown();
            while (release.getCount() > 0) {
              try {
                release.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException ignored) {
                // 의도적으로 무시 — 응답 없는 JDBC 호출처럼 멈춘 시도
              }
            }
            oldRunning.set(false);
          } else {
            oldRunningSeenByFollowUp.set(oldRunning.get());
          }
          return Outcome.DONE;
        });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    executor.stop(); // 200ms 비우기 → shutdownNow → 시도가 인터럽트를 무시해 옛 스케줄러가 끝나지 않는다.
    long restartedAt = System.nanoTime();
    executor.start();

    CountDownLatch otherRan = new CountDownLatch(1);
    assertThat(submit(executor, otherRan::countDown)).isTrue();
    assertThat(executor.submitRetryable(stuckGroup, "t2", (items, lockWait) -> Outcome.DONE))
        .as("멈춘 시도의 테넌트에 온 제출은 그 작업에 합쳐진다")
        .isTrue();
    assertThat(otherRan.await(100, TimeUnit.MILLISECONDS)).as("상한 전에는 옛 스케줄러를 기다린다").isFalse();
    assertThat(otherRan.await(5, TimeUnit.SECONDS)).as("상한 뒤에는 다른 테넌트 작업이 돈다").isTrue();
    assertThat(Duration.ofNanos(System.nanoTime() - restartedAt)).isGreaterThanOrEqualTo(bound);
    assertThat(oldRunning.get()).as("옛 시도는 아직 멈춰 있다").isTrue();
    assertThat(stuckGroupAttempts.get()).as("멈춘 테넌트는 겹쳐 돌지 않는다").isEqualTo(1);

    release.countDown();
    oldThread.get().join(TimeUnit.SECONDS.toMillis(5));
    assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
    assertThat(stuckGroupAttempts.get()).as("합쳐진 항목은 멈춘 시도가 끝난 뒤 한 번 돈다").isEqualTo(2);
    assertThat(oldRunningSeenByFollowUp.get()).as("뒤따르는 회차는 옛 시도와 겹치지 않았다").isFalse();
    assertThat(executor.droppedCount()).as("멈춘 시도도, 그 뒤 제출도 버리지 않는다").isZero();
    executor.shutdown();
  }

  /**
   * WD-655: isRunning 은 워커의 생사가 아니라 라이프사이클 상태(켜짐)다. 옛 워커가 안 끝난 채 start 했어도 isRunning 이 true 라
   * Spring 이 stop 을 건너뛰지 않는다(SmartLifecycle 은 isRunning 이 true 인 빈에만 stop 을 부른다). 그 stop 뒤에는 꺼짐이고
   * 제출을 버린다.
   */
  @Test
  void isRunning_reflectsLifecycleState_afterStartWithAliveOldWorker() throws Exception {
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(PythonReadSyncExecutor.QUEUE_CAPACITY, Duration.ofMillis(200));
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean oldRunning = new AtomicBoolean(true);
    AtomicReference<Thread> oldThread = submitInterruptIgnoringTask(executor, release, oldRunning);
    executor.stop();
    assertThat(executor.isRunning()).isFalse();

    executor.start();
    assertThat(executor.isRunning()).as("옛 워커가 살아 있어도 켜짐이다").isTrue();

    executor.stop();
    assertThat(executor.isRunning()).isFalse();
    AtomicInteger ran = new AtomicInteger();
    assertThat(submit(executor, ran::incrementAndGet)).as("꺼짐 뒤 제출은 버린다").isFalse();
    release.countDown();
    oldThread.get().join(TimeUnit.SECONDS.toMillis(5));
    assertThat(submit(executor, ran::incrementAndGet)).as("옛 워커가 끝나도 꺼짐이면 워커를 만들지 않는다").isFalse();
    assertThat(ran.get()).isZero();
  }

  /**
   * WD-655: start 와 stop 이 다른 스레드에서 겹쳐도 끝난 뒤의 상태가 늘 일관된다 — 켜짐이면 제출을 받아들이고, 꺼짐이면 버린다(꺼짐인데 새 워커가 생기거나
   * 켜짐인데 닫힌 워커가 남지 않는다). 예전에는 stop 이 꺼짐 표시와 워커 닫기 사이에 모니터를 놓아, 그 사이의 start 가 켜짐인데 닫힌 워커를 남길 수 있었다.
   * 경쟁 재현이라 매번 실패를 보장하지는 않는 스트레스 검사다.
   */
  @Test
  void concurrentStartStop_leavesConsistentState() throws Exception {
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(PythonReadSyncExecutor.QUEUE_CAPACITY, Duration.ofSeconds(5));
    try {
      for (int round = 0; round < 300; round++) {
        CountDownLatch go = new CountDownLatch(1);
        Thread starter =
            new Thread(
                () -> {
                  awaitQuietly(go);
                  executor.start();
                });
        Thread stopper =
            new Thread(
                () -> {
                  awaitQuietly(go);
                  executor.stop();
                });
        starter.start();
        stopper.start();
        go.countDown();
        starter.join();
        stopper.join();
        boolean running = executor.isRunning();
        assertThat(submit(executor, () -> {}))
            .as("round %d: 제출 수락 여부가 라이프사이클 상태(isRunning=%s)와 같다", round, running)
            .isEqualTo(running);
      }
      executor.stop();
      assertThat(executor.isRunning()).isFalse();
      assertThat(submit(executor, () -> {})).as("끝난 stop 뒤에는 늘 버린다").isFalse();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
    } finally {
      executor.shutdown();
    }
  }

  /** WD-655: stop 뒤(꺼짐)에는 옛 워커가 끝났어도 submit 이 워커를 새로 만들지 않는다 — 컨텍스트 종료 뒤 스레드 생성 방지. */
  @Test
  void submitAfterStop_doesNotCreateWorker() throws Exception {
    PythonReadSyncExecutor executor = new PythonReadSyncExecutor();
    executor.start();
    executor.stop();
    AtomicInteger ran = new AtomicInteger();
    assertThat(submit(executor, ran::incrementAndGet)).isFalse();
    assertThat(executor.isRunning()).isFalse();
    assertThat(ran.get()).isZero();
  }

  /**
   * WD-629: submit 과 stop/start 가 겹쳐도 각 작업은 받아들여져 실행되거나 버려져 세어지는 것 중 정확히 하나로 끝난다 — 대기 수(pending)가
   * 남거나 음수가 되지 않는다(awaitIdle 이 true, 실행 + 버림 = 제출). start 직후의 제출은 새 워커로 간다(lifecycleLock 안에서 워커 읽기
   * + execute).
   */
  @Test
  void submitConcurrentWithStopStart_accountsEveryTaskExactlyOnce() throws Exception {
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(PythonReadSyncExecutor.QUEUE_CAPACITY, Duration.ofSeconds(5));
    AtomicInteger ran = new AtomicInteger();
    AtomicInteger accepted = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    int perThread = 2_000;
    Thread[] submitters = new Thread[4];
    for (int i = 0; i < submitters.length; i++) {
      submitters[i] =
          new Thread(
              () -> {
                for (int k = 0; k < perThread; k++) {
                  if (submit(executor, ran::incrementAndGet)) {
                    accepted.incrementAndGet();
                  } else {
                    rejected.incrementAndGet();
                  }
                }
              });
      submitters[i].start();
    }
    // stop/start 는 이 스레드만 부른다 — start 가 돌아온 직후의 제출은 반드시 받아들여진다(큐 상한 10000 > 제출 총수).
    AtomicInteger afterStart = new AtomicInteger();
    int rounds = 20;
    for (int round = 0; round < rounds; round++) {
      executor.stop();
      executor.start();
      assertThat(submit(executor, afterStart::incrementAndGet)).as("start 직후 제출은 받아들인다").isTrue();
    }
    for (Thread t : submitters) {
      t.join();
    }
    assertThat(executor.awaitIdle(Duration.ofSeconds(10))).as("pending 이 남지 않는다").isTrue();
    executor.stop();
    assertThat(accepted.get() + rejected.get()).isEqualTo(submitters.length * perThread);
    assertThat(ran.get()).as("받아들인 작업은 모두 돌았다(stop 이 비운다)").isEqualTo(accepted.get());
    assertThat(afterStart.get()).isEqualTo(rounds);
    assertThat(executor.droppedCount()).as("버린 것은 거부된 제출뿐").isEqualTo(rejected.get());
  }

  /**
   * WD-644 × WD-655: 잠금이 바빠(BUSY) 비우기 중 계속 다시 줄에 서는 테넌트 작업이 있을 때 다른 스레드의 start 가 오면, 비우던 stop 은 비우기
   * 제한 시간(10초)까지 기다리지도, 그 작업을 버리지도 않고 곧바로 돌아온다(넘겨받기). 작업은 새 스케줄러에서 평소 규칙으로 이어 돌고, 재시작 뒤 같은 테넌트에 들어온
   * 항목도 그 작업에 합쳐져 함께 적용된다. 변이: discardRemaining 의 넘겨받기 확인을 지우면 stop 이 10초 뒤 그 작업을 버림으로 센다 — 또는
   * awaitIdleOrRestart 가 재시작에 깨지 않으면 stop 이 제한 시간까지 멈춘다.
   */
  @Test
  void start_duringDrainWithBusyJob_handsOverWithoutDropping() throws Exception {
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(
            100,
            new PythonReadSyncExecutor.RetryPolicy(
                Duration.ofMillis(5), Duration.ofMillis(5), 3, 5),
            Duration.ofSeconds(10));
    try {
      AtomicBoolean busy = new AtomicBoolean(true);
      List<List<String>> runs = new CopyOnWriteArrayList<>();
      PythonReadSyncExecutor.Attempt sync =
          (items, lockWait) -> {
            runs.add(items);
            return busy.get() ? Outcome.BUSY : Outcome.DONE;
          };
      assertThat(executor.submitRetryable("A", "a1", sync)).isTrue();
      await().atMost(Duration.ofSeconds(5)).until(() -> runs.size() >= 2);

      Thread stopper = new Thread(executor::stop);
      stopper.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (executor.isRunning() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertThat(executor.isRunning()).as("stop 이 비우기를 시작했다").isFalse();

      executor.start();

      stopper.join(TimeUnit.SECONDS.toMillis(3));
      assertThat(stopper.isAlive()).as("넘겨받은 stop 은 비우기 제한 시간까지 기다리지 않고 돌아온다").isFalse();
      assertThat(executor.isRunning()).isTrue();
      assertThat(executor.submitRetryable("A", "a2", sync))
          .as("재시작 뒤 같은 테넌트 제출은 그 작업에 합쳐진다")
          .isTrue();
      busy.set(false);
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(executor.droppedCount()).as("넘겨받은 작업은 버리지 않는다").isZero();
      assertThat(runs.stream().filter(r -> r.contains("a1")).count())
          .as("a1 은 BUSY 동안 다시 줄에 섰다가 적용됐다")
          .isGreaterThan(2);
      assertThat(runs).anySatisfy(r -> assertThat(r).contains("a2"));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * WD-644 × WD-655: 비우기 시간 초과 뒤에도 끝나지 않는(인터럽트를 무시하는) 테넌트 작업이 있는 채 start 하면, 그 테넌트에 새로 들어온 항목은 그
   * 작업에 합쳐져 버려지지 않고, 옛 시도가 끝난 뒤 새 스케줄러에서 한 번 더 돈다(옛 시도와 겹치지 않는다). 변이: 실행 중 작업의 다음 시도가 닫힌 옛 스케줄러로
   * 가면(현재 스케줄러를 읽지 않으면) 거절되어 a2 가 버림으로 세어진다.
   */
  @Test
  void start_afterDrainTimeoutWithStuckJob_followUpIsMergedAndRunsAfterOldAttempt()
      throws Exception {
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(PythonReadSyncExecutor.QUEUE_CAPACITY, Duration.ofMillis(200));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean oldRunning = new AtomicBoolean(false);
    AtomicReference<Thread> oldThread = new AtomicReference<>();
    List<List<String>> runs = new CopyOnWriteArrayList<>();
    AtomicReference<Boolean> overlapSeen = new AtomicReference<>();
    PythonReadSyncExecutor.Attempt sync =
        (items, lockWait) -> {
          runs.add(items);
          if (runs.size() == 1) {
            oldThread.set(Thread.currentThread());
            oldRunning.set(true);
            started.countDown();
            // 인터럽트를 무시하고 풀릴 때까지 버틴다(shutdownNow 로도 끝나지 않는 시도).
            while (release.getCount() > 0) {
              try {
                release.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException ignored) {
                // 의도적으로 무시
              }
            }
            oldRunning.set(false);
          } else {
            overlapSeen.set(oldRunning.get());
          }
          return Outcome.DONE;
        };
    executor.submitRetryable("A", "a1", sync);
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    executor.stop(); // 200ms 비우기 → shutdownNow → 시도가 인터럽트를 무시해 옛 스케줄러가 끝나지 않는다.
    executor.start();

    assertThat(executor.submitRetryable("A", "a2", sync)).as("옛 시도가 살아 있어도 받아들인다").isTrue();
    assertThat(executor.awaitIdle(Duration.ofMillis(100))).as("옛 시도가 끝나기 전에는 끝나지 않는다").isFalse();

    release.countDown();
    oldThread.get().join(TimeUnit.SECONDS.toMillis(5));
    assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
    assertThat(runs).containsExactly(List.of("a1"), List.of("a2"));
    assertThat(overlapSeen.get()).as("a2 의 시도는 옛 시도와 겹치지 않았다").isFalse();
    assertThat(executor.droppedCount()).isZero();
    executor.shutdown();
  }

  /**
   * 인터럽트를 무시하고 {@code release} 까지 버티는 작업을 넣고 시작을 기다린다(shutdownNow 로도 끝나지 않는 옛 워커 재현, WD-655). 끝날 때
   * {@code running} 을 false 로 바꾼다.
   *
   * @return 그 작업을 돌린 워커 스레드
   */
  private static AtomicReference<Thread> submitInterruptIgnoringTask(
      PythonReadSyncExecutor executor, CountDownLatch release, AtomicBoolean running)
      throws InterruptedException {
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<Thread> thread = new AtomicReference<>();
    submit(
        executor,
        () -> {
          thread.set(Thread.currentThread());
          started.countDown();
          while (release.getCount() > 0) {
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
              // 의도적으로 무시
            }
          }
          running.set(false);
        });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    return thread;
  }

  /**
   * WD-644 리뷰: 잠금을 잡아 묶음을 돌았지만 일부 테이블만 실패하면(PartialFailure) 실패한 테이블만 다룬다 — 일시 실패(b)는 그것만 다시 하고, 영구
   * 실패(c)는 그것만 버려 세며, 끝난 테이블(a)은 다시 하지 않는다. 예전처럼 묶음 전체를 영구 실패로 버리면 a 까지 버림으로 세고, 묶음 전체를 다시 하면 두 번째
   * 시도에 a 가 실린다(변이).
   */
  @Test
  void submitRetryable_partialFailure_retriesOrDropsOnlyFailedItems() throws Exception {
    PythonReadSyncExecutor executor = executor(Duration.ofMillis(2), CAP, 3, 5);
    try {
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch started = new CountDownLatch(1);
      submit(
          executor,
          () -> {
            started.countDown();
            awaitQuietly(release);
          });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      List<List<String>> runs = new CopyOnWriteArrayList<>();
      PythonReadSyncExecutor.Attempt sync =
          (items, lockWait) -> {
            runs.add(items);
            if (runs.size() == 1) {
              return new PythonReadSyncExecutor.PartialFailure(List.of("b"), List.of("c"));
            }
            return Outcome.DONE;
          };
      executor.submitRetryable("t1", "a", sync);
      executor.submitRetryable("t1", "b", sync);
      executor.submitRetryable("t1", "c", sync);
      release.countDown();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(runs)
          .as("처음엔 묶음 전체, 다시 하는 것은 일시 실패한 b 뿐")
          .containsExactly(List.of("a", "b", "c"), List.of("b"));
      assertThat(executor.droppedCount()).as("영구 실패한 c 만 버림").isEqualTo(1);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * WD-644 리뷰: 일시 실패 횟수는 항목마다 센다 — 계속 실패하는 b 만 상한을 넘겨 버려지고, 그 사이 끝난 a 는 한 번만 돌며 버림으로 세지 않는다. 시도 수 =
   * 첫 시도 + 재시도 maxFailures 번.
   */
  @Test
  void submitRetryable_partialTransientFailure_dropsOnlyThatItemAfterMaxFailures()
      throws Exception {
    PythonReadSyncExecutor executor = executor(Duration.ofMillis(2), CAP, 3, 2);
    try {
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch started = new CountDownLatch(1);
      submit(
          executor,
          () -> {
            started.countDown();
            awaitQuietly(release);
          });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      List<List<String>> runs = new CopyOnWriteArrayList<>();
      PythonReadSyncExecutor.Attempt sync =
          (items, lockWait) -> {
            runs.add(items);
            return items.contains("b")
                ? new PythonReadSyncExecutor.PartialFailure(List.of("b"), List.of())
                : Outcome.DONE;
          };
      executor.submitRetryable("t1", "a", sync);
      executor.submitRetryable("t1", "b", sync);
      release.countDown();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(runs).containsExactly(List.of("a", "b"), List.of("b"), List.of("b"));
      assertThat(executor.droppedCount()).as("b 하나만 버림").isEqualTo(1);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * WD-644 리뷰: 시도가 Error(AssertionError 등)로 끝나도 작업이 "실행 중" 에 갇히지 않는다 — 시도한 항목은 버림으로 세고, 그 시도 중 들어온
   * 같은 테넌트 항목은 새 회차로 돌며, 같은 테넌트의 다음 제출도 돌고, 다른 테넌트 작업도 계속 돈다(스레드가 죽지 않는다). RuntimeException 만 받던 예전
   * 코드는 running=true 로 남아 뒤따르는 항목·다음 제출이 영영 돌지 않았다(변이).
   */
  @Test
  void submitRetryable_attemptThrowsError_jobIsCleanedUpAndOthersKeepRunning() throws Exception {
    PythonReadSyncExecutor executor = executor(Duration.ofMillis(2), CAP);
    try {
      List<List<String>> runs = new CopyOnWriteArrayList<>();
      AtomicReference<PythonReadSyncExecutor.Attempt> self = new AtomicReference<>();
      self.set(
          (items, lockWait) -> {
            runs.add(items);
            if (runs.size() == 1) {
              // 시도 중 같은 테넌트에 항목이 하나 더 들어온 뒤 치명 오류로 끝난다.
              executor.submitRetryable("A", "a2", self.get());
              throw new AssertionError("치명 오류 흉내");
            }
            return Outcome.DONE;
          });
      executor.submitRetryable("A", "a1", self.get());
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).as("작업이 실행 중에 갇히지 않는다").isTrue();
      assertThat(runs).as("시도 중 들어온 a2 는 새 회차로 돈다").containsExactly(List.of("a1"), List.of("a2"));
      assertThat(executor.droppedCount()).as("Error 로 끝난 시도의 a1 은 버림으로 센다").isEqualTo(1);

      // 같은 테넌트의 다음 제출과 다른 테넌트의 작업이 모두 돈다.
      CountDownLatch otherTenant = new CountDownLatch(1);
      assertThat(executor.submitRetryable("A", "a3", self.get())).isTrue();
      assertThat(
              executor.submitRetryable(
                  "B",
                  "b1",
                  (items, lockWait) -> {
                    otherTenant.countDown();
                    return Outcome.DONE;
                  }))
          .isTrue();
      assertThat(otherTenant.await(5, TimeUnit.SECONDS)).as("다른 테넌트 작업도 돈다").isTrue();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(runs).last().isEqualTo(List.of("a3"));
      assertThat(executor.droppedCount()).isEqualTo(1);
    } finally {
      executor.shutdown();
    }
  }

  /** 테스트 작업이 풀려날 때까지 기다린다(인터럽트는 플래그만 되살린다). */
  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
