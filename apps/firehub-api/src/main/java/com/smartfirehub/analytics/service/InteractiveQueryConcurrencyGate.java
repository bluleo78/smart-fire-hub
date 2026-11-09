package com.smartfirehub.analytics.service;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.pipeline.service.StoreTransactions;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 화면 쿼리(차트 데이터·대시보드 일괄 데이터·데이터셋 SQL 편집기) 동시 실행 상한(WD-599) — 자리가 없으면 잠깐 기다린다.
 *
 * <p><b>왜 필요한가.</b> 대시보드 화면은 위젯마다 {@code /charts/{id}/data} 를 병렬로(30초마다) 부르고, 각 요청은 쿼리 시간 내내 공용
 * Hikari 풀 커넥션을 쥔다. 상한이 없으면 위젯 많은 대시보드 하나가 풀을 다 써 목록·로그인 같은 무관한 요청이 풀 대기에 걸린다.
 *
 * <p><b>왜 거절하지 않고 기다리는가.</b> 위젯 병렬 로딩은 원래 몰리는 것이 정상이라, 즉시 429 로 거절하면 위젯 일부가 매번 깨진다. 대신 차례를 기다려(최대
 * wait-ms) 위젯이 줄 서서 차례로 뜨게 한다. 그래도 차례가 오지 않으면 429 {@code QUERY_CONCURRENCY_LIMIT}.
 *
 * <p><b>언제·어디서 얻는가 — {@link #runGated}.</b> 허가는 반드시 트랜잭션·커넥션 <b>밖</b>에서 기다린다 — 기다리는 동안 커넥션을 쥐면 기다림
 * 자체가 풀을 고갈시킨다. 그리고 「실제로 쿼리를 실행한다」가 정해진 <b>뒤</b>에만 얻는다(WD-625): 차트·대시보드는 조회 트랜잭션(없으면 404)·판정(거부면
 * 200 denied)·결과 캐시 확인을 먼저 끝내 커넥션을 반납하고, 실행할 쿼리가 있을 때만 {@code runGated} 로 「허가(트랜잭션 밖) → 실행 구간만 새
 * 트랜잭션(PG 는 readOnly, Tibero 는 트랜잭션 없음) → 반납」을 한 번에 한다. 허가 전 판정은 거부를 빨리 끝내기 위한 앞 판정이고, 실행하는 토큰은
 * 호출자가 {@code work} 안(허가를 얻은 뒤)에서 새 자격으로 다시 판정한 토큰이다(WD-684 — 기다리는 동안 바뀐 등급 반영). 진입부에서 얻으면 게이트가 찼을
 * 때 쿼리를 돌리지도 않을 요청(없는 차트·거부 위젯·전부 캐시된 대시보드)까지 wait-ms 동안 줄 서다 429 를 받는다. 실행 구간을 새 readOnly 트랜잭션으로
 * 여는 이유: 실행 서비스({@code AnalyticsQueryExecutionService.execute})는 자기 {@code @Transactional}(쓰기 가능)을
 * 열므로, 바깥 readOnly 트랜잭션 없이 부르면 차트 저장 쿼리(readOnly=false 로 실행)가 쓰기 가능한 커넥션에서 돈다. 판정·자격 계산 리포지토리는 각자
 * readOnly 트랜잭션으로 테넌트 GUC 를 세우므로 바깥 트랜잭션 없이 불러도 된다. 데이터셋 SQL 편집기도 같은 규칙이다(WD-638): 존재
 * 확인(404)·판정(400/403)을 먼저 끝낸 뒤 {@link #acquireUnlessTibero} 로 허가를 얻고, 재판정(대기 중 바뀐 등급 반영)·실행·이력 저장을
 * 쓰기 트랜잭션 하나로 연다(쓰기 트랜잭션이라 {@code runGated} 를 쓰지 않는다).
 *
 * <p><b>WD-553 게이트({@link AnalyticsQueryConcurrencyGate})와의 차이.</b> 그쪽(애드혹·저장 쿼리·내보내기)은 바깥 트랜잭션이 이미
 * 커넥션을 쥔 채 오므로 기다리면 안 되어 즉시 거절한다. 이 게이트는 커넥션을 쥐기 전에 얻으므로 기다려도 풀을 쥐지 않는다. 두 게이트는 몫을 따로 센다(합쳐 최대 8 동시
 * 실행 — 풀 10 중 2 여유).
 *
 * <p><b>Tibero 테넌트는 지나지 않는다.</b> Tibero 실행은 db-executor 를 거쳐 PG 풀 커넥션을 쥐지 않는다(WD-207) — WD-553 게이트가
 * Tibero 를 빼는 것과 같은 이유로 허가를 얻지 않는다. 이 분기는 {@link #acquireUnlessTibero} 한 곳에만 둔다.
 *
 * <p><b>구현.</b> 전체·사용자 두 상한을 한 번에 원자적으로 판정하려고 세마포어 둘이 아니라 {@link ReentrantLock} 하나를 쓴다. 두 몫을 모두 얻을
 * 수 있을 때만 카운터를 올리므로, 기다리다 시간 초과·인터럽트로 나가도 카운터가 새지 않는다. 각 상한은 0 이하면 따로 꺼진다.
 *
 * <p><b>도착 순서(FIFO) 입장.</b> 잠금의 공정성만으로는 부족하다 — 반납 직후 막 도착한 요청이 오래 기다린 요청보다 먼저 자리를 차지해, 가장 먼저 온 요청이
 * 429 로 끝나고 뒤에 온 요청은 성공할 수 있다. 그래서 대기자를 도착 순서(번호표)로 줄 세우고, 입장할 수 있는 사람은 늘 {@link #firstAdmissible}
 * — 줄 앞에서부터 <b>지금 자리를 얻을 수 있는 첫 대기자</b> — 하나뿐이다. 사용자 상한에 걸린 앞선 대기자는 어차피 지금 못 들어가므로 건너뛴다 — 한 사용자의
 * 상한이 다른 사용자를 막는 줄 앞 막힘(head-of-line blocking)이 없다. 줄이 비어 있고 자리가 있으면 줄에 서지 않고 바로 들어간다.
 *
 * <p><b>깨우기 — 들어갈 사람 하나만.</b> 대기자마다 자기 {@link Condition} 을 갖고, 상태가 바뀔 때(반납, 대기자의 입장·시간 초과·인터럽트로 줄에서
 * 빠짐) 입장 판정과 같은 {@link #firstAdmissible} 이 고른 대기자 하나만 깨운다 — 깨운 사람이 곧 들어갈 사람이다. 입장한 대기자는 나가면서 다음 사람을
 * 깨우므로 반납이 여러 번 겹쳐도 연쇄로 모두 깨어난다. 공용 조건 하나에 {@code signal()} 만 쓰면 안 된다 — 조건 큐의 맨 앞(가장 오래 기다린 사람)이
 * 사용자 상한에 걸린 대기자면 그만 깨어 다시 잠들고 신호가 사라져, 들어갈 수 있는 다른 사용자가 시간 초과(429)까지 잔다.
 */
@Component
public class InteractiveQueryConcurrencyGate {

  /** 기다렸지만 차례가 오지 않았을 때(또는 기다리다 인터럽트됐을 때)의 안내 — 웹은 응답 message 를 그대로 보여 준다. */
  static final String TIMEOUT_MESSAGE = "쿼리가 몰려 기다렸지만 차례가 오지 않았습니다 — 잠시 뒤 다시 시도하세요.";

  /** 두 상한이 모두 꺼졌거나 Tibero 테넌트일 때 돌려주는 허가 — 반납할 것이 없다. */
  private static final AnalyticsQueryConcurrencyGate.Permit NOOP = () -> {};

  /** 전체 상한(0 이하 = 끔). */
  private final int maxTotal;

  /** 사용자당 상한(0 이하 = 끔). */
  private final int maxPerUser;

  /** 최대 대기 시간(나노초, 0 이하 = 기다리지 않음). */
  private final long waitNanos;

  /** 카운터·대기열 보호와 대기용 공정 잠금. 입장 순서 자체는 {@link #waiters} 가 정한다. */
  private final ReentrantLock lock = new ReentrantLock(true);

  /** 전체 실행 중 개수. {@link #lock} 아래에서만 읽고 쓴다. */
  private int active;

  /** 사용자별 실행 중 개수 — 0 이 되면 항목을 지운다. {@link #lock} 아래에서만 읽고 쓴다. */
  private final Map<Long, Integer> perUser = new HashMap<>();

  /** 대기자 한 명 — 사용자 id 와 그 대기자만 깨우는 조건. 동일성(==)으로 비교한다. */
  private record Waiter(long userId, Condition turn) {}

  /** 도착 순서 대기열 — 번호표 → 대기자(삽입 순서 = 도착 순서). {@link #lock} 아래에서만 읽고 쓴다. */
  private final LinkedHashMap<Long, Waiter> waiters = new LinkedHashMap<>();

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
   * 허가를 얻어(트랜잭션 밖에서 기다린다) 실행 구간만 새 트랜잭션으로 열어 {@code work} 를 돌리고 반납한다 — PG 는 {@code tx}(readOnly
   * 템플릿) 안, Tibero 는 허가 없이 트랜잭션 밖({@link StoreTransactions#executeOnly}). 호출자는 조회 트랜잭션을 닫은 뒤, 실행이
   * 정해졌을 때만 부른다(클래스 설명 참고). {@code work} 가 어떻게 끝나든(예외 포함) 허가를 반납한다.
   *
   * @throws CodedApiException 429 {@code QUERY_CONCURRENCY_LIMIT} — {@link #acquire} 와 같다(PG 에서만)
   */
  public <T> T runGated(
      PipelineDataSchema schema, TransactionTemplate tx, long userId, Supplier<T> work) {
    try (AnalyticsQueryConcurrencyGate.Permit ignored = acquireUnlessTibero(schema, userId)) {
      return StoreTransactions.executeOnly(schema, tx, work);
    }
  }

  /**
   * PG 테넌트면 {@link #acquire} 로 허가를 얻고, Tibero 테넌트면 반납할 것 없는 허가를 바로 돌려준다. 실행 구간을 호출자가 직접 정하는 경로(데이터셋
   * SQL 편집기)가 쓰고, 나머지는 {@link #runGated} 를 쓴다.
   *
   * <p>테넌트 저장소 판정({@link PipelineDataSchema})을 생성자로 주입하지 않고 인자로 받는다 — 게이트를 {@code new} 로 직접 만드는 단위
   * 테스트·테스트 대역의 생성자를 바꾸지 않으려는 선택이며, 호출하는 서비스는 이미 그 판정을 갖고 있다({@code StoreTransactions} 와 같은 이유).
   */
  public AnalyticsQueryConcurrencyGate.Permit acquireUnlessTibero(
      PipelineDataSchema schema, long userId) {
    return schema.isTibero() ? NOOP : acquire(userId);
  }

  /**
   * 전체·사용자 몫을 함께 얻을 수 있을 때까지 도착 순서대로 최대 wait-ms 기다려 허가를 얻는다. 반드시 트랜잭션·커넥션 밖에서 불러야 한다. 허가 타입은 WD-553
   * 게이트와 같은 {@link AnalyticsQueryConcurrencyGate.Permit}(닫으면 한 번만 반납)이다.
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
    try {
      // 빠른 길: 기다리는 사람이 없고 자리가 있으면 줄에 서지 않고 바로 들어간다(대기자가 없으니 깨울 사람도 없다).
      if (waiters.isEmpty() && hasRoom(userId)) {
        admit(userId);
      } else {
        awaitTurn(userId, deadline);
      }
    } finally {
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

  /** 카운터를 올린다 — 두 몫을 모두 얻을 수 있음을 확인한 뒤에만 부른다. {@link #lock} 아래에서 부른다. */
  private void admit(long userId) {
    active++;
    perUser.merge(userId, 1, Integer::sum);
  }

  /**
   * 줄에 서서 자기가 {@link #firstAdmissible} 이 될 때까지(=두 상한에 자리가 있고 앞선 대기자 중 들어갈 수 있는 사람이 없을 때까지) 기다렸다가
   * 입장한다. {@link #lock} 아래에서 부른다.
   *
   * @throws CodedApiException 429 — 시간 초과·인터럽트. 아직 카운터를 올리지 않았으므로 되돌릴 것이 없다.
   */
  private void awaitTurn(long userId, long deadline) {
    long ticket = nextTicket++;
    Waiter me = new Waiter(userId, lock.newCondition());
    waiters.put(ticket, me);
    try {
      while (firstAdmissible() != me) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new CodedApiException(
              HttpStatus.TOO_MANY_REQUESTS,
              AnalyticsQueryConcurrencyGate.LIMIT_CODE,
              TIMEOUT_MESSAGE);
        }
        try {
          me.turn().awaitNanos(remaining);
        } catch (InterruptedException e) {
          throw interrupted();
        }
      }
      admit(userId);
    } finally {
      // 입장·시간 초과·인터럽트 모두 줄에서 빠진다 — 뒤 대기자가 더는 이 번호표에 양보하지 않으므로 이제 들어갈 사람을 깨운다. 입장이면 카운터를 올린 뒤라,
      // 반납이 겹쳐 남은 자리만 다음 사람에게 잇는 연쇄 깨우기가 된다.
      waiters.remove(ticket);
      signalFirstAdmissible();
    }
  }

  /** 전체·사용자 몫이 모두 남았는지(꺼진 상한은 늘 남은 것으로 본다). {@link #lock} 아래에서 부른다. */
  private boolean hasRoom(long userId) {
    boolean totalOk = maxTotal <= 0 || active < maxTotal;
    boolean userOk = maxPerUser <= 0 || perUser.getOrDefault(userId, 0) < maxPerUser;
    return totalOk && userOk;
  }

  /**
   * 줄 앞에서부터 지금 자리를 얻을 수 있는 첫 대기자(없으면 null). 입장 판정({@link #awaitTurn})과 깨우기({@link
   * #signalFirstAdmissible})가 이 하나를 함께 써서, 깨운 사람과 들어갈 수 있는 사람이 구조적으로 같다. 사용자 상한에 걸린 대기자는 건너뛴다(줄 앞
   * 막힘 방지). {@link #lock} 아래에서 부른다.
   */
  private Waiter firstAdmissible() {
    for (Waiter w : waiters.values()) {
      if (hasRoom(w.userId())) {
        return w;
      }
    }
    return null;
  }

  /** 들어갈 수 있는 첫 대기자 하나만 깨운다(없으면 아무도 — 다음 상태 변화가 다시 부른다). {@link #lock} 아래에서 부른다. */
  private void signalFirstAdmissible() {
    Waiter next = firstAdmissible();
    if (next != null) {
      next.turn().signal();
    }
  }

  /** 몫을 돌려주고 그 자리로 들어갈 수 있는 첫 대기자를 깨운다. */
  private void release(long userId) {
    lock.lock();
    try {
      active--;
      perUser.computeIfPresent(userId, (k, v) -> v <= 1 ? null : v - 1);
      signalFirstAdmissible();
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

  /** 줄 서서 기다리는 대기자 수 — 테스트가 sleep 대신 폴링해 「기다리는 중」을 확인한다. */
  int waitingCount() {
    lock.lock();
    try {
      return waiters.size();
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
