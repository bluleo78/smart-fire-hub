package com.smartfirehub.securitylevel.pythonread;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * 커넥션 풀은 아직 열려 있다. {@code @PreDestroy} 는 멱등 안전망으로만 남긴다. stop 뒤 start 하면 새 워커로 다시 받아들이고, 비우기 시간 초과로
 * 버린 작업도 대기 수(awaitIdle)에서 빠진다(WD-629).
 */
@Slf4j
@Component
public class PythonReadSyncExecutor implements SmartLifecycle {

  /** 대기 큐 상한 — 넘치면 버리고 WARN(호출자를 막지 않는다). */
  static final int QUEUE_CAPACITY = 10_000;

  /** 종료 시 남은 동기화를 비우는 최대 대기(운영 기본값). */
  private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

  /** 워커 큐 상한 — stop 뒤 start 로 워커를 다시 만들 때 같은 값을 쓴다(WD-629). */
  private final int queueCapacity;

  /** 종료 시 남은 동기화를 비우는 최대 대기 — 테스트는 짧게 줘서 shutdownNow 경로를 재현한다(WD-629). */
  private final Duration drainTimeout;

  /**
   * 단일 스레드 실행기(유한 큐, AbortPolicy — 포화 시 예외를 받아 버림 처리. CallerRuns 면 호출자를 다시 막는다).
   *
   * <p>final 이 아닌 이유(WD-629): 컨텍스트 stop() → start() 뒤에도 커밋 뒤 동기화가 계속 돌아야 한다. 종료된 ThreadPoolExecutor
   * 는 되살릴 수 없으므로 {@link #start()} 만이 ({@link #replaceWorker} 로) 새 워커로 바꾼다. 교체·submit 의 넘기기·shutdown
   * 의 닫기는 모두 {@link #lifecycleLock} 안에서 일어나므로, 워커가 닫혀 있지 않은 것이 곧 「켜짐」이다({@link #isRunning()},
   * WD-655).
   */
  private volatile ThreadPoolExecutor worker;

  /**
   * 워커 교체(start)·닫기(shutdown)와 submit 의 "현재 워커 읽기 + execute" 를 한 덩어리로 묶는 모니터(WD-629·WD-655) —
   * submit 이 교체 직전의 옛 워커를 읽고 교체 뒤에 넣어 거부되는 경쟁, start·stop 이 겹쳐 「켜짐인데 닫힌 워커」가 남는 경쟁을 막는다. 이 모니터를 쥔 채
   * 기다리는 곳은 없다 — execute 는 유한 큐·AbortPolicy 라 막히지 않고, shutdown 의 비우기 대기는 모니터 밖에서 한다. pending 보호용
   * {@code this} 모니터와 분리해 awaitIdle 대기와 얽히지 않게 한다.
   */
  private final Object lifecycleLock = new Object();

  /**
   * 닫혔지만 아직 끝나지 않은 옛 워커들(WD-655) — 새 워커의 첫 작업({@link AwaitRetiredWorkers})이 이들이 모두 끝나기를 기다린다. 직전 워커
   * 하나만이 아니라 목록인 이유: 인터럽트를 무시하는 작업 때문에 옛 워커가 끝나지 않은 채 새 워커가 다시 닫히면, 그다음 워커는 더 오래된 워커도 기다려야 두 동기화가
   * 동시에 돌지 않는다. {@link #lifecycleLock} 안에서만 읽고 쓴다.
   */
  private final List<ThreadPoolExecutor> retired = new ArrayList<>();

  /** 넘겼으나 아직 끝나지 않은 작업 수 — {@link #awaitIdle} 가 기다리는 대상. {@code this} 모니터로 보호한다. */
  private int pending;

  /** 큐 포화·종료로 버린 작업 누적 수(WD-629 부터 종료 시 shutdownNow 로 버린 작업도 포함, WARN 로그에 함께 남긴다). */
  private final AtomicLong dropped = new AtomicLong();

  /** 운영용 — 큐 상한 {@link #QUEUE_CAPACITY}. */
  public PythonReadSyncExecutor() {
    this(QUEUE_CAPACITY);
  }

  /** 테스트용 — 작은 큐로 포화를 재현한다. */
  PythonReadSyncExecutor(int queueCapacity) {
    this(queueCapacity, DRAIN_TIMEOUT);
  }

  /**
   * 테스트용 — 비우기 대기를 줄여 시간 초과(shutdownNow) 경로를 재현한다(WD-629). 워커는 지금처럼 생성자에서 바로 만든다 — start 전에 제출하는
   * 경로(빈 초기화 중 커밋·단위 테스트)를 그대로 받기 위해서다.
   */
  PythonReadSyncExecutor(int queueCapacity, Duration drainTimeout) {
    this.queueCapacity = queueCapacity;
    this.drainTimeout = drainTimeout;
    this.worker = newWorker(queueCapacity);
  }

