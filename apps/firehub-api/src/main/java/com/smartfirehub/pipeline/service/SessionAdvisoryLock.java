package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.platformdb.PlatformLocks;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.executor.ExecutionCancelCheck;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

/**
 * 세션 범위 advisory 잠금의 공통 획득·해제 장치 — 증분 스텝 잠금(WD-223, {@link IncrementalStepLock})이 쓴다. 예전에는 출력 테이블
 * 세션 잠금(#735)도 함께 썼으나 WD-690 에서 트랜잭션 범위 잠금({@link OutputSwapLock})으로 바뀌었다.
 *
 * <p>증분 스텝 잠금은 "여러 커넥션·여러 트랜잭션에 걸친 구간"을 덮어야 해서 트랜잭션 범위 잠금으로는 안 된다. 세션 잠금은 커밋으로 풀리지 않고 풀로 돌아간 커넥션에도
 * 남으므로, 누수 방지 규칙(잠금 커넥션은 아무에게도 내주지 않고, 키로 풀고(PG 구현은 {@code unlock_all}), 그마저 실패하면 커넥션을 물리적으로 끊는다)을
 * 한 곳에 둔다 — 잠금마다 따로 복제하면 한쪽만 고쳐지는 일이 생긴다.
 *
 * <p><b>기다리는 {@code pg_advisory_lock} 이 아니라 시도형({@code pg_try_advisory_lock})을 폴링한다.</b> 기다리는 동안 메인
 * 풀 커넥션을 쥐고 있으면 대기자들이 풀을 다 차지해 잠금을 쥔 실행조차 커넥션을 못 얻는 교착이 생긴다. 실패한 시도는 커넥션을 바로 풀에 돌려주고 잠시 뒤 다시 시도한다.
 * 잠금을 얻은 커넥션만 해제할 때까지 붙잡는다.
 */
@Slf4j
final class SessionAdvisoryLock {

  /** 획득 실패 뒤 다시 시도하기까지의 간격. */
  static final long RETRY_INTERVAL_MS = 200;

  /** 대기 상한 없음. */
  static final long NO_LIMIT = -1;

  private SessionAdvisoryLock() {}

  /**
   * 잠금을 얻은 상태. {@link #close()} 로 풀고 커넥션을 반납한다 — 여러 번 불러도 한 번만 푼다(성공 경로의 조기 해제 + {@code finally} 의
   * 안전망을 함께 쓸 수 있게).
   */
  static final class Held implements AutoCloseable {
    private Connection connection;
    private final PlatformLocks locks;
    private final String key;

    private Held(Connection connection, PlatformLocks locks, String key) {
      this.connection = connection;
      this.locks = locks;
      this.key = key;
    }

    @Override
    public void close() {
      Connection c = connection;
      connection = null;
      if (c != null) {
        release(locks, c, key);
      }
    }
  }

  /**
   * 잠금을 얻을 때까지 시도한다.
   *
   * @param locks 플랫폼 DB 잠금 구현(DBMS 별 SQL 은 여기 있다 — WD-486)
   * @param dataSource 플랫폼 풀(잠금이 놓일 DB 는 이 풀의 DB 다). 잠금 전용 커넥션을 빌린다
   * @param key 잠금 키 문자열(해시해 쓴다)
   * @param label 오류 문구에 쓸 잠금 이름(예: "증분 스텝 잠금")
   * @param maxWaitMs 대기 상한(밀리초). {@link #NO_LIMIT} 이면 무제한
   * @param cancel 대기 중 매 회차 확인할 협조적 취소(WD-153). 취소면 {@code PipelineExecutionCancelledException} 이
   *     그대로 나간다
   * @param timeoutMessage 상한을 넘겼을 때 던질 한국어 안내
   */
  static Held acquire(
      PlatformLocks locks,
      DataSource dataSource,
      String key,
      String label,
      long maxWaitMs,
      ExecutionCancelCheck cancel,
      Supplier<String> timeoutMessage) {
    long deadline = maxWaitMs < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + maxWaitMs;
    while (true) {
      // 잠금을 얻기 전이므로 취소돼도 풀 것이 없다 — 취소 예외를 그대로 내보낸다.
      cancel.checkpoint(0, false);
      Connection connection;
      try {
        connection = dataSource.getConnection();
      } catch (SQLTransientConnectionException e) {
        // 상한이 있는 대기(증분 스텝 잠금, WD-223)는 잠금 전용 풀이 바닥나도 "다른 실행이 쥐고 있음"과 같게 본다 — 상한 안에서
        // 다시 시도하고, 넘기면 같은 안내로 실패한다. 상한 없는 호출은 예전처럼 바로 실패한다.
        if (maxWaitMs < 0) {
          throw new ScriptExecutionException(label + "용 커넥션을 얻지 못했습니다: " + e.getMessage(), e);
        }
        waitBeforeRetry(deadline, label, timeoutMessage);
        continue;
      } catch (SQLException e) {
        throw new ScriptExecutionException(label + "용 커넥션을 얻지 못했습니다: " + e.getMessage(), e);
      }
      boolean locked = false;
      try {
        // 트랜잭션을 열어 두지 않는다 — 세션 잠금은 트랜잭션과 무관하고, 자동 커밋이 아니면 잠금을 쥐는
        // 내내 이 커넥션이 idle in transaction 으로 남는다.
        connection.setAutoCommit(true);
        locked = locks.trySessionLock(connection, key);
      } catch (SQLException | RuntimeException e) {
        // 시도 문장이 중간에 끊기면 잠금 여부가 모호하다 — release 가 키 해제(실패 시 끊기)로 덮는다.
        release(locks, connection, key);
        throw new ScriptExecutionException(label + "을 얻지 못했습니다: " + e.getMessage(), e);
      }
      if (locked) {
        return new Held(connection, locks, key);
      }
      // 다른 실행이 쥐고 있다 — 커넥션을 바로 돌려주고 잠시 뒤 다시 시도한다(클래스 Javadoc 참조).
      release(locks, connection, key);
      waitBeforeRetry(deadline, label, timeoutMessage);
    }
  }

  /** 상한을 넘겼으면 안내와 함께 실패하고, 아니면 다음 시도까지 잠시 잔다. */
  private static void waitBeforeRetry(
      long deadline, String label, Supplier<String> timeoutMessage) {
    if (System.currentTimeMillis() >= deadline) {
      throw new ScriptExecutionException(timeoutMessage.get());
    }
    try {
      Thread.sleep(RETRY_INTERVAL_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScriptExecutionException(label + " 대기 중 중단되었습니다", e);
    }
  }

  /** 잠금을 풀고 커넥션을 반납한다. 풀지 못했으면 커넥션을 끊어 잠금이 풀로 새지 않게 한다. */
  static void release(PlatformLocks locks, Connection connection, String key) {
    try {
      locks.releaseSessionLock(connection, key);
    } catch (SQLException | RuntimeException e) {
      log.warn("세션 advisory 잠금 해제 실패 — 커넥션을 끊어 서버가 잠금을 회수하게 한다", e);
      try {
        connection.abort(Runnable::run);
      } catch (SQLException | RuntimeException abortFailure) {
        log.error("잠금 해제에 실패한 커넥션을 끊지 못했습니다", abortFailure);
      }
    } finally {
      try {
        connection.close();
      } catch (SQLException e) {
        log.warn("잠금용 커넥션 반납 실패", e);
      }
    }
  }
}
