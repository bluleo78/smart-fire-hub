package com.smartfirehub.pipeline.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 워크스페이스(테넌트)별 파이프라인 동시 실행 관문(WD-439).
 *
 * <p>왜 필요한가: 실행기를 끈 구성(app.executor.enabled=false — 로컬·격리 스택)에서는 SQL 스텝이 api 의 테넌트별 파이프라인 연결 풀
 * ({@code TenantPipelineDataSourceRegistry}, 기본 2개)에서 돈다. 실행 스레드는 5~10개라 세 번째 실행은 RUNNING 으로 보이면서
 * 연결을 30초 기다리다 Hikari 영어 원문으로 실패했다. 그래서 한 테넌트에서 동시에 도는 실행 수를 연결 수만큼으로 묶고, 넘는 실행은 PENDING 그대로 이 관문의
 * 대기열에서 순서를 기다렸다가 자리가 나면 러너에 제출한다.
 *
 * <p>왜 스레드 안에서 기다리지 않는가: 대기를 {@code pipeline-exec} 스레드에서 하면 한 테넌트의 대기자가 코어 스레드를 다 차지해, 연결이 남는 다른
 * 테넌트의 실행까지 실행기 대기열에 갇힌다. 자리가 날 때만 제출하므로 스레드는 실제로 도는 실행만 쥔다.
 *
 * <p>상한: {@code app.pipeline.tenant-concurrency.max-running}. 0 이면 관문을 끈다(곧바로 제출 — 예전 동작). 따로 정하지
 * 않으면(-1) 실행기를 끈 구성은 테넌트 연결 풀 크기, 실행기를 켠 구성(운영)은 0 이다 — 운영의 SQL 은 Python 실행기의 테넌트 슬롯(기본 4개, 슬롯이 날
 * 때까지 기다림)에서 돌고 api 풀은 컬럼 조사에만 잠깐 쓰므로, 여기서 2개로 묶으면 지금보다 동시 실행이 줄어든다.
 *
 * <p>대기 한도: {@code app.pipeline.tenant-concurrency.max-wait}(기본 30분 — 증분 스텝 잠금 대기와 같은 값)를 넘긴 대기자는
 * 주기 점검이 대기열에서 빼고 실패 처리 콜백을 부른다.
 *
 * <p>범위: 이 JVM 안의 상한이다. 연결 풀도 JVM 마다 따로라 인스턴스마다 같은 상한이 맞다. 재시작하면 대기열이 사라지고, 남은 PENDING 행은 하트비트가 끊겨
 * 정리기가 「중단됨」으로 닫는다(제출만 하고 시작 못 한 실행과 같은 처리).
 */
@Slf4j
@Component
public class PipelineTenantAdmission {

  /** 대기 한도를 넘긴 실행의 실행·스텝 오류 문구 — 사용자에게 그대로 보인다. */
  public static final String WAIT_TIMEOUT_MESSAGE = "동시에 실행 중인 파이프라인이 많아 시작하지 못했습니다. 잠시 뒤 다시 실행하세요";

  private final int maxRunning;
  private final Duration maxWait;
  private final Clock clock;

  /** 테넌트별 지금 자리를 쥔 실행 수. */
  private final Map<Long, Integer> running = new HashMap<>();

  /** 자리를 쥔 실행 → 테넌트. 해제를 실행 id 로 받고, 자리를 쥐지 않은 실행의 해제를 무시하려고 둔다. */
  private final Map<Long, Long> admitted = new HashMap<>();

  /** 테넌트별 자리를 기다리는 실행(먼저 온 순서). */
  private final Map<Long, Deque<Waiter>> waiting = new HashMap<>();

  /**
   * 기다리는 실행 하나.
   *
   * @param dispatch 자리를 받으면 부르는 제출 — 러너에 실행을 넘긴다
   * @param onTimeout 대기 한도를 넘기면 부르는 처리 — 실행을 실패로 닫는다
   */
  private record Waiter(
      long executionId, Instant enqueuedAt, Runnable dispatch, Runnable onTimeout) {}

