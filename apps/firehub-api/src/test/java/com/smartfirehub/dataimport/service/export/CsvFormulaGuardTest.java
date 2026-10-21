package com.smartfirehub.dataimport.service.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * CsvFormulaGuard 단위 테스트 — CSV 수식 주입(OWASP CSV Injection) 중화 규칙(WD-156).
 *
 * <p>규칙: 문자열 값이 {@code = + - @ \t \r} 로 시작하면 앞에 {@code '} 를 붙인다. 숫자·날짜·불리언·지오메트리는 원형 유지.
 */
class CsvFormulaGuardTest {

  /** 수식 시작 문자 6종 모두 작은따옴표가 붙어야 한다. */
  @Test
  void neutralize_prefixesAllFormulaStartCharacters() {
    assertThat(CsvFormulaGuard.neutralize("=1+1")).isEqualTo("'=1+1");
    assertThat(CsvFormulaGuard.neutralize("=HYPERLINK(\"http://x\",\"클릭\")"))
        .isEqualTo("'=HYPERLINK(\"http://x\",\"클릭\")");
    assertThat(CsvFormulaGuard.neutralize("+1")).isEqualTo("'+1");
    assertThat(CsvFormulaGuard.neutralize("-1")).isEqualTo("'-1");
    assertThat(CsvFormulaGuard.neutralize("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    assertThat(CsvFormulaGuard.neutralize("\t=1")).isEqualTo("'\t=1");
    assertThat(CsvFormulaGuard.neutralize("\r=1")).isEqualTo("'\r=1");
  }

  /** 정상 문자열·빈 문자열·null·중간에 위험 문자가 있는 값·앞 공백 값은 그대로다. */
  @Test
  void neutralize_leavesSafeValuesUnchanged() {
    assertThat(CsvFormulaGuard.neutralize("정상값")).isEqualTo("정상값");
    assertThat(CsvFormulaGuard.neutralize("a=1+1")).isEqualTo("a=1+1");
    assertThat(CsvFormulaGuard.neutralize(" =1+1")).isEqualTo(" =1+1");
    assertThat(CsvFormulaGuard.neutralize("")).isEqualTo("");
    assertThat(CsvFormulaGuard.neutralize(null)).isNull();
  }

  /** 문자열 컬럼(TEXT·VARCHAR·모르는 타입·타입 없음)은 중화하고, 비문자열 컬럼은 문자열 값이어도 원형 유지. */
  @Test
  void datasetCell_guardsByColumnType() {
    assertThat(CsvFormulaGuard.datasetCell("=1+1", "TEXT")).isEqualTo("'=1+1");
    assertThat(CsvFormulaGuard.datasetCell("-1", "VARCHAR(20)")).isEqualTo("'-1");
    assertThat(CsvFormulaGuard.datasetCell("=1+1", "SOMETHING_NEW")).isEqualTo("'=1+1");
    assertThat(CsvFormulaGuard.datasetCell("=1+1", null)).isEqualTo("'=1+1");

    // 비문자열 컬럼 — 음수·날짜가 따옴표로 망가지지 않아야 한다
    assertThat(CsvFormulaGuard.datasetCell(-5L, "BIGINT")).isEqualTo("-5");
    assertThat(CsvFormulaGuard.datasetCell("-5", "INTEGER")).isEqualTo("-5");
    assertThat(CsvFormulaGuard.datasetCell(new BigDecimal("-5.5"), "NUMERIC(18,6)"))
        .isEqualTo("-5.5");
    assertThat(CsvFormulaGuard.datasetCell("-5.5", "decimal")).isEqualTo("-5.5");
    assertThat(CsvFormulaGuard.datasetCell(LocalDate.of(2026, 1, 2), "DATE"))
        .isEqualTo("2026-01-02");
    assertThat(CsvFormulaGuard.datasetCell(false, "BOOLEAN")).isEqualTo("false");
    // GEOMETRY 는 GeoJSON 문자열 — 원형 유지
    String geo = "{\"type\":\"Point\",\"coordinates\":[-126.97,37.56]}";
    assertThat(CsvFormulaGuard.datasetCell(geo, "GEOMETRY")).isEqualTo(geo);

    assertThat(CsvFormulaGuard.datasetCell(null, "TEXT")).isEqualTo("");
  }

  /** 쿼리 결과 — 값의 Java 타입으로 판정: 숫자·날짜·불리언은 원형, 문자열·모르는 타입은 중화. */
  @Test
  void queryCell_guardsByValueType() {
    assertThat(CsvFormulaGuard.queryCell("=1+1")).isEqualTo("'=1+1");
    assertThat(CsvFormulaGuard.queryCell("정상값")).isEqualTo("정상값");
    assertThat(CsvFormulaGuard.queryCell(-5)).isEqualTo("-5");
    assertThat(CsvFormulaGuard.queryCell(-5L)).isEqualTo("-5");
    assertThat(CsvFormulaGuard.queryCell(new BigDecimal("-5.5"))).isEqualTo("-5.5");
    assertThat(CsvFormulaGuard.queryCell(-1.5d)).isEqualTo("-1.5");
    assertThat(CsvFormulaGuard.queryCell(LocalDate.of(2026, 1, 2))).isEqualTo("2026-01-02");
    assertThat(CsvFormulaGuard.queryCell(LocalDateTime.of(2026, 1, 2, 3, 4)))
        .isEqualTo("2026-01-02T03:04");
    assertThat(CsvFormulaGuard.queryCell(OffsetDateTime.of(2026, 1, 2, 3, 4, 0, 0, ZoneOffset.UTC)))
        .isEqualTo("2026-01-02T03:04Z");
    // java.sql.Timestamp 는 Temporal 이 아니라 java.util.Date 하위 — 원형 유지
    Timestamp ts = Timestamp.valueOf("2026-01-02 03:04:05");
    assertThat(CsvFormulaGuard.queryCell(ts)).isEqualTo(ts.toString());
    assertThat(CsvFormulaGuard.queryCell(true)).isEqualTo("true");
    // 모르는 타입은 문자열로 보고 중화(안전 우선)
    Object unknown =
        new Object() {
          @Override
          public String toString() {
            return "-1 days";
          }
        };
    assertThat(CsvFormulaGuard.queryCell(unknown)).isEqualTo("'-1 days");
    assertThat(CsvFormulaGuard.queryCell(null)).isEqualTo("");
  }
}
