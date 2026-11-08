package com.smartfirehub.analytics.service;

import com.smartfirehub.global.exception.CodedApiException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 화면 쿼리(차트 데이터·대시보드 일괄 데이터·데이터셋 SQL 편집기) 동시 실행 상한(WD-599) — 자리가 없으면 잠깐 기다린다.
 *
 * <p><b>왜 필요한가.</b> 대시보드 화면은 위젯마다 {@code /charts/{id}/data} 를 병렬로(30초마다) 부르고, 각 요청은 쿼리 시간 내내 공용
 * Hikari 풀 커넥션을 쥔다. 상한이 없으면 위젯 많은 대시보드 하나가 풀을 다 써 목록·로그인 같은 무관한 요청이 풀 대기에 걸린다.
 *
 * <p><b>왜 거절하지 않고 기다리는가.</b> 위젯 병렬 로딩은 원래 몰리는 것이 정상이라, 즉시 429 로 거절하면 위젯 일부가 매번 깨진다. 대신 차례를 기다려(최대
 * wait-ms) 위젯이 줄 서서 차례로 뜨게 한다. 그래도 차례가 오지 않으면 429 {@code QUERY_CONCURRENCY_LIMIT}.
 *
 * <p><b>왜 커넥션보다 먼저 얻는가.</b> 기다리는 동안 커넥션을 쥐면 기다림 자체가 풀을 고갈시킨다. 그래서 호출자는 트랜잭션·커넥션을 열기 전, 서비스 진입부에서 이
 * 허가를 얻는다(바깥 트랜잭션이 없는 경로에만 적용).
 *
 * <p><b>WD-553 게이트({@link AnalyticsQueryConcurrencyGate})와의 차이.</b> 그쪽(애드혹·저장 쿼리·내보내기)은 바깥 트랜잭션이 이미
 * 커넥션을 쥔 채 오므로 기다리면 안 되어 즉시 거절한다. 이 게이트는 커넥션을 쥐기 전에 얻으므로 기다려도 풀을 쥐지 않는다. 두 게이트는 몫을 따로 센다(합쳐 최대 8 동시
 * 실행 — 풀 10 중 2 여유).
 *
 * <p><b>Tibero 테넌트는 지나지 않는다.</b> Tibero 실행은 db-executor 를 거쳐 PG 풀 커넥션을 쥐지 않는다(WD-207) — WD-553 게이트가
 * Tibero 를 빼는 것과 같은 이유로, 호출자가 {@code PipelineDataSchema.isTibero()} 면 허가를 얻지 않는다.
 *
 * <p><b>구현.</b> 전체·사용자 두 상한을 한 번에 원자적으로 판정하려고 세마포어 둘이 아니라 {@link ReentrantLock} 하나와 {@link
 * Condition} 을 쓴다. 두 몫을 모두 얻을 수 있을 때만 카운터를 올리므로, 기다리다 시간 초과·인터럽트로 나가도 카운터가 새지 않는다.
 *
 * <p><b>도착 순서(FIFO) 입장.</b> 잠금의 공정성만으로는 부족하다 — 반납 직후 막 도착한 요청이 오래 기다린 요청보다 먼저 자리를 차지해, 가장 먼저 온 요청이
 * 429 로 끝나고 뒤에 온 요청은 성공할 수 있다. 그래서 대기자를 도착 순서(번호표 → 사용자)로 줄 세우고, 앞선 대기자 가운데 <b>지금 자리를 얻을 수 있는
 * 사람</b>이 있으면 양보한다. 사용자 상한에 걸린 앞선 대기자는 어차피 지금 못 들어가므로 건너뛴다 — 한 사용자의 상한이 다른 사용자를 막는 줄 앞
 * 막힘(head-of-line blocking)이 없다. 번호표는 성공·시간 초과·인터럽트 어느 쪽으로 나가든 지우고 {@code signalAll} 로 남은 대기자가 다시
 * 판정하게 한다. 각 상한은 0 이하면 따로 꺼진다.
 */