  @Autowired
  public PipelineTenantAdmission(
      @Value("${app.pipeline.tenant-concurrency.max-running:-1}") int configuredMaxRunning,
      @Value("${app.pipeline.tenant-pool.max-size:2}") int tenantPoolMaxSize,
      @Value("${app.executor.enabled:false}") boolean executorEnabled,
      @Value("${app.pipeline.tenant-concurrency.max-wait:PT30M}") Duration maxWait) {
    this(
        configuredMaxRunning >= 0
            ? configuredMaxRunning
            : (executorEnabled ? 0 : tenantPoolMaxSize),
        maxWait,
        Clock.systemUTC());
  }

  /** 테스트용 — 상한·한도·시계를 직접 정한다. */
  PipelineTenantAdmission(int maxRunning, Duration maxWait, Clock clock) {
    this.maxRunning = maxRunning;
    this.maxWait = maxWait;
    this.clock = clock;
    log.info("파이프라인 테넌트별 동시 실행 상한: {} (0 = 제한 없음), 대기 한도: {}", maxRunning, maxWait);
  }

  /**
   * 실행을 들여보낸다. 자리가 있으면 곧바로 {@code dispatch} 를 부르고, 없으면 대기열에 넣고 돌아온다(실행은 PENDING 그대로).
   *
   * <p>곧바로 부른 {@code dispatch} 가 예외를 던지면 자리를 돌려주고 예외를 그대로 던진다 — 호출자(요청 스레드)가 예전처럼 처리한다.
   *
   * <p>그 테넌트에 이미 기다리는 실행이 있으면 자리가 비어 보여도 뒤에 줄을 선다 — 자리를 돌려준 순간과 대기자를 제출하는 순간 사이에 새 실행이 끼어들면 먼저 온
   * 대기자가 계속 밀려 대기 한도에 걸린다.
   *
   * @return 곧바로 제출했으면 true, 대기열에 넣었으면 false
   */
  public boolean admit(long tenantId, long executionId, Runnable dispatch, Runnable onTimeout) {
    if (maxRunning <= 0) {
      dispatch.run();
      return true;
    }
    synchronized (this) {
      Deque<Waiter> queue = waiting.get(tenantId);
      if (running.getOrDefault(tenantId, 0) >= maxRunning || (queue != null && !queue.isEmpty())) {
        waiting
            .computeIfAbsent(tenantId, t -> new ArrayDeque<>())
            .addLast(new Waiter(executionId, clock.instant(), dispatch, onTimeout));
        log.info(
            "파이프라인 실행이 자리를 기다립니다: execution={}, tenant={}, 대기 {}개",
            executionId,
            tenantId,
            waiting.get(tenantId).size());
        return false;
      }
      take(tenantId, executionId);
    }
    try {
      dispatch.run();
    } catch (RuntimeException | Error e) {
      release(executionId);
      throw e;
    }
    return true;
  }

  /**
   * 실행이 이 JVM 을 떠날 때(러너 종료·제출 실패) 자리를 돌려주고, 그 테넌트에서 기다리던 다음 실행을 제출한다. 자리를 쥐지 않은 실행이면 아무것도 하지 않는다(여러
   * 번 불려도 된다).
   */
  public void release(long executionId) {
    Long tenantId;
    synchronized (this) {
      tenantId = untake(executionId);
      if (tenantId == null) {
        return;
      }
    }
    dispatchNext(tenantId);
  }

  /** 기다리던 실행을 대기열에서 뺀다(취소된 실행 등). 빼었으면 true. */
  public synchronized boolean forget(long executionId) {
    for (Iterator<Deque<Waiter>> it = waiting.values().iterator(); it.hasNext(); ) {
      Deque<Waiter> queue = it.next();
      if (queue.removeIf(w -> w.executionId() == executionId)) {
        if (queue.isEmpty()) {
          it.remove();
        }
        return true;
      }
    }
    return false;
  }

  /** 실행이 지금 대기열에 있는지 — 테스트·진단용. */
  synchronized boolean isWaiting(long executionId) {
    return waiting.values().stream()
        .anyMatch(q -> q.stream().anyMatch(w -> w.executionId() == executionId));
  }

