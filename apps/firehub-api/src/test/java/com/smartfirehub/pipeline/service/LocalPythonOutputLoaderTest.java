package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * executor({@code python_executor.py}) 의 stdout JSON 파싱·타입 변환과의 패리티(R5). 기대값은 executor 이미지와 같은
 * Python 3.12 에서 원본 함수({@code _convert_single}·{@code _parse_stdout_json})를 실제로 돌린 결과다(2026-10-09,
 * 3.12.13) — 이 표를 바꾸려면 원본을 다시 돌려 확인한다.
 */
class LocalPythonOutputLoaderTest {

  private static Object c(Object value, String dtype) {
    return LocalPythonOutputLoader.convertSingle(value, dtype);
  }

  @Test
  void integer_matchesPythonInt() {
    assertThat(c("42", "INTEGER")).isEqualTo(42L);
    assertThat(c(" +1_000 ", "INTEGER")).isEqualTo(1000L);
    assertThat(c(42, "integer")).as("타입 이름은 대소문자 무시").isEqualTo(42L);
    // int("1.0")·int("True") 는 ValueError → None(오류 아님)
    assertThat(c("1.0", "INTEGER")).isNull();
    assertThat(c(1.0d, "INTEGER")).isNull();
    assertThat(c(true, "INTEGER")).isNull();
    assertThat(c(new BigInteger("12345678901234567890"), "INTEGER"))
        .isEqualTo(new BigInteger("12345678901234567890"));
  }

  @Test
  void decimal_matchesPythonDecimalOfStr() {
    assertThat(c("1.50", "DECIMAL")).isEqualTo(new BigDecimal("1.50"));
    assertThat(c(0.1d, "DECIMAL")).isEqualTo(new BigDecimal("0.1"));
    assertThat(c(1e-05d, "NUMERIC")).isEqualTo(new BigDecimal("0.00001"));
    assertThat(c(1e16d, "FLOAT")).isEqualTo(new BigDecimal("1E+16"));
    assertThat(c("1_000.5", "DOUBLE")).isEqualTo(new BigDecimal("1000.5"));
    assertThat(c("abc", "DECIMAL")).isNull();
    assertThat(c(true, "DECIMAL")).isNull();
  }

  @Test
  void boolean_matchesPythonTruthTable() {
    assertThat(c(true, "BOOLEAN")).isEqualTo(true);
    assertThat(c("Yes", "BOOLEAN")).isEqualTo(true);
    assertThat(c(1, "BOOLEAN")).isEqualTo(true);
    // str(1.0) == "1.0" 는 진리표에 없다
    assertThat(c(1.0d, "BOOLEAN")).isEqualTo(false);
    assertThat(c("no", "BOOLEAN")).isEqualTo(false);
  }

  @Test
  void date_matchesPythonFromisoformat() {
    assertThat(c("2024-02-29", "DATE")).isEqualTo(LocalDate.of(2024, 2, 29));
    assertThat(c("20240301", "DATE")).isEqualTo(LocalDate.of(2024, 3, 1));
    assertThat(c("2024-W01-1", "DATE")).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThat(c("2023-02-29", "DATE")).isNull();
    assertThat(c("2024-01-01T00:00", "DATE")).isNull();
  }

  @Test
  void timestamp_matchesPythonFromisoformat() {
    assertThat(c("2024-01-02T03:04:05", "TIMESTAMP"))
        .isEqualTo(LocalDateTime.of(2024, 1, 2, 3, 4, 5));
    assertThat(c("2024-01-02 03:04:05.1234567", "DATETIME"))
        .as("분수 6자리 초과는 잘린다")
        .isEqualTo(LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123456000));
    assertThat(c("2024-01-02T03:04:05Z", "TIMESTAMP"))
        .isEqualTo(OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC));
    assertThat(c("2024-01-02T03:04+09:00", "TIMESTAMP"))
        .isEqualTo(OffsetDateTime.of(2024, 1, 2, 3, 4, 0, 0, ZoneOffset.ofHours(9)));
    assertThat(c("2024-01-02", "TIMESTAMP")).isEqualTo(LocalDateTime.of(2024, 1, 2, 0, 0));
    assertThat(c("nope", "TIMESTAMP")).isNull();
  }

  @Test
  void text_matchesPythonStr() {
    assertThat(c(1.5d, "TEXT")).isEqualTo("1.5");
    assertThat(c(true, "TEXT")).isEqualTo("True");
    assertThat(c(1e16d, "TEXT")).isEqualTo("1e+16");
    assertThat(c(1e-5d, "TEXT")).isEqualTo("1e-05");
    assertThat(c(123.0d, "STRING")).isEqualTo("123.0");
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("a", List.of(1, "x"));
    assertThat(c(nested, "TEXT")).isEqualTo("{'a': [1, 'x']}");
    assertThat(c("it's", "VARCHAR")).isEqualTo("it's");
  }

  @Test
  void unknownTypes_passThrough() {
    assertThat(c("x", "GEOMETRY")).isEqualTo("x");
    assertThat(c(7, "UNKNOWN")).isEqualTo(7);
  }

  @Test
  void parseStdoutJson_matchesPythonRules() {
    assertThat(LocalPythonOutputLoader.parseStdoutJson("  [{\"a\":1}]\n"))
        .containsExactly(Map.of("a", 1));
    // json.loads 는 뒤따르는 내용을 거부한다("Extra data") → 0행
    assertThat(LocalPythonOutputLoader.parseStdoutJson("[{\"a\":1}] extra")).isNull();
    assertThat(LocalPythonOutputLoader.parseStdoutJson("[1,2]")).isNull();
    assertThat(LocalPythonOutputLoader.parseStdoutJson("{\"a\":1}")).isNull();
    assertThat(LocalPythonOutputLoader.parseStdoutJson("x")).isNull();
    assertThat(LocalPythonOutputLoader.parseStdoutJson("[]")).isNull();
    assertThat(LocalPythonOutputLoader.parseStdoutJson("")).isNull();
  }

  /** 컬럼은 첫 행의 키 — 이후 행에 그 키가 없으면 적재 실패(Python KeyError), 여분 키는 무시된다. */
  @Test
  void laterRowMissingFirstRowKey_failsLikeKeyError() {
    List<Map<String, Object>> rows = new ArrayList<>();
    rows.add(new LinkedHashMap<>(Map.of("a", 1)));
    rows.add(new LinkedHashMap<>(Map.of("b", 2)));
    assertThatThrownBy(() -> LocalPythonOutputLoader.applyTypeConversion(rows, Map.of()))
        .hasMessageContaining("'a'");

    List<Map<String, Object>> extra = new ArrayList<>();
    extra.add(new LinkedHashMap<>(Map.of("a", 1)));
    extra.add(new LinkedHashMap<>(Map.of("a", 2, "z", 3)));
    LocalPythonOutputLoader.applyTypeConversion(extra, Map.of("a", "TEXT"));
    assertThat(extra.get(1).get("a")).isEqualTo("2");
  }
}
