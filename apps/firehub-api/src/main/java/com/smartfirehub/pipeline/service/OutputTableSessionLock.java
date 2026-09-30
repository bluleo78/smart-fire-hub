package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 출력 테이블 단위 직렬화 잠금의 <b>세션 범위</b> 판(#735) — "비우기 + 적재"를 한 트랜잭션으로 묶을 수
 * 없는 경로 전용이다.
 *
 * <p><b>누가 쓰는가.</b> 실행기를 끈 PYTHON 스텝의 REPLACE 뿐이다. 그 경로는 API 가 출력을 truncate 한
 * 뒤 자식 파이썬 프로세스가 <b>자기 커넥션</b>으로 적재하므로, SQL 스텝처럼 트랜잭션 범위 잠금
 * ({@link SqlScriptExecutor#OUTPUT_LOCK_SQL})으로 "비우기~적재 커밋" 구간을 덮을 수 없다. SQL 스텝은
 * 이 클래스를 쓰지 않는다 — 트랜잭션 범위 잠금이 커밋·롤백 때 저절로 풀려 더 안전하다.
 *
 * <p><b>키는 SQL 경로와 같다.</b> {@link OutputClearStatement#deleteAll} 이 만든 선행 문장에서
 * {@link SqlScriptExecutor#outputLockKey} 로 뽑는다(직접 조립하지 않는다 — 어긋나면 두 경로가 서로를
 * 못 본다). PostgreSQL 의 세션 범위·트랜잭션 범위 advisory 잠금은 같은 키 공간을 쓰고 세션이 다르면
 * 서로 충돌하므로, 이 잠금을 쥔 동안에는 같은 출력에 대한 SQL 스텝의 "비우기 + 적재"도 기다린다.
 *
 * <p><b>누수 방지.</b> 세션 잠금은 커밋으로 풀리지 않고 커넥션이 풀(pool)로 돌아가도 남는다 — 풀어
 * 주지 않으면 그 커넥션이 닫힐 때까지 해당 출력의 모든 REPLACE 가 영원히 기다린다. 그래서
 *
 * <ul>
 *   <li>잠금을 얻은 커넥션은 본문이 도는 동안 아무에게도 내주지 않고,
 *   <li>본문이 성공하든 예외로 끝나든 {@code finally} 에서 {@code pg_advisory_unlock_all()} 로 풀며
 *       (이 커넥션은 이 잠금만 쥐므로 전부 풀어도 된다 — 획득 문장이 중간에 끊겨 잠금 여부가 모호한
 *       경우까지 덮는다),
 *   <li>해제 문장마저 실패하면 커넥션을 <b>물리적으로 끊는다</b>({@link Connection#abort}) — 세션이
 *       끝나면 서버가 잠금을 회수하고, 풀은 끊긴 커넥션을 버린다. 잠금을 쥔 세션이 풀로 돌아가는
 *       경로를 남기지 않는다.
 * </ul>
 *
 * <p>대기 시간 제한(lock_timeout)은 걸지 않는다 — #731 과 같은 이유다(뒤 실행을 실패로 바꾸는 것은
 * 제품 정책 변경).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutputTableSessionLock {

  /**
   * 획득 시도 문장 — 키 식은 {@link SqlScriptExecutor#OUTPUT_LOCK_SQL} 과 같아야 한다(같은 해시).
   *
   * <p><b>기다리는 {@code pg_advisory_lock} 이 아니라 시도형({@code pg_try_advisory_lock})을 폴링한다.</b>
   * 기다리는 동안 메인 풀 커넥션을 쥐고 있으면, 같은 출력에 겹친 실행이 여럿일 때 대기자들이 메인 풀을
   * 다 차지해 잠금을 쥔 실행조차 비우기(truncate)에 쓸 커넥션을 못 얻는 교착이 생긴다(대기자는 쥔
   * 실행을, 쥔 실행은 풀을 기다린다). 그래서 실패한 시도는 커넥션을 바로 풀에 돌려주고 잠시 뒤 다시
   * 시도한다. 잠금을 얻은 커넥션만 본문이 끝날 때까지 붙잡는다.
   */
  static final String TRY_ACQUIRE_SQL = "SELECT pg_try_advisory_lock(hashtextextended(?, 0))";

  /** 획득 실패 뒤 다시 시도하기까지의 간격. 로컬 전용 경로라 정밀한 공정성은 필요 없다. */
  static final long RETRY_INTERVAL_MS = 200;

  /** 해제 문장 — 이 세션이 쥔 세션 범위 advisory 잠금을 전부 푼다. */
  static final String RELEASE_SQL = "SELECT pg_advisory_unlock_all()";

  /** 메인 커넥션 풀. 잠금 전용 커넥션을 여기서 하나 빌린다. */
  private final DataSource dataSource;

  /**
   * 출력 테이블의 직렬화 잠금을 쥔 채 본문을 실행한다. 다른 실행이 같은 출력의 잠금을 쥐고 있으면
   * 그 실행이 끝날 때까지 기다린다(거부하지 않는다).
   *
   * @param outputTableName 출력 테이블명(스키마 미포함). 현재 테넌트 스키마로 한정해 키를 만든다.
   * @param body 잠금 아래에서 실행할 본문(비우기 + 적재)
   * @return 본문의 반환값
   */
  public <T> T callLocked(String outputTableName, Supplier<T> body) {
    String key = SqlScriptExecutor.outputLockKey(OutputClearStatement.deleteAll(outputTableName));
    Connection connection = acquire(key);
    try {
      return body.get();
    } finally {
      release(connection);
    }
  }

  /** 잠금을 얻을 때까지 시도한다. 얻으면 그 잠금을 쥔 커넥션을 돌려준다(반납은 호출자 몫). */
  private Connection acquire(String key) {
    while (true) {
      Connection connection;
      try {
        connection = dataSource.getConnection();
      } catch (SQLException e) {
        throw new ScriptExecutionException("출력 테이블 잠금용 커넥션을 얻지 못했습니다: " + e.getMessage(), e);
      }
      boolean locked = false;
      try {
        // 트랜잭션을 열어 두지 않는다 — 세션 잠금은 트랜잭션과 무관하고, 자동 커밋이 아니면 본문이
        // 도는 내내 이 커넥션이 idle in transaction 으로 남는다.
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
        throw new ScriptExecutionException("출력 테이블 잠금을 얻지 못했습니다: " + e.getMessage(), e);
      }
      if (locked) {
        return connection;
      }
      // 다른 실행이 쥐고 있다 — 커넥션을 바로 돌려주고 잠시 뒤 다시 시도한다(위 TRY_ACQUIRE_SQL 참조).
      release(connection);
      try {
        Thread.sleep(RETRY_INTERVAL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ScriptExecutionException("출력 테이블 잠금 대기 중 중단되었습니다", e);
      }
    }
  }

  /** 잠금을 풀고 커넥션을 반납한다. 풀지 못했으면 커넥션을 끊어 잠금이 풀로 새지 않게 한다. */
  private void release(Connection connection) {
    try {
      try (Statement release = connection.createStatement();
          ResultSet ignored = release.executeQuery(RELEASE_SQL)) {
        // 결과는 쓰지 않는다 — 문장이 끝났다는 것이 곧 해제다.
      }
    } catch (SQLException | RuntimeException e) {
      log.warn("출력 테이블 세션 잠금 해제 실패 — 커넥션을 끊어 서버가 잠금을 회수하게 한다", e);
      try {
        connection.abort(Runnable::run);
      } catch (SQLException | RuntimeException abortFailure) {
        log.error("잠금 해제에 실패한 커넥션을 끊지 못했습니다", abortFailure);
      }
    } finally {
      try {
        connection.close();
      } catch (SQLException e) {
        log.warn("출력 테이블 잠금용 커넥션 반납 실패", e);
      }
    }
  }
}