  /** 새 단일 스레드 워커(데몬 스레드 — 종료를 막지 않는다). */
  private static ThreadPoolExecutor newWorker(int capacity) {
    return new ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(capacity),
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
   * <p>현재 워커 읽기와 execute 는 {@link #lifecycleLock} 안에서 한 번에 한다(WD-629·WD-655). 그래서 각 제출은 둘 중 정확히 하나로
   * 끝나 pending 이 어긋나지 않는다: (1) 거부되어 버려지고 세어진다(큐 포화, 또는 stop 뒤~start 전의 닫힌 워커 — 이때 워커를 새로 만들지 않는다:
   * 컨텍스트 종료 뒤 스레드 생성 방지) → 여기서 finishOne 한 번. (2) 큐에 들어가 실행되거나(자기 finally), 비우기 시간 초과로 버려진다({@link
   * #discardRemaining} 이 finish 한 번). 켜진 동안에는 옛 워커가 아직 살아 있어도 새 워커의 큐에 들어가 옛 워커가 끝난 뒤에 돈다({@link
   * #replaceWorker}).
   *
   * @return 받아들였으면 true, 버렸으면 false
   */
  public boolean submit(Runnable work) {
    Runnable task =
        () -> {
          try {
            work.run();
          } catch (RuntimeException e) {
            log.warn("PYTHON 읽기 권한 동기화 작업이 예외로 끝났다(다음 동기화에서 회복)", e);
          } finally {
            finishOne();
          }
        };
    synchronized (this) {
      pending++;
    }
    try {
      synchronized (lifecycleLock) {
        worker.execute(task);
      }
      return true;
    } catch (RejectedExecutionException e) {
      finishOne();
      log.warn(
          "PYTHON 읽기 권한 동기화를 버렸다 — 큐 포화 또는 종료 중(누적 {}건). 실행 직전·일 1회 동기화가 회복한다",
          dropped.incrementAndGet());
      return false;
    }
  }

  /**
   * 닫힌 현재 워커를 새 단일 스레드 워커로 바꾼다(WD-655). {@link #lifecycleLock} 을 쥔 채 불러야 한다.
   *
   * <p>옛 워커(들)가 아직 끝나지 않았으면(비우는 중이거나 인터럽트를 무시하는 작업) 새 워커의 첫 작업으로 {@link AwaitRetiredWorkers} 를 넣는다.
   * 그 뒤에 들어온 작업은 옛 워커가 모두 끝난 다음에야 돈다 — 그래서 제출을 버리지 않으면서도 옛·새 워커의 동기화가 동시에 돌지 않는다(단일 스레드 전제: 한 번에 한
   * 동기화가 커넥션 하나만 쥔다).
   */
  private void replaceWorker() {
    ThreadPoolExecutor old = worker;
    retired.removeIf(ThreadPoolExecutor::isTerminated);
    if (!old.isTerminated()) {
      retired.add(old);
    }
    ThreadPoolExecutor fresh = newWorker(queueCapacity);
    if (!retired.isEmpty()) {
      // 첫 작업이라 큐가 아니라 새 스레드의 첫 작업으로 바로 들어간다(큐 칸을 차지하지 않는다).
      fresh.execute(new AwaitRetiredWorkers(List.copyOf(retired)));
    }
    worker = fresh;
  }

  /**
   * 새 워커의 첫 작업 — 옛 워커들이 모두 끝날 때까지 기다린다(WD-655). 그동안 새 워커의 큐에 쌓인 작업은 이 작업이 끝난 뒤에 돈다.
   *
   * <p>인터럽트는 새 워커의 shutdownNow({@link #discardRemaining})에서만 온다 — 그때는 큐에 남은 작업도 이미 꺼내 버렸으므로 기다리기를
   * 그만두고, 인터럽트 상태를 되살린 채 끝낸다. pending 에는 세지 않는 내부 작업이다(submit 을 거치지 않는다).
   */
  private record AwaitRetiredWorkers(List<ThreadPoolExecutor> previous) implements Runnable {
    @Override
    public void run() {
      for (ThreadPoolExecutor p : previous) {
        try {
          while (!p.awaitTermination(1, TimeUnit.MINUTES)) {
            log.warn("PYTHON 읽기 권한 동기화 실행기: 이전 워커가 아직 끝나지 않아 새 워커의 작업이 기다리는 중이다");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /** 버린 누적 건수 — 테스트 확인용(WD-629). */
  long droppedCount() {
    return dropped.get();
  }

  /** 작업 하나가 끝났다(또는 버려졌다) — 0 이 되면 {@link #awaitIdle} 대기자를 깨운다. */
  private void finishOne() {
    finish(1);
  }

  /**
   * 작업 n 개가 끝났다(또는 버려졌다) — 0 이 되면 {@link #awaitIdle} 대기자를 깨운다. 작업마다 정확히 한 번만 불려야 한다(실행된 작업은 자기
   * finally, 거부된 작업은 submit 의 catch, shutdownNow 로 버린 작업은 {@link #discardRemaining}). 음수가 되면 중복 계산
   * 버그이므로 숨기지 않고 ERROR 로 남긴다(WD-629) — 대기자는 그래도 깨워 awaitIdle 이 시간 초과까지 멈추지 않게 한다.
   */
  private synchronized void finish(int n) {
    pending -= n;
    if (pending == 0) {
      notifyAll();
    } else if (pending < 0) {
      log.error("PYTHON 읽기 권한 동기화 대기 수가 음수가 됐다(pending={}, 방금 {}건 완료) — 완료를 두 번 센 버그", pending, n);
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

  /**
   * 라이프사이클 시작 — 워커가 닫혀 있으면(앞선 stop) 새 워커로 바꿔 커밋 뒤 동기화를 다시 받아들인다(WD-629). 실행 중이면 아무것도 하지 않는다(진행 중
   * 작업·큐 유지).
   *
   * <p>옛 워커가 닫혔지만 아직 끝나지 않았어도(다른 스레드의 stop 이 비우는 중, 인터럽트를 무시하는 작업) 새 워커를 바로 만든다 — 새 워커의 첫 작업이 옛 워커의
   * 끝을 기다리므로 동기화가 동시에 둘 돌지 않는다({@link #replaceWorker}, WD-655). 교체가 {@link #lifecycleLock} 안이라 stop
   * 과 겹쳐도 「켜짐인데 닫힌 워커」나 「꺼짐인데 새 워커」가 남지 않는다.
   */
  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (worker.isShutdown()) {
        replaceWorker();
      }
    }
  }

  /**
   * 라이프사이클 종료 — DataSource 등 빈이 소멸되기 전에 큐에 남은 동기화를 제한 시간 안에 비운다. 이후 제출은 {@link #submit} 이 WARN 과 함께
   * 버린다(예외 없음). 다시 {@link #start()} 하면 새 워커로 받아들인다.
   */
  @Override
  public void stop() {
    shutdown();
  }

  /**
   * 라이프사이클 상태(켜짐)를 돌려준다 — 현재 워커가 닫히지 않았으면 켜짐이다. 옛 워커가 아직 끝나지 않았어도 start 뒤에는 true 다(WD-655):
   * SmartLifecycle 계약상 Spring 은 isRunning() 이 true 인 빈에만 stop() 을 부르므로, 옛 워커의 생사를 섞으면 stop 이
   * 건너뛰어진다.
   */
  @Override
  public boolean isRunning() {
    return !worker.isShutdown();
  }

  /**
   * 큐에 남은 동기화를 제한 시간 안에 비운다. 넘기면 남은 작업을 버리고 WARN(다음 기동 뒤 동기화가 회복). {@link #stop()} 이 정상 경로이고,
   * {@code @PreDestroy} 는 라이프사이클 stop 이 불리지 않은 경우(예: 컨텍스트 refresh 실패)를 위한 안전망이다. stop 과 @PreDestroy
   * 는 종료 스레드에서 차례로 돌고, ThreadPoolExecutor 의 shutdown·awaitTermination 은 스레드 안전·멱등이라 두 번 불려도 이미 종료된
   * 실행기에서는 곧바로 끝난다.
   *
   * <p>현재 워커 읽기와 닫기(shutdown — 막지 않는다)는 {@link #lifecycleLock} 안에서 한 번에 한다(WD-655) — stop 이 끝나면 늘
   * 꺼짐이고, 그 뒤 start 전에는 워커가 새로 생기지 않는다. 끝나기를 기다리는 awaitTermination 은 모니터 밖에서 해 submit 을 막지
   * 않는다(WD-629).
   */
  @PreDestroy
  void shutdown() {
    ThreadPoolExecutor w;
    synchronized (lifecycleLock) {
      w = worker;
      w.shutdown();
    }
    try {
      if (!w.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
        discardRemaining(w);
      }
    } catch (InterruptedException e) {
      discardRemaining(w);
      Thread.currentThread().interrupt();
    }
  }

  /**
   * 워커를 즉시 멈추고 돌지 못한 작업을 버린다(WD-629). shutdownNow 가 돌려준 작업은 실행되지 않으므로 작업 안의 finally(finishOne)가 불리지
   * 않는다 — 여기서 그 수만큼 pending 을 풀고 버림 건수로 센다. 빠뜨리면 pending 이 영구히 남아 {@link #awaitIdle} 이 늘 false 였다.
   * 실행 중이던 작업은 인터럽트로 끝나며 자기 finally 에서 한 번만 finishOne 한다(반환 목록에 없으므로 중복 계산 없음).
   *
   * <p>왜 이중 차감이 없는가: shutdownNow 가 돌려주는 것은 큐에서 꺼낸, 한 번도 시작되지 않은 작업이라 그 finally 는 영영 돌지 않는다. 종료와 겹친
   * execute 는 ThreadPoolExecutor 가 큐에서 도로 빼는 데 성공했을 때만 거부하고(→ submit 의 catch), 빼지 못했으면 이미 여기 반환 목록에
   * 들어갔거나 실행된 것이다 — 어느 작업이든 세 경로 중 정확히 하나로만 끝난다.
   */
  private void discardRemaining(ThreadPoolExecutor w) {
    List<Runnable> left = w.shutdownNow();
    int n = left.size();
    if (n > 0) {
      finish(n);
      log.warn("종료 시 PYTHON 읽기 권한 동기화 {}건을 비우지 못하고 버렸다(누적 {}건)", n, dropped.addAndGet(n));
    }
  }
}
