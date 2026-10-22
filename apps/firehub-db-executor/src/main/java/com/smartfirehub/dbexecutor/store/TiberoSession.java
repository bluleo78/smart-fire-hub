package com.smartfirehub.dbexecutor.store;

/** Tibero 접속 고정값. api {@code TiberoDialect} 와 같아야 하며 고정값 JSON 으로 테스트가 대조한다. */
public final class TiberoSession {

  private TiberoSession() {}

  /** JDBC 드라이버 클래스(jar 는 libs/ 에서 선택적으로 가져온다). */
  public static final String DRIVER_CLASS = "com.tmax.tibero.jdbc.TbDriver";

  /** 커넥션 유효성 검사 쿼리(FROM 절이 필수라 DUAL). */
  public static final String VALIDATION_QUERY = "SELECT 1 FROM DUAL";

  /**
   * 세션 NLS 고정 문장 — 날짜·타임스탬프 형식과 소수점 문자를 고정해 DB·클라이언트 기본값에 따라 암묵 변환 결과가 달라지지 않게 한다. 익명 블록 하나인 이유:
   * Hikari {@code connectionInitSql} 은 문장 하나만 받는다.
   */
  public static final String SESSION_INIT_SQL =
      "BEGIN EXECUTE IMMEDIATE 'ALTER SESSION SET NLS_DATE_FORMAT=''YYYY-MM-DD'''; EXECUTE IMMEDIATE"
          + " 'ALTER SESSION SET NLS_TIMESTAMP_FORMAT=''YYYY-MM-DD HH24:MI:SS.FF6'''; EXECUTE"
          + " IMMEDIATE 'ALTER SESSION SET NLS_NUMERIC_CHARACTERS=''.,'''; END;";

  /** api {@code ExternalDbTarget#jdbcUrl} 와 같은 형식. */
  public static String jdbcUrl(String host, int port, String database) {
    return "jdbc:tibero:thin:@" + host + ":" + port + ":" + database;
  }
}
