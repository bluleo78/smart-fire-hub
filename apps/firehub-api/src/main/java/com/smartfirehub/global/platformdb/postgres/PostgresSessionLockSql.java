package com.smartfirehub.global.platformdb.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * PG 세션 advisory 잠금의 SQL·JDBC 본문 단일 원천(WD-493) — {@code pg_try_advisory_lock} / {@code
 * pg_advisory_unlock_all}.
 *
 * <p>왜 있나: 플랫폼 DB 세션 잠금({@link PostgresPlatformLocks}, 증분 스텝 잠금 WD-223)과 데이터 저장소 세션 잠금({@code
 * DataStoreSessionLocks}, 출력 테이블 잠금 #735)은 지키는 저장소만 다르고 문장·본문이 같다. 두 벌로 두면 한쪽만 고쳐 키 식이 갈라질 수 있으므로
 * 여기 한곳에 둔다. 어느 DB 에서 도는지는 호출자가 넘기는 커넥션(풀)이 정한다. 상태가 없다.
 *
 * <p>키 식({@code hashtextextended(?, 0)})은 {@code SqlScriptExecutor.OUTPUT_LOCK_SQL} 과 같아야 한다 — 출력
 * 테이블 세션 잠금과 SQL 스텝의 출력 잠금이 같은 키 공간에서 서로를 막는 것이 이 일치에 달려 있다.
 */
public final class PostgresSessionLockSql {

  /** 세션 잠금 획득 시도 문장 — 키 문자열의 64비트 해시로 잠근다(기다리지 않는 시도형). */
  public static final String TRY_ACQUIRE_SQL =
      "SELECT pg_try_advisory_lock(hashtextextended(?, 0))";

  /** 세션 잠금 해제 문장 — 이 세션이 쥔 세션 범위 advisory 잠금을 전부 푼다. */
  public static final String RELEASE_SQL = "SELECT pg_advisory_unlock_all()";

  private PostgresSessionLockSql() {}

  /**
   * 주어진 autocommit 커넥션에서 세션 잠금을 한 번 시도한다. 기다리지 않는다.
   *
   * @return 잡았으면 true, 다른 세션이 쥐고 있으면 false
   */
  public static boolean tryAcquire(Connection connection, String key) throws SQLException {
    try (PreparedStatement attempt = connection.prepareStatement(TRY_ACQUIRE_SQL)) {
      attempt.setString(1, key);
      try (ResultSet rs = attempt.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  /**
   * 키와 무관하게 이 세션의 세션 잠금을 전부 푼다. 키별 해제 대신 {@code unlock_all} 을 쓰는 것은 잠금 전용 커넥션이 이 잠금 하나만 쥐고, 획득 시도가
   * 중간에 끊겨 잠금 여부가 모호한 경우까지 덮기 위해서다(기존 동작 유지).
   */
  public static void releaseAll(Connection connection) throws SQLException {
    try (Statement release = connection.createStatement();
        ResultSet ignored = release.executeQuery(RELEASE_SQL)) {
      // 결과는 쓰지 않는다 — 문장이 끝났다는 것이 곧 해제다.
    }
  }
}
