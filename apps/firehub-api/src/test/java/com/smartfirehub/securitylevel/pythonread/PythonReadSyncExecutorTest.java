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

  /** 테스트 작업이 풀려날 때까지 기다린다(인터럽트는 플래그만 되살린다). */
  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
