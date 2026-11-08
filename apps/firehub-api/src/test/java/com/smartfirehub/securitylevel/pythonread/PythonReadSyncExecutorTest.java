package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 커밋 뒤 PYTHON 읽기 권한 동기화 전용 실행기(WD-594) 단위 검증 — 다른 스레드에서 돌고, 포화·종료 때 예외 없이 버리며(커밋 콜백을 막지 않는다),
 * awaitIdle 이 넘긴 작업(버린 것 포함)의 끝을 정확히 기다리는지 본다. DB 는 쓰지 않는다.
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
              executor.submit(
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

  /** 큐가 차면 예외 없이 버리고(false) 호출자를 막지 않는다. 버린 작업은 pending 에 남지 않아 awaitIdle 이 끝난다. */
  @Test
  void submit_whenSaturated_dropsWithoutThrowing() throws Exception {
    PythonReadSyncExecutor executor = new PythonReadSyncExecutor(1);
    try {
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch started = new CountDownLatch(1);
      AtomicInteger ran = new AtomicInteger();
      executor.submit(
          () -> {
            started.countDown();
            awaitQuietly(release);
            ran.incrementAndGet();
          });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue(); // 워커가 첫 작업을 쥐었다 → 큐는 비었다
      assertThat(executor.submit(ran::incrementAndGet)).as("큐 1칸").isTrue();
      assertThat(executor.submit(ran::incrementAndGet)).as("포화 → 버림").isFalse();
      release.countDown();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(ran.get()).as("받아들인 두 작업만 돌았다").isEqualTo(2);
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
      executor.submit(
          () -> {
            throw new IllegalStateException("동기화 실패 흉내");
          });
      executor.submit(ran::incrementAndGet);
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
    assertThatCode(() -> assertThat(executor.submit(ran::incrementAndGet)).isFalse())
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
    executor.submit(
        () -> {
          awaitQuietly(release);
          ran.incrementAndGet();
        });
    executor.submit(ran::incrementAndGet);
    executor.submit(ran::incrementAndGet);
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
    assertThatCode(() -> assertThat(executor.submit(ran::incrementAndGet)).isFalse())
        .doesNotThrowAnyException();
    assertThat(executor.awaitIdle(Duration.ofSeconds(1))).isTrue();
    assertThat(ran.get()).isEqualTo(3);
    releaser.join();
  }

  /**
   * 회귀(WD-629): 라이프사이클 stop 뒤 start(컨텍스트 stop/start)하면 워커를 새로 만들어 다시 받아들인다. 고치기 전에는 start 가 빈 메서드라
   * 이후 모든 제출이 RejectedExecutionException → 버림이 되어 커밋 뒤 동기화가 영구히 사라졌다.
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
      assertThat(executor.submit(ran::incrementAndGet)).as("다시 받아들인다").isTrue();
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
      executor.submit(
          () -> {
            firstThread.set(Thread.currentThread());
            started.countDown();
            awaitQuietly(release);
          });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      executor.submit(() -> secondThread.set(Thread.currentThread()));

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
    executor.submit(
        () -> {
          started.countDown();
          // shutdownNow 의 인터럽트로 풀려난다(awaitQuietly 는 플래그만 되살린다).
          awaitQuietly(never);
        });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    executor.submit(ran::incrementAndGet);
    executor.submit(ran::incrementAndGet);

    executor.shutdown();

    assertThat(executor.awaitIdle(Duration.ofSeconds(5))).as("버려진 작업이 pending 에 남지 않는다").isTrue();
    assertThat(ran.get()).as("큐에 남은 두 작업은 돌지 않았다").isZero();
    assertThat(executor.droppedCount()).as("버린 두 작업을 센다").isEqualTo(2);
  }

  /**
   * 회귀(WD-629): 다른 스레드의 stop 이 비우는 도중 start 가 와도 동기화 스레드가 둘이 되지 않는다 — start 는 옛 워커가 끝날 때까지 기다린 뒤에만
   * 새 워커로 바꾼다. 고치기 전에는 isShutdown() 만 보고 바로 바꿔, 옛 워커의 작업이 아직 도는 동안 새 워커가 새 작업을 함께 돌렸다.
   */
  @Test
  void start_duringDrain_waitsForOldWorkerBeforeSwapping() throws Exception {
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(PythonReadSyncExecutor.QUEUE_CAPACITY, Duration.ofSeconds(10));
    try {
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      java.util.concurrent.atomic.AtomicBoolean oldRunning =
          new java.util.concurrent.atomic.AtomicBoolean(true);
      executor.submit(
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
      assertThat(executor.isRunning()).as("stop 이 워커를 닫았다(비우는 중)").isFalse();
      // start 가 기다리는 동안 옛 작업을 풀어 준다.
      Thread releaser =
          new Thread(
              () -> {
                try {
                  Thread.sleep(300);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                release.countDown();
              });
      releaser.start();

      executor.start();

      assertThat(oldRunning.get()).as("start 는 옛 워커의 작업이 끝난 뒤에야 돌아온다").isFalse();
      assertThat(executor.isRunning()).isTrue();
      AtomicReference<Boolean> oldRunningSeenByNew = new AtomicReference<>();
      assertThat(executor.submit(() -> oldRunningSeenByNew.set(oldRunning.get()))).isTrue();
      assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(oldRunningSeenByNew.get()).as("새 워커의 작업은 옛 작업과 겹치지 않았다").isFalse();
      stopper.join();
      releaser.join();
    } finally {
      executor.shutdown();
    }
  }

  /**
   * WD-629: 비우기 제한 시간이 지나도 옛 워커가 끝나지 않으면(인터럽트를 무시하는 작업) start 는 새 워커를 만들지 않는다 — 동기화 스레드 중복 대신 제출을
   * 버림+WARN 으로 둔다. 옛 워커가 끝난 뒤의 다음 start 는 정상으로 바꾼다.
   */
  @Test
  void start_whenOldWorkerNeverTerminates_doesNotStartSecondWorker() throws Exception {
    PythonReadSyncExecutor executor =
        new PythonReadSyncExecutor(PythonReadSyncExecutor.QUEUE_CAPACITY, Duration.ofMillis(200));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    executor.submit(
        () -> {
          started.countDown();
          // 인터럽트를 무시하고 풀릴 때까지 버틴다(shutdownNow 로도 끝나지 않는 작업).
          while (release.getCount() > 0) {
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
              // 의도적으로 무시
            }
          }
        });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    executor.stop(); // 200ms 비우기 → shutdownNow → 작업이 인터럽트를 무시해 워커가 끝나지 않는다.
    assertThat(executor.isRunning()).isFalse();

    executor.start(); // 200ms 더 기다려도 안 끝난다 → 바꾸지 않는다.

    assertThat(executor.isRunning()).as("옛 워커가 살아 있는 동안 새 워커를 만들지 않는다").isFalse();
    AtomicInteger ran = new AtomicInteger();
    assertThat(executor.submit(ran::incrementAndGet)).as("그동안 제출은 버린다").isFalse();

    release.countDown();
    executor.start(); // 옛 워커가 끝나면 다음 start 가 바꾼다.
    assertThat(executor.isRunning()).isTrue();
    assertThat(executor.submit(ran::incrementAndGet)).isTrue();
    assertThat(executor.awaitIdle(Duration.ofSeconds(5))).isTrue();
    assertThat(ran.get()).isEqualTo(1);
    executor.shutdown();
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
                  if (executor.submit(ran::incrementAndGet)) {
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
      assertThat(executor.submit(afterStart::incrementAndGet)).as("start 직후 제출은 받아들인다").isTrue();
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

  /** 테스트 작업이 풀려날 때까지 기다린다(인터럽트는 플래그만 되살린다). */
  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
