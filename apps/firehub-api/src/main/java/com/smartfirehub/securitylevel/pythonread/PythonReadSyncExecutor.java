package com.smartfirehub.securitylevel.pythonread;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 커밋 뒤 PYTHON 읽기 슬롯 GRANT 동기화(테이블 하나)를 돌리는 전용 단일 스레드 실행기(WD-594). PG({@link
 * PythonReadGrantSync})·Tibero({@link TiberoPythonReadGrantSync}) 두 백엔드가 같이 쓴다.
 *
 * <p><b>왜 따로 스레드인가(커넥션 1개 원칙).</b> 동기화는 REQUIRES_NEW 로 커넥션을 하나 더 잡는다. 이를 커밋 콜백(afterCommit·
 * afterCompletion)에서 호출 스레드로 바로 돌리면, Spring 은 그 콜백들을 부른 <b>뒤</b>에 바깥 커넥션을 반납하므로(spring-tx
 * AbstractPlatformTransactionManager.processCommit: triggerAfterCommit → triggerAfterCompletion →
 * finally cleanupAfterCompletion) 요청 하나가 커넥션 2개를 쥔다. 풀 크기만큼 데이터셋 생성·복제가 동시에 커밋하면 모두가 두 번째 커넥션을 기다려
 * 커넥션 타임아웃(30초)까지 멈추고 동기화도 실패한다. 실행기 스레드는 바깥 커넥션 없이 커넥션 하나만 쓰므로 서로 막지 않는다 — {@code
 * SecurityAuditRecorder} 와 같은 방식이다.
 *
 * <p><b>포화·종료.</b> 큐는 유한하다. 차거나 종료 중이면 호출자(커밋 콜백)를 막지 않고 버린 뒤 WARN(누적 건수)만 남긴다. 이 동기화는 판정이 아니라 이중
 * 방어선이라 PYTHON 실행 직전(JIT) 동기화와 일 1회 전체 동기화가 회복한다. 같은 테넌트의 동기화는 advisory lock 으로 직렬화되므로 순서가 바뀌어도 정합성
 * 문제는 없다(각 동기화가 그 시점 계산값에 맞춘다).
 *
 * <p><b>종료 순서(SmartLifecycle).</b> 남은 동기화를 비우려면 DataSource(Hikari 풀)가 아직 살아 있어야 한다. 이 빈은 DataSource
 * 에 의존하지 않으므로 {@code @PreDestroy} 에서만 비우면 풀이 먼저(또는 비우는 도중에) 닫혀 비운 동기화가 모두 실패할 수 있다. 그래서 {@link
 * SmartLifecycle#stop()} 에서 최대 10초 비운다 — 컨텍스트 종료 시 모든 라이프사이클 빈의 stop 이 어떤 빈 소멸보다도 앞서 끝나므로, 비우는 동안
 * 커넥션 풀은 아직 열려 있다. {@code @PreDestroy} 는 멱등 안전망으로만 남긴다.
 */
@Slf4j
@Component
public class PythonReadSyncExecutor implements SmartLifecycle {

  /** 대기 큐 상한 — 넘치면 버리고 WARN(호출자를 막지 않는다). */
  static final int QUEUE_CAPACITY = 10_000;

  /** 종료 시 남은 동기화를 비우는 최대 대기. */
  private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

  /** 단일 스레드 실행기(유한 큐, AbortPolicy — 포화 시 예외를 받아 버림 처리. CallerRuns 면 호출자를 다시 막는다). */
  private final ThreadPoolExecutor worker;

  /** 넘겼으나 아직 끝나지 않은 작업 수 — {@link #awaitIdle} 가 기다리는 대상. {@code this} 모니터로 보호한다. */
  private int pending;

  /** 큐 포화·종료로 버린 작업 누적 수(WARN 로그에 함께 남긴다). */
  private final AtomicLong dropped = new AtomicLong();

  /** 운영용 — 큐 상한 {@link #QUEUE_CAPACITY}. */
  public PythonReadSyncExecutor() {
    this(QUEUE_CAPACITY);
  }

  /** 테스트용 — 작은 큐로 포화를 재현한다. */
  PythonReadSyncExecutor(int queueCapacity) {
    this.worker =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            r -> {
              Thread th = new Thread(r, "python-read-grant-sync");
              th.setDaemon(true);
              return th;
            });
  }

  /**
   * 작업을 실행기에 넘긴다. 큐 포화·종료면 예외를 던지지 않고 버린 뒤 WARN — 커밋 콜백에서 불리므로 어떤 경우에도 호출자를 막거나 실패시키지 않는다. 작업 자체의
   * 예외 처리(로그)는 작업 쪽 책임이지만, 새어 나와도 워커 스레드가 죽지 않도록 여기서 한 번 더 받는다.
   *
   * @return 받아들였으면 true, 버렸으면 false
   */
  public boolean submit(Runnable work) {
    synchronized (this) {
      pending++;
    }
    try {
      worker.execute(
          () -> {
            try {
              work.run();
            } catch (RuntimeException e) {
              log.warn("PYTHON 읽기 권한 동기화 작업이 예외로 끝났다(다음 동기화에서 회복)", e);
            } finally {
              finishOne();
            }
          });
      return true;
    } catch (RejectedExecutionException e) {
      finishOne();
      log.warn(
          "PYTHON 읽기 권한 동기화를 버렸다 — 큐 포화 또는 종료 중(누적 {}건). 실행 직전·일 1회 동기화가 회복한다",
          dropped.incrementAndGet());
      return false;
    }
  }

  /** 작업 하나가 끝났다(또는 버려졌다) — 0 이 되면 {@link #awaitIdle} 대기자를 깨운다. */
  private synchronized void finishOne() {
    pending--;
    if (pending == 0) {
      notifyAll();
    }
  }

  /**
   * 넘긴 작업이 모두 끝날 때까지 최대 {@code timeout} 기다린다. <b>테스트 결정성용 seam</b> — 커밋 뒤 슬롯 GRANT 를 단언하는 테스트가 단언
   * 전에 부른다. 운영 코드는 부르지 않는다.
   *
   * @return 시간 안에 모두 끝났으면 true
   */
  public synchronized boolean awaitIdle(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (pending > 0) {
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        return false;
      }
      TimeUnit.NANOSECONDS.timedWait(this, left);
    }
    return true;
  }

  /** 라이프사이클 시작 — 워커는 생성자에서 이미 만들어졌고 실행 상태는 워커 종료 여부로 판단하므로 할 일이 없다(종료된 실행기는 다시 켜지 않는다). */
  @Override
  public void start() {
    // 의도적으로 비움 — isRunning() 이 워커 상태를 그대로 본다.
  }

  /**
   * 라이프사이클 종료 — DataSource 등 빈이 소멸되기 전에 큐에 남은 동기화를 제한 시간 안에 비운다. 이후 제출은 {@link #submit} 이 WARN 과 함께
   * 버린다(예외 없음).
   */
  @Override
  public void stop() {
    shutdown();
  }

  /** 워커가 종료(stop·안전망)되기 전까지 실행 중이다. */
  @Override
  public boolean isRunning() {
    return !worker.isShutdown();
  }

  /**
   * 큐에 남은 동기화를 제한 시간 안에 비운다. 넘기면 남은 작업을 버리고 WARN(다음 기동 뒤 동기화가 회복). {@link #stop()} 이 정상 경로이고,
   * {@code @PreDestroy} 는 라이프사이클 stop 이 불리지 않은 경우(예: 컨텍스트 refresh 실패)를 위한 안전망이다. stop 과 @PreDestroy
   * 는 종료 스레드에서 차례로 돌고, ThreadPoolExecutor 의 shutdown·awaitTermination 은 스레드 안전·멱등이라 두 번 불려도 이미 종료된
   * 실행기에서는 곧바로 끝난다.
   */
  @PreDestroy
  void shutdown() {
    worker.shutdown();
    try {
      if (!worker.awaitTermination(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        int left = worker.shutdownNow().size();
        log.warn("종료 시 PYTHON 읽기 권한 동기화 {}건을 비우지 못하고 버렸다", left);
      }
    } catch (InterruptedException e) {
      worker.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
