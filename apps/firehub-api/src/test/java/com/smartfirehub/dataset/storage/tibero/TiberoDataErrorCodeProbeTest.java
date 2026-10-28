package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.service.DataExceptionTranslator;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tibero 데이터 오류 코드 실서버 측정(WD-261 §5.1, 측정일 2026-10-27, Tibero 개발 서버).
 *
 * <p>숫자 형식 오류·자릿수 초과·날짜 형식/범위 오류가 {@code SQLException.getErrorCode()} 로 어떤 코드를 내는지 단언으로 고정한다. 값이
 * 바뀌면 이 테스트가 먼저 깨져 {@link TiberoDialect} 코드표({@code INVALID_NUMBER}·{@code NUMBER_OVERFLOW}·{@code
 * INVALID_DATETIME}·{@code DATETIME_OUT_OF_RANGE})를 다시 보게 한다.
 *
 * <p>공유 서버라 이 테스트가 만든 {@code WD261_PROBE_} 접두 테이블 하나만 쓰고, 끝나면 반드시 DROP 한다. TIMESTAMP WITH TIME ZONE
 * 열은 쓰지 않는다. 접속 정보는 환경변수에서만 받고 출력하지 않는다(코드만 {@code PROBE} 줄로 낸다).
 */
@EnabledIfEnvironmentVariable(named = "TIBERO_TEST_HOST", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoDataErrorCodeProbeTest {

  private Connection connection;
  private String table;

  /** 탐침 전용 테이블 하나를 만든다 — 열: D NUMBER(18,6), I NUMBER(19), DT DATE, TS TIMESTAMP. */
  @BeforeAll
  void createProbeTable() throws SQLException {
    TiberoTestEnvironment.assumeDriverPresent();
    connection = TiberoTestEnvironment.openDba(TiberoTestEnvironment.dbaFromEnv());
    connection.setAutoCommit(true);
    String name = "WD261_PROBE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    try (Statement st = connection.createStatement()) {
      st.execute("CREATE TABLE " + name + " (D NUMBER(18,6), I NUMBER(19), DT DATE, TS TIMESTAMP)");
    }
    // CREATE 가 성공한 뒤에만 필드에 담는다 — 실패했을 때 @AfterAll 의 DROP 이 없는 테이블을 지우다 진짜 원인(CREATE 실패)을 가리지 않게.
    table = name;
  }

  /** 만든 테이블만 지운다. 정리 실패가 있어도 연결은 닫는다. */
  @AfterAll
  void dropProbeTable() throws SQLException {
    if (connection == null) {
      return;
    }
    try {
      if (table != null) {
        try (Statement st = connection.createStatement()) {
          st.execute("DROP TABLE " + table);
        }
        // 정리 확인: 같은 이름이 카탈로그에 남아 있지 않아야 한다(공유 서버에 잔재를 두지 않는다).
        try (Statement st = connection.createStatement();
            ResultSet rows =
                st.executeQuery(
                    "SELECT COUNT(*) FROM ALL_TABLES WHERE TABLE_NAME = '" + table + "'")) {
          rows.next();
          System.out.println("PROBE data_error_probe_table_left_after_drop: " + rows.getInt(1));
          assertThat(rows.getInt(1)).isZero();
        }
      }
    } finally {
      connection.close();
    }
  }

  /** 실패해야 하는 SQL 한 번(문장 실행·바인드 실행). */
  @FunctionalInterface
  private interface SqlAction {
    void run() throws SQLException;
  }

  /**
   * {@code action} 이 낸 오류 코드·SQLState 를 {@code PROBE} 줄로 내고 코드를 돌려준다. 성공하면 실패시킨다(측정 대상 오류가 나지 않았다).
   *
   * <p>WD-266: 실제 드라이버 예외가 사유 번역기의 Tibero 판정(PG 예외 아님 + 벤더 코드 대응표)을 통과해 PG 와 같은 사유가 나오는지도 단언한다.
   * SQLState 도 함께 출력해 둔다(실측 2026-10-28: -5111 22003, -5074 ERRDT, -5113·-5114 22007 — 판정은 SQLState
   * 가 아니라 벤더 코드에 기댄다).
   */
  private static int errorCodeOf(String name, SqlAction action) {
    try {
      action.run();
    } catch (SQLException e) {
      System.out.println("PROBE data_error_" + name + ": " + e.getErrorCode());
      System.out.println("PROBE data_error_" + name + "_sqlstate: " + e.getSQLState());
      assertThat(DataExceptionTranslator.describe(e))
          .isNotNull()
          .isEqualTo(
              DataExceptionTranslator.describeDataError(
                  TiberoDialect.sqlState(e.getErrorCode()), null, null));
      return e.getErrorCode();
    }
    throw new AssertionError(name + " 이 성공했다");
  }

  /** 암묵 변환을 타도록 {@code INSERT … SELECT <값식> FROM DUAL} 을 실행해 오류 코드를 돌려준다. 성공하면 실패시킨다. */
  private int insertSelectCode(String name, String column, String valueExpr) {
    String sql = "INSERT INTO " + table + " (" + column + ") SELECT " + valueExpr + " FROM DUAL";
    return errorCodeOf(
        name,
        () -> {
          try (Statement st = connection.createStatement()) {
            st.execute(sql);
          }
        });
  }

  /** 바인드 값으로 {@code INSERT … VALUES (?)} 를 실행해 오류 코드를 돌려준다(Python 적재 경로와 같은 모양). 성공하면 실패시킨다. */
  private int bindCode(String name, String column, BigDecimal value) {
    return errorCodeOf(
        name,
        () -> {
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "INSERT INTO " + table + " (" + column + ") VALUES (?)")) {
            ps.setBigDecimal(1, value);
            ps.executeUpdate();
          }
        });
  }

  /**
   * 측정값(2026-10-27, Tibero 개발 서버) 자체를 고정하고, 운영 코드표({@link TiberoDialect})가 그 코드를 담고 있는지도 확인한다.
   *
   * @param expected 호출처가 넘기는 측정값 리터럴
   */
  private static void assertCode(int actual, int expected, Set<Integer> codeSet) {
    assertThat(actual).isEqualTo(expected);
    assertThat(codeSet).contains(actual);
  }

  /** 숫자가 아닌 문자열을 NUMBER 열에 넣으면 -5074(PG 22P02 에 해당). */
  @Test
  void nonNumericString_isInvalidNumber() {
    assertCode(
        insertSelectCode("number_text_decimal", "D", "'abc'"), -5074, TiberoDialect.INVALID_NUMBER);
    assertCode(
        insertSelectCode("number_text_integer", "I", "'abc'"), -5074, TiberoDialect.INVALID_NUMBER);
    assertCode(
        insertSelectCode("to_number_text", "D", "TO_NUMBER('abc')"),
        -5074,
        TiberoDialect.INVALID_NUMBER);
  }

  /** 정밀도를 넘는 값은 -5111 — NUMBER(18,6) 정수부 초과·NUMBER(19) 초과·문자열 경유·바인드 모두 같다(PG 22003 에 해당). */
  @Test
  void precisionExceeded_isNumberOverflow() {
    assertCode(
        insertSelectCode("overflow_decimal", "D", "10000000000000"),
        -5111,
        TiberoDialect.NUMBER_OVERFLOW);
    assertCode(
        insertSelectCode("overflow_decimal_string", "D", "'10000000000000'"),
        -5111,
        TiberoDialect.NUMBER_OVERFLOW);
    assertCode(
        insertSelectCode("overflow_integer", "I", "99999999999999999999"),
        -5111,
        TiberoDialect.NUMBER_OVERFLOW);
    assertCode(
        insertSelectCode("overflow_integer_string", "I", "'99999999999999999999'"),
        -5111,
        TiberoDialect.NUMBER_OVERFLOW);
    // Python 적재 경로처럼 바인드 값으로 넣어도 같은 코드다.
    assertCode(
        bindCode("bind_overflow_decimal", "D", new BigDecimal("10000000000000.5")),
        -5111,
        TiberoDialect.NUMBER_OVERFLOW);
    assertCode(
        bindCode("bind_overflow_integer", "I", new BigDecimal("12345678901234567890")),
        -5111,
        TiberoDialect.NUMBER_OVERFLOW);
  }

  /** 날짜로 읽을 수 없는 문자열은 -5113(PG 22007 에 해당) — DATE·TIMESTAMP, 암묵·명시 변환 모두. */
  @Test
  void unparsableDateString_isInvalidDatetime() {
    assertCode(
        insertSelectCode("date_text_implicit", "DT", "'abc'"),
        -5113,
        TiberoDialect.INVALID_DATETIME);
    assertCode(
        insertSelectCode("date_text_to_date", "DT", "TO_DATE('abc','YYYY-MM-DD')"),
        -5113,
        TiberoDialect.INVALID_DATETIME);
    assertCode(
        insertSelectCode("timestamp_text_implicit", "TS", "'abc'"),
        -5113,
        TiberoDialect.INVALID_DATETIME);
    assertCode(
        insertSelectCode(
            "timestamp_text_to_timestamp", "TS", "TO_TIMESTAMP('abc','YYYY-MM-DD HH24:MI:SS')"),
        -5113,
        TiberoDialect.INVALID_DATETIME);
  }

  /**
   * 존재하지 않는 날짜(2026-02-30)·월 13·0000년·10000년은 형식 오류와 다른 -5114 로 나온다(PG 22008 에 해당). TO_DATE 로
   * '2026-02-30' 이 거부되는 것까지 확인한다.
   */
  @Test
  void outOfRangeDate_isDatetimeOutOfRange() {
    assertCode(
        insertSelectCode("date_feb30", "DT", "TO_DATE('2026-02-30','YYYY-MM-DD')"),
        -5114,
        TiberoDialect.DATETIME_OUT_OF_RANGE);
    assertCode(
        insertSelectCode("date_month13", "DT", "TO_DATE('2026-13-01','YYYY-MM-DD')"),
        -5114,
        TiberoDialect.DATETIME_OUT_OF_RANGE);
    assertCode(
        insertSelectCode("date_year0", "DT", "TO_DATE('0000-01-01','YYYY-MM-DD')"),
        -5114,
        TiberoDialect.DATETIME_OUT_OF_RANGE);
    assertCode(
        insertSelectCode("date_year10000", "DT", "TO_DATE('10000-01-01','YYYY-MM-DD')"),
        -5114,
        TiberoDialect.DATETIME_OUT_OF_RANGE);
    assertCode(
        insertSelectCode("date_feb30_implicit", "DT", "'2026-02-30'"),
        -5114,
        TiberoDialect.DATETIME_OUT_OF_RANGE);
    assertCode(
        insertSelectCode("timestamp_feb30_implicit", "TS", "'2026-02-30 00:00:00'"),
        -5114,
        TiberoDialect.DATETIME_OUT_OF_RANGE);
    assertCode(
        insertSelectCode(
            "timestamp_feb30_to_timestamp",
            "TS",
            "TO_TIMESTAMP('2026-02-30 00:00:00','YYYY-MM-DD HH24:MI:SS')"),
        -5114,
        TiberoDialect.DATETIME_OUT_OF_RANGE);
  }
}