@Component
public class InteractiveQueryConcurrencyGate {

  /** 기다렸지만 차례가 오지 않았을 때(또는 기다리다 인터럽트됐을 때)의 안내 — 웹은 응답 message 를 그대로 보여 준다. */
  static final String TIMEOUT_MESSAGE = "쿼리가 몰려 기다렸지만 차례가 오지 않았습니다 — 잠시 뒤 다시 시도하세요.";

  /** 두 상한이 모두 꺼졌을 때 돌려주는 허가 — 반납할 것이 없다. */
  private static final AnalyticsQueryConcurrencyGate.Permit NOOP = () -> {};

  /** 전체 상한(0 이하 = 끔). */
  private final int maxTotal;

  /** 사용자당 상한(0 이하 = 끔). */
  private final int maxPerUser;

  /** 최대 대기 시간(나노초, 0 이하 = 기다리지 않음). */
  private final long waitNanos;

  /** 카운터·대기열 보호와 대기용 공정 잠금. 입장 순서 자체는 {@link #waiters} 가 정한다. */
  private final ReentrantLock lock = new ReentrantLock(true);

  /** 허가가 반납됐음을 대기자에게 알리는 조건. */
  private final Condition released = lock.newCondition();

  /** 전체 실행 중 개수. {@link #lock} 아래에서만 읽고 쓴다. */
  private int active;

  /** 사용자별 실행 중 개수 — 0 이 되면 항목을 지운다. {@link #lock} 아래에서만 읽고 쓴다. */
  private final Map<Long, Integer> perUser = new HashMap<>();

  /** 도착 순서 대기열 — 번호표 → 사용자 id(삽입 순서 = 도착 순서). {@link #lock} 아래에서만 읽고 쓴다. */
  private final LinkedHashMap<Long, Long> waiters = new LinkedHashMap<>();

  /** 다음 번호표. {@link #lock} 아래에서만 증가한다. */
  private long nextTicket;

