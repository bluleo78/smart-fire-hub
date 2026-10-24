package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.executor.ExecutionCancelCheck;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

/**
 * 세션 범위 advisory 잠금의 공통 획득·해제 장치 — 출력 테이블 세션 잠금(#735, {@link OutputTableSessionLock})과 증분 스텝
 * 잠금(WD-223, {@link IncrementalStepLock})이 함께 쓴다.
 *
 * <p>두 잠금 모두 "여러 커넥션·여러 트랜잭션에 걸친 구간"을 덮어야 해서 트랜잭션 범위 잠금으로는 안 된다. 세션 잠금은 커밋으로 풀리지 않고 풀로 돌아간 커넥션에도
 * 남으므로, 누수 방지 규칙(잠금 커넥션은 아무에게도 내주지 않고, {@code unlock_all} 로 풀고, 그마저 실패하면 커넥션을 물리적으로 끊는다)을 한 곳에 둔다 —
 * 두 벌로 복제하면 한쪽만 고쳐지는 일이 생긴다.
 *
 * <p><b>기다리는 {@code pg_advisory_lock} 이 아니라 시도형({@code pg_try_advisory_lock})을 폴링한다.</b> 기다리는 동안 메인
 * 풀 커넥션을 쥐고 있으면 대기자들이 풀을 다 차지해 잠금을 쥔 실행조차 커넥션을 못 얻는 교착이 생긴다. 실패한 시도는 커넥션을 바로 풀에 돌려주고 잠시 뒤 다시 시도한다.
 * 잠금을 얻은 커넥션만 해제할 때까지 붙잡는다.
 */
@Slf4j
final class SessionAdvisoryLock {

  /** 획득 시도 문장 — 키 문자열의 64비트 해시로 잠근다. */
  static final String TRY_ACQUIRE_SQL = "SELECT pg_try_advisory_lock(hashtextextended(?, 0))";

  /** 획득 실패 뒤 다시 시도하기까지의 간격. */
  static final long RETRY_INTERVAL_MS = 200;

  /** 해제 문장 — 이 세션이 쥔 세션 범위 advisory 잠금을 전부 푼다(잠금 커넥션은 이 잠금 하나만 쥔다). */
  static final String RELEASE_SQL = "SELECT pg_advisory_unlock_all()";

  /** 대기 상한 없음. */
  static final long NO_LIMIT = -1;

  private SessionAdvisoryLock() {}

  /**
   * 잠금을 얻은 상태. {@link #close()} 로 풀고 커넥션을 반납한다 — 여러 번 불러도 한 번만 푼다(성공 경로의 조기 해제 + {@code finally} 의
   * 안전망을 함께 쓸 수 있게).
   */
  static final class Held implements AutoCloseable {
    private Connection connection;

    private Held(Connection connection) {
      this.connection = connection;
    }

    @Override
    public void close() {
      Connection c = connection;
      connection = null;
      if (c != null) {
        release(c);
      }
    }
  }

  /**
   * 잠금을 얻을 때까지 시도한다.
   *
   * @param dataSource 잠금 전용 커넥션을 빌릴 풀
   * @param key 잠금 키 문자열(해시해 쓴다)
   * @param label 오류 문구에 쓸 잠금 이름(예: "출력 테이블 잠금")
   * @param maxWaitMs 대기 상한(밀리초). {@link #NO_LIMIT} 이면 무제한
   * @param cancel 대기 중 매 회차 확인할 협조적 취소(WD-153). 취소면 {@code PipelineExecutionCancelledException} 이
   *     그대로 나간다
   * @param timeoutMessage 상한을 넘겼을 때 던질 한국어 안내
   */
  static Held acquire(
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
        // 다시 시도하고, 넘기면 같은 안내로 실패한다. 상한 없는 호출(#735 출력 세션 잠금)은 예전처럼 바로 실패한다.
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
        try (PreparedStatement attempt = connection.prepareStatement(TRY_ACQUIRE_SQL)) {
          attempt.setString(1, key);
          try (ResultSet rs = attempt.executeQuery()) {
            locked = rs.next() && rs.getBoolean(1);
          }
        }
      } catch (SQLException | RuntimeException e) {
        // 시도 문장이 중간에 끊기면 잠금 여부가 모호하다 — release 가 unlock_all(실패 시 끊기)로 덮는다.
        release(connection);
        throw new ScriptExecutionException(label + "을 얻지 못했습니다: " + e.getMessage(), e);
      }
      if (locked) {
        return new Held(connection);
      }
      // 다른 실행이 쥐고 있다 — 커넥션을 바로 돌려주고 잠시 뒤 다시 시도한다(클래스 Javadoc 참조).
      release(connection);
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
  static void release(Connection connection) {
    try {
      try (Statement release = connection.createStatement();
          ResultSet ignored = release.executeQuery(RELEASE_SQL)) {
        // 결과는 쓰지 않는다 — 문장이 끝났다는 것이 곧 해제다.
      }
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
