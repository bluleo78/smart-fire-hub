package com.smartfirehub.analytics.service;

import com.smartfirehub.global.exception.CodedApiException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 분석 쿼리 동시 실행 상한(WD-553) — JVM 안에서 전체·사용자당 동시 실행 수를 센다.
 *
 * <p><b>왜 필요한가.</b> 분석 쿼리(애드혹·저장 쿼리 실행·결과 내보내기 재실행)는 앱 공용 Hikari 풀에서 쿼리 시간 내내 커넥션을 쥔다. 상한이 없으면 동시
 * 실행 몇 건이 풀을 독점해 무관한 요청(목록·로그인)이 풀 대기에 걸리고 일부는 500 이 된다. 전체 상한을 풀 크기보다 작게 두어 핵심 요청 몫을 남긴다.
 *
 * <p><b>기다리지 않고 즉시 거절한다(tryAcquire).</b> 저장 쿼리 실행은 바깥 트랜잭션이 이미 커넥션을 쥔 상태로 여기 온다 — 허가를 기다리면 기다리는 동안
 * 풀을 쥐어 같은 고갈이 되풀이된다. 초과면 429 {@code QUERY_CONCURRENCY_LIMIT}.
 *
 * <p><b>적용 범위.</b> 관문(GuardedSqlExecutor)의 PG 실행 구간에서 애드혹·저장 쿼리·내보내기 경로만 이 게이트를 지난다. 차트·대시보드는 위젯을
 * 병렬로 불러오므로 지나지 않는다(429 로 깨지지 않게) — 대신 트랜잭션 밖에서 차례를 기다리는 {@link
 * InteractiveQueryConcurrencyGate}(WD-599)를 실행이 정해진 뒤에 지난다(언제·어디서는 그 클래스 설명). 각 상한은 0 이하면 따로 꺼진다.
 */
@Component
public class AnalyticsQueryConcurrencyGate {

  /** 거절 코드 — 웹·ai-agent 는 응답 message 를 그대로 보여 준다. */
  public static final String LIMIT_CODE = "QUERY_CONCURRENCY_LIMIT";

  static final String TOTAL_LIMIT_MESSAGE = "동시에 실행 중인 쿼리가 많습니다 — 실행 중인 쿼리가 끝난 뒤 다시 시도하세요.";
  static final String USER_LIMIT_MESSAGE = "이미 실행 중인 내 쿼리가 많습니다 — 실행 중인 쿼리가 끝난 뒤 다시 시도하세요.";

  /** 전체 상한 세마포어. 전체 상한이 꺼져 있으면 null. */
  private final Semaphore total;

  /** 전체 상한(0 이하 = 끔) — 사용 중 개수 계산용. */
  private final int maxTotal;

  /** 사용자당 상한(0 이하 = 끔). */
  private final int maxPerUser;

  /**
   * 사용자별 실행 중 개수. 0 이 되면 항목을 지운다 — 한 번이라도 실행한 사용자가 맵에 영영 남지 않게. 값 갱신은 {@code compute} 로 해서 "확인 후
   * 증가"·"감소 후 0 이면 삭제" 가 원자적이다.
   */
  private final ConcurrentHashMap<Long, Integer> perUser = new ConcurrentHashMap<>();

  @Autowired
  public AnalyticsQueryConcurrencyGate(
      @Value("${app.analytics.query-concurrency.max-total:4}") int maxTotal,
      @Value("${app.analytics.query-concurrency.max-per-user:2}") int maxPerUser) {
    this.maxTotal = maxTotal;
    this.maxPerUser = maxPerUser;
    this.total = maxTotal > 0 ? new Semaphore(maxTotal) : null;
  }

  /** 실행 허가. 닫으면 반납한다 — 여러 번 닫아도 한 번만 반납한다(try-with-resources·finally 중복 호출 안전). */
  public interface Permit extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * 허가를 얻는다 — 기다리지 않는다. 사용자 몫을 먼저 얻고 전체 몫에 실패하면 사용자 몫을 되돌린다(사용자 카운터가 새지 않게).
   *
   * @throws CodedApiException 429 {@code QUERY_CONCURRENCY_LIMIT} — 사용자당 또는 전체 상한 초과
   */
  public Permit acquire(long userId) {
    boolean userCounted = maxPerUser > 0;
    if (userCounted && !tryIncrementUser(userId)) {
      throw new CodedApiException(HttpStatus.TOO_MANY_REQUESTS, LIMIT_CODE, USER_LIMIT_MESSAGE);
    }
    if (total != null && !total.tryAcquire()) {
      if (userCounted) {
        decrementUser(userId);
      }
      throw new CodedApiException(HttpStatus.TOO_MANY_REQUESTS, LIMIT_CODE, TOTAL_LIMIT_MESSAGE);
    }
    AtomicBoolean released = new AtomicBoolean();
    return () -> {
      // 한 번만 반납 — 중복 close 가 다른 실행의 허가를 빼앗지 않게.
      if (!released.compareAndSet(false, true)) {
        return;
      }
      if (total != null) {
        total.release();
      }
      if (userCounted) {
        decrementUser(userId);
      }
    };
  }

  /** 사용자 카운터를 상한 안에서만 1 올린다. 올렸으면 true. */
  private boolean tryIncrementUser(long userId) {
    boolean[] ok = {false};
    perUser.compute(
        userId,
        (k, v) -> {
          int cur = v == null ? 0 : v;
          if (cur >= maxPerUser) {
            return v; // 상한 — 그대로 둔다(없던 항목이면 null 이라 만들지 않는다)
          }
          ok[0] = true;
          return cur + 1;
        });
    return ok[0];
  }

  /** 사용자 카운터를 1 내린다. 0 이 되면 항목을 지운다. */
  private void decrementUser(long userId) {
    perUser.computeIfPresent(userId, (k, v) -> v <= 1 ? null : v - 1);
  }

  /** 전체 실행 중 개수(전체 상한이 꺼져 있으면 0) — 테스트·진단용. */
  int activeTotal() {
    return total == null ? 0 : maxTotal - total.availablePermits();
  }

  /** 사용자의 실행 중 개수 — 테스트·진단용. */
  int activeFor(long userId) {
    return perUser.getOrDefault(userId, 0);
  }

  /** 카운터가 남아 있는 사용자 id — 테스트·진단용(0 이 된 사용자는 없어야 한다). */
  Set<Long> trackedUsers() {
    return Set.copyOf(perUser.keySet());
  }
}