  @Autowired
  public InteractiveQueryConcurrencyGate(
      @Value("${app.analytics.interactive-query-concurrency.max-total:4}") int maxTotal,
      @Value("${app.analytics.interactive-query-concurrency.max-per-user:3}") int maxPerUser,
      @Value("${app.analytics.interactive-query-concurrency.wait-ms:10000}") long waitMs) {
    this.maxTotal = maxTotal;
    this.maxPerUser = maxPerUser;
    this.waitNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMs));
  }

  /**
   * 전체·사용자 몫을 함께 얻을 수 있을 때까지 도착 순서대로 최대 wait-ms 기다려 허가를 얻는다. 반드시 트랜잭션·커넥션을 열기 전에 불러야 한다. 허가 타입은
   * WD-553 게이트와 같은 {@link AnalyticsQueryConcurrencyGate.Permit}(닫으면 한 번만 반납)이다.
   *
   * @throws CodedApiException 429 {@code QUERY_CONCURRENCY_LIMIT} — 기다려도 차례가 오지 않았거나 기다리다
   *     인터럽트됨(인터럽트 플래그는 복원한다)
   */
  public AnalyticsQueryConcurrencyGate.Permit acquire(long userId) {
    if (maxTotal <= 0 && maxPerUser <= 0) {
      return NOOP;
    }
    long deadline = System.nanoTime() + waitNanos;
    try {
      lock.lockInterruptibly();
    } catch (InterruptedException e) {
      throw interrupted();
    }
    long ticket = nextTicket++;
    waiters.put(ticket, userId);
    try {
      // 두 상한을 한 번에 본다 — 둘 다 자리가 있을 때만 통과하므로 한쪽만 올리고 되돌리는 일이 없다.
      // 자리가 있어도 앞선 대기자 중 지금 들어갈 수 있는 사람이 있으면 양보한다(도착 순서).
      while (!hasRoom(userId) || earlierWaiterAdmissible(ticket)) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new CodedApiException(
              HttpStatus.TOO_MANY_REQUESTS,
              AnalyticsQueryConcurrencyGate.LIMIT_CODE,
              TIMEOUT_MESSAGE);
        }
        try {
          released.awaitNanos(remaining);
        } catch (InterruptedException e) {
          // 아직 카운터를 올리지 않았으므로 되돌릴 것이 없다.
          throw interrupted();
        }
      }
      active++;
      perUser.merge(userId, 1, Integer::sum);
    } finally {
      // 성공·시간 초과·인터럽트 모두 줄에서 빠진다 — 뒤 대기자가 더는 이 번호표에 양보하지 않도록 깨워 다시 판정하게 한다.
      waiters.remove(ticket);
      released.signalAll();
      lock.unlock();
    }
    AtomicBoolean done = new AtomicBoolean();
    return () -> {
      // 한 번만 반납 — 중복 close 가 다른 실행의 몫을 빼앗지 않게.
      if (done.compareAndSet(false, true)) {
        release(userId);
      }
    };
  }

  /** 전체·사용자 몫이 모두 남았는지(꺼진 상한은 늘 남은 것으로 본다). {@link #lock} 아래에서 부른다. */
  private boolean hasRoom(long userId) {
    boolean totalOk = maxTotal <= 0 || active < maxTotal;
    boolean userOk = maxPerUser <= 0 || perUser.getOrDefault(userId, 0) < maxPerUser;
    return totalOk && userOk;
  }

  /**
   * 이 번호표보다 앞선 대기자 가운데 지금 자리를 얻을 수 있는 사람이 있는지. 사용자 상한에 걸린 앞선 대기자는 건너뛴다(줄 앞 막힘 방지). {@link #lock}
   * 아래에서 부른다.
   */
  private boolean earlierWaiterAdmissible(long ticket) {
    for (Map.Entry<Long, Long> w : waiters.entrySet()) {
      if (w.getKey() >= ticket) {
        return false; // 삽입 순서 = 번호표 순서 — 여기부터는 뒤 대기자다.
      }
      if (hasRoom(w.getValue())) {
        return true;
      }
    }
    return false;
  }

  /** 몫을 돌려주고 대기자를 모두 깨운다 — 사용자 상한·전체 상한 대기자가 섞여 있어 각자 다시 판정하게 한다. */
  private void release(long userId) {
    lock.lock();
    try {
      active--;
      perUser.computeIfPresent(userId, (k, v) -> v <= 1 ? null : v - 1);
      released.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /** 인터럽트 플래그를 복원하고 같은 429 로 알린다 — 요청 스레드 종료(서버 정지 등)가 원인이라 사용자에게는 재시도 안내가 맞다. */
  private static CodedApiException interrupted() {
    Thread.currentThread().interrupt();
    return new CodedApiException(
        HttpStatus.TOO_MANY_REQUESTS, AnalyticsQueryConcurrencyGate.LIMIT_CODE, TIMEOUT_MESSAGE);
  }

  /** 전체 실행 중 개수(상한이 꺼져 있어도 센다, 두 상한 모두 꺼지면 0) — 테스트·진단용. */
  int activeTotal() {
    lock.lock();
    try {
      return active;
    } finally {
      lock.unlock();
    }
  }

  /** 사용자의 실행 중 개수 — 테스트·진단용. */
  int activeFor(long userId) {
    lock.lock();
    try {
      return perUser.getOrDefault(userId, 0);
    } finally {
      lock.unlock();
    }
  }

  /** 카운터가 남아 있는 사용자 id — 테스트·진단용(0 이 된 사용자는 없어야 한다). */
  Set<Long> trackedUsers() {
    lock.lock();
    try {
      return Set.copyOf(perUser.keySet());
    } finally {
      lock.unlock();
    }
  }
}
