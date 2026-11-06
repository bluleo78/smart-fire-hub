package com.smartfirehub.global.platformdb.postgres;

import static org.jooq.impl.DSL.val;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.platformdb.PlatformLocks;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * {@link PlatformLocks} 의 PostgreSQL 구현 — {@code pg_advisory_xact_lock} / {@code
 * pg_try_advisory_lock}.
 *
 * <p>기존 호출 지점({@code TriggerRepository}, {@code SecurityLevelRepository}, {@code UserRepository},
 * {@code SessionAdvisoryLock})에 흩어져 있던 SQL 을 한 글자도 바꾸지 않고 옮겼다. 테스트가 SQL 텍스트에 기대고 있기 때문이다 — {@code
 * pg_stat_activity.query ilike '%pg_advisory_xact_lock%'} 폴링, {@code hashtextextended(?, 0)} 키 식
 * 공유.
 *
 * <p>트랜잭션 범위 잠금은 주입된 메인 {@link DSLContext} 로 실행하므로 호출자의 Spring 트랜잭션에 합류한다. 문자열 키는 {@code
 * PythonReadGrantSync.lockTenantSync} 와 같은 키 식({@code hashtextextended(키, 0)})을 써서, 같은 키 문자열이면 두
 * 경로가 같은 잠금 공간에서 직렬화된다.
 */
@Component
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
public class PostgresPlatformLocks implements PlatformLocks {

  /** 문자열 키 트랜잭션 잠금 — 키의 64비트 해시로 잠근다. */
  static final String XACT_LOCK_STRING_SQL =
      "SELECT pg_advisory_xact_lock(hashtextextended({0}, 0))";

  /** 정수 키 트랜잭션 잠금 — 키를 그대로 잠금 id 로 쓴다. */
  static final String XACT_LOCK_LONG_SQL = "SELECT pg_advisory_xact_lock({0})";

  /**
   * 세션 잠금 획득 시도 문장 — 키 문자열의 64비트 해시로 잠근다(기다리지 않는 시도형).
   *
   * <p>키 식은 {@code SqlScriptExecutor.OUTPUT_LOCK_SQL} 과 같아야 한다(같은 해시) — 출력 테이블 잠금 ({@code
   * OutputTableSessionLock})과 SQL 스텝의 출력 잠금이 같은 키 공간에서 서로를 막는 것이 이 일치에 달려 있다.
   */
  public static final String TRY_ACQUIRE_SQL =
      "SELECT pg_try_advisory_lock(hashtextextended(?, 0))";

  /** 세션 잠금 해제 문장 — 이 세션이 쥔 세션 범위 advisory 잠금을 전부 푼다. */
  public static final String RELEASE_SQL = "SELECT pg_advisory_unlock_all()";

  /** 메인 DSL — 호출자의 Spring 트랜잭션에 합류해 트랜잭션 범위 잠금을 잡는다. */
  private final DSLContext dsl;

  @Override
  public void xactLock(String key) {
    dsl.execute(XACT_LOCK_STRING_SQL, val(key));
  }

  @Override
  public void xactLock(long key) {
    dsl.execute(XACT_LOCK_LONG_SQL, val(key));
  }

  @Override
  public boolean trySessionLock(Connection connection, String key) throws SQLException {
    try (PreparedStatement attempt = connection.prepareStatement(TRY_ACQUIRE_SQL)) {
      attempt.setString(1, key);
      try (ResultSet rs = attempt.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  @Override
  public void releaseAllSessionLocks(Connection connection) throws SQLException {
    try (Statement release = connection.createStatement();
        ResultSet ignored = release.executeQuery(RELEASE_SQL)) {
      // 결과는 쓰지 않는다 — 문장이 끝났다는 것이 곧 해제다.
    }
  }
}
