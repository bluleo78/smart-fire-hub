package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.pipeline.service.executor.DbExecutorClient.ProbeColumn;
import java.sql.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tibero probe 컬럼 → 앱 데이터셋 타입 역매핑 표 검증(WD-77 Task 7, 스펙 §4.5).
 *
 * <p>실서버(Tibero 7, 2026-10-24, db-executor {@code
 * TiberoLiveSqlTest#live_probe_numericExpressionPrecisionAndScale})에서 확인한 보고값:
 *
 * <ul>
 *   <li>{@code COUNT(*)}, {@code SUM(정수열)} → NUMBER precision 0 / scale 0 (맨 NUMBER)
 *   <li>{@code ROUND(amount, 2)}, 리터럴 {@code 1} → NUMBER precision 38 / scale <b>0</b> — 소수가 있어도
 *       scale 0 으로 온다
 *   <li>{@code NUMBER(19)} 열 → 19/0, {@code NUMBER(18,6)} 열 → 18/6
 * </ul>
 *
 * 그래서 "맨 NUMBER" 는 precision 0·null 뿐 아니라 38 이상 + scale 0 도 포함한다 — 아니면 ROUND 결과가 INTEGER 로 잘려 값이
 * 손실된다.
 */
class TiberoProbeTypesTest {

  private static ProbeColumn col(String typeName, int jdbcType, Integer precision, Integer scale) {
    return new ProbeColumn("C", jdbcType, typeName, precision, scale);
  }

  @ParameterizedTest
  @CsvSource({
    "VARCHAR,12,100,0",
    "VARCHAR2,12,4000,0",
    "NVARCHAR2,-9,50,0",
    "CHAR,1,10,0",
    "NCHAR,-15,10,0",
    "CLOB,2005,0,0",
    "NCLOB,2011,0,0",
  })
  void textTypes_mapToText_regardlessOfLength(String typeName, int jdbc, int p, int s) {
    assertThat(TiberoProbeTypes.toAppType(col(typeName, jdbc, p, s))).isEqualTo("TEXT");
  }

  @Test
  void number1_0_isBoolean() {
    assertThat(TiberoProbeTypes.toAppType(col("NUMBER", Types.NUMERIC, 1, 0))).isEqualTo("BOOLEAN");
  }

  @ParameterizedTest
  @CsvSource({"2,0", "10,0", "19,0", "37,0"})
  void numberPrecisionOver1_scale0_isInteger(int p, int s) {
    assertThat(TiberoProbeTypes.toAppType(col("NUMBER", Types.NUMERIC, p, s))).isEqualTo("INTEGER");
  }

  @ParameterizedTest
  @CsvSource({"18,6", "10,2", "5,1", "1,1"})
  void numberWithScale_isDecimal(int p, int s) {
    assertThat(TiberoProbeTypes.toAppType(col("NUMBER", Types.NUMERIC, p, s))).isEqualTo("DECIMAL");
  }

  @Test
  void bareNumber_isDecimal_notGuessedAsInteger() {
    // 실서버: COUNT(*)·SUM(정수열) 은 precision 0 / scale 0 으로 온다 — 정수로 짐작하지 않는다(값 손실 방지).
    assertThat(TiberoProbeTypes.toAppType(col("NUMBER", Types.NUMERIC, 0, 0))).isEqualTo("DECIMAL");
    assertThat(TiberoProbeTypes.toAppType(col("NUMBER", Types.NUMERIC, null, null)))
        .isEqualTo("DECIMAL");
    assertThat(TiberoProbeTypes.toAppType(col("NUMBER", Types.NUMERIC, 0, null)))
        .isEqualTo("DECIMAL");
  }

  @Test
  void expressionNumber_precision38Scale0_isDecimal() {
    // 실서버: ROUND(amount,2)·리터럴 1 이 38/0 으로 온다. INTEGER 로 두면 소수가 잘린다.
    assertThat(TiberoProbeTypes.toAppType(col("NUMBER", Types.NUMERIC, 38, 0)))
        .isEqualTo("DECIMAL");
  }

  @Test
  void floatingTypes_areDecimal() {
    assertThat(TiberoProbeTypes.toAppType(col("BINARY_DOUBLE", Types.DOUBLE, 0, 0)))
        .isEqualTo("DECIMAL");
    assertThat(TiberoProbeTypes.toAppType(col("FLOAT", Types.FLOAT, 126, -127)))
        .isEqualTo("DECIMAL");
  }

  @Test
  void date_isDate() {
    assertThat(TiberoProbeTypes.toAppType(col("DATE", Types.DATE, 0, 0))).isEqualTo("DATE");
    // 드라이버가 DATE 를 TIMESTAMP jdbc 타입으로 보고해도 타입 이름이 DATE 면 DATE 다(스펙 표).
    assertThat(TiberoProbeTypes.toAppType(col("DATE", Types.TIMESTAMP, 0, 0))).isEqualTo("DATE");
  }

  @ParameterizedTest
  @CsvSource({
    "TIMESTAMP",
    "TIMESTAMP(6)",
    "TIMESTAMP WITH TIME ZONE",
    "TIMESTAMP WITH LOCAL TIME ZONE",
  })
  void timestampFamily_isTimestamp(String typeName) {
    assertThat(TiberoProbeTypes.toAppType(col(typeName, Types.TIMESTAMP, 0, 6)))
        .isEqualTo("TIMESTAMP");
  }

  @Test
  void others_fallBackToText() {
    assertThat(TiberoProbeTypes.toAppType(col("BLOB", Types.BLOB, 0, 0))).isEqualTo("TEXT");
    assertThat(TiberoProbeTypes.toAppType(col("RAW", Types.VARBINARY, 16, 0))).isEqualTo("TEXT");
    assertThat(TiberoProbeTypes.toAppType(col(null, Types.OTHER, null, null))).isEqualTo("TEXT");
  }

  @Test
  void typeNameIsCaseInsensitive() {
    assertThat(TiberoProbeTypes.toAppType(col("varchar2", 12, 10, 0))).isEqualTo("TEXT");
    assertThat(TiberoProbeTypes.toAppType(col("number", Types.NUMERIC, 10, 0)))
        .isEqualTo("INTEGER");
  }
}