  /**
   * 대기 한도를 넘긴 실행을 대기열에서 빼고 실패 처리한 뒤, 자리가 비어 있는 테넌트의 대기자를 다시 제출해 본다. 정리기와 같은 주기로 돈다 — 한도(분 단위)에 비해
   * 충분히 촘촘하다. 다시 제출하는 까닭: 실행기가 포화돼 제출이 거절되면 대기자는 대기열 앞에 그대로 남는데, 그 테넌트에 도는 실행이 없으면 자리를 돌려줄 러너도 없어 이
   * 주기가 아니면 다시 시도할 계기가 없다.
   *
   * @return 실패 처리한 수
   */
  @Scheduled(
      fixedDelayString = "${app.pipeline.tenant-concurrency.check-interval:PT30S}",
      initialDelayString = "${app.pipeline.tenant-concurrency.check-interval:PT30S}")
  public int expireWaiting() {
    if (maxRunning <= 0) {
      return 0;
    }
    Instant deadline = clock.instant().minus(maxWait);
    List<Waiter> expired = new ArrayList<>();
    synchronized (this) {
      for (Iterator<Deque<Waiter>> queues = waiting.values().iterator(); queues.hasNext(); ) {
        Deque<Waiter> queue = queues.next();
        for (Iterator<Waiter> it = queue.iterator(); it.hasNext(); ) {
          Waiter w = it.next();
          if (!w.enqueuedAt().isAfter(deadline)) {
            it.remove();
            expired.add(w);
          }
        }
        if (queue.isEmpty()) {
          queues.remove();
        }
      }
    }
    // 콜백은 DB 쓰기·이벤트 발행을 하므로 잠금 밖에서 부른다. 하나가 실패해도 나머지는 처리한다.
    for (Waiter w : expired) {
      try {
        w.onTimeout().run();
      } catch (RuntimeException e) {
        log.error("대기 한도를 넘긴 파이프라인 실행을 닫지 못했습니다: execution={}", w.executionId(), e);
      }
    }
    List<Long> tenants;
    synchronized (this) {
      tenants = new ArrayList<>(waiting.keySet());
    }
    for (Long tenantId : tenants) {
      dispatchNext(tenantId);
    }
    return expired.size();
  }

  /**
   * 자리가 있는 동안 그 테넌트의 대기자를 먼저 온 순서대로 제출한다. 제출이 실패하면(공용 실행기 포화 — {@code TaskRejectedException}) 그
   * 대기자를 대기열 맨 앞에 되돌리고 자리를 돌려받은 뒤 멈춘다 — 같은 포화에 뒤 대기자까지 줄줄이 실패시키지 않고, 다음 해제나 주기 점검({@link
   * #expireWaiting})에서 다시 시도한다. 그래도 끝내 못 들어가면 대기 한도에서 한국어 안내로 닫힌다.
   */
  private void dispatchNext(long tenantId) {
    while (true) {
      Waiter next;
      synchronized (this) {
        Deque<Waiter> queue = waiting.get(tenantId);
        if (queue == null || queue.isEmpty() || running.getOrDefault(tenantId, 0) >= maxRunning) {
          if (queue != null && queue.isEmpty()) {
            waiting.remove(tenantId);
          }
          return;
        }
        next = queue.pollFirst();
        take(tenantId, next.executionId());
      }
      try {
        next.dispatch().run();
      } catch (RuntimeException | Error e) {
        synchronized (this) {
          untake(next.executionId());
          waiting.computeIfAbsent(tenantId, t -> new ArrayDeque<>()).addFirst(next);
        }
        log.warn(
            "기다리던 파이프라인 실행을 러너에 넘기지 못해 대기열에 되돌렸습니다: execution={}, tenant={}",
            next.executionId(),
            tenantId,
            e);
        if (e instanceof Error error) {
          throw error;
        }
        return;
      }
    }
  }

  private void take(long tenantId, long executionId) {
    running.merge(tenantId, 1, Integer::sum);
    admitted.put(executionId, tenantId);
  }

  /** {@link #take} 를 되돌린다. 자리를 쥐지 않은 실행이면 null. 호출자가 잠금을 쥔다. */
  private Long untake(long executionId) {
    Long tenantId = admitted.remove(executionId);
    if (tenantId != null) {
      running.merge(tenantId, -1, (a, b) -> a + b <= 0 ? null : a + b);
    }
    return tenantId;
  }
}
