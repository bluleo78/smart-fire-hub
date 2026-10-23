package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
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
import org.springframework.transaction.PlatformTransactionManager;

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

  /**
   * 비유한수: executor 는 Decimal(str(v)) 로 NaN/Infinity 를 만들고 psycopg2 가 그 전부를 'NaN'::numeric 으로
   * 보낸다(CR6) — 로컬 경로도 Double.NaN 을 넘겨 NUMERIC 에 NaN 이 들어가게 한다. Python 이 거부하는 철자(infinit·nan.5)는
   * None.
   */
  @Test
  void decimal_nonFinite_becomesNaNLikePsycopg2() {
    assertThat(c(Double.NaN, "DECIMAL")).isEqualTo(Double.NaN);
    assertThat(c(Double.POSITIVE_INFINITY, "NUMERIC")).isEqualTo(Double.NaN);
    assertThat(c(Double.NEGATIVE_INFINITY, "FLOAT")).isEqualTo(Double.NaN);
    for (String s : List.of("NaN", " -nan ", "Infinity", "-inf", "inFINity", "sNaN5", "nan1_0")) {
      assertThat(c(s, "DOUBLE")).as(s).isEqualTo(Double.NaN);
    }
    assertThat(c("infinit", "DECIMAL")).isNull();
    assertThat(c("nan.5", "DECIMAL")).isNull();
    // INTEGER·TEXT 는 그대로 Python 규칙: int('nan') 은 ValueError, str(nan) 은 'nan'
    assertThat(c(Double.NaN, "INTEGER")).isNull();
    assertThat(c(Double.POSITIVE_INFINITY, "TEXT")).isEqualTo("inf");
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
    // json.loads 는 뒤따르는 내용을 거부한다("Extra data") → JSON 아님으로 실패(WD-210, 예전엔 0행)
    assertThatThrownBy(() -> LocalPythonOutputLoader.parseStdoutJson("[{\"a\":1}] extra"))
        .isInstanceOf(LocalPythonOutputLoader.StdoutNotJsonException.class);
    assertThat(LocalPythonOutputLoader.parseStdoutJson("[1,2]")).isNull();
    assertThat(LocalPythonOutputLoader.parseStdoutJson("{\"a\":1}")).isNull();
    assertThatThrownBy(() -> LocalPythonOutputLoader.parseStdoutJson("x"))
        .isInstanceOf(LocalPythonOutputLoader.StdoutNotJsonException.class);
    // JSON 이지만 행 배열이 아닌 값은 기존대로 0행(오류 아님)
    assertThat(LocalPythonOutputLoader.parseStdoutJson("123")).isNull();
    assertThat(LocalPythonOutputLoader.parseStdoutJson("[]")).isNull();
    // json.dumps 는 float nan/inf 를 NaN/Infinity/-Infinity 토큰으로 쓰고 json.loads 는 받아들인다
    assertThat(
            LocalPythonOutputLoader.parseStdoutJson("[{\"a\":NaN,\"b\":Infinity,\"c\":-Infinity}]"))
        .containsExactly(
            Map.of("a", Double.NaN, "b", Double.POSITIVE_INFINITY, "c", Double.NEGATIVE_INFINITY));
    assertThat(LocalPythonOutputLoader.parseStdoutJson("")).isNull();
    assertThat(LocalPythonOutputLoader.parseStdoutJson("  \n\t\n")).isNull();
  }

  private static LocalPythonOutputLoader loaderWith(DataTableRowService rowService) {
    return new LocalPythonOutputLoader(rowService, mock(PlatformTransactionManager.class));
  }

  /**
   * WD-210: 디버그 print 뒤 JSON 은 적재 전에 실패한다 — 예전엔 조용히 0행 "완료"였다. 문구는 executor
   * (test_python_executor.py 의 DEBUG_THEN_JSON_MESSAGE)와 같은 입력에 대해 글자 단위로 같고, 실행기 켠 경로처럼 러너 접두
   * "Python 실행 실패: " 가 붙는다. 적재 실패 접두(INSERT_FAILED_PREFIX)는 붙지 않는다. DB 는 건드리지 않는다.
   */
  @Test
  void debugPrintThenJson_failsBeforeInsertWithExecutorMessage() {
    DataTableRowService rowService = mock(DataTableRowService.class);
    assertThatThrownBy(() -> loaderWith(rowService).load("t", "start\n[{\"a\": 1}]\n", Map.of()))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage(
            "Python 실행 실패: 스크립트 출력이 JSON 이 아니어서 결과를 적재하지 않았습니다. 출력 데이터셋을 쓰는 스크립트는 결과 JSON"
                + " 한 번만 print 해야 합니다(디버그 출력은 sys.stderr 로). 출력 앞부분: \"start\n[{\"a\": 1}]\"");
    verifyNoInteractions(rowService);
  }

  /** JSON 이 아닌 문자열만 print 해도 실패한다. */
  @Test
  void plainTextStdout_fails() {
    DataTableRowService rowService = mock(DataTableRowService.class);
    assertThatThrownBy(() -> loaderWith(rowService).load("t", "plain text output\n", Map.of()))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageStartingWith("Python 실행 실패: 스크립트 출력이 JSON 이 아니어서 결과를 적재하지 않았습니다.")
        .hasMessageEndingWith("출력 앞부분: \"plain text output\"");
    verifyNoInteractions(rowService);
  }

  /** 앞부분 인용은 100 코드 포인트 이하 — 비밀이 섞일 수 있어 뒤는 잘라낸다(말줄임표 없음). 서로게이트 쌍도 한 글자로 센다. */
  @Test
  void notJsonExcerpt_isCappedAt100CodePoints() {
    String hundred = "\uD83D\uDE00".repeat(100); // 이모지 100자 = UTF-16 200단위
    String message = LocalPythonOutputLoader.notJsonMessage(hundred + "SECRET_AFTER_100");
    assertThat(message).endsWith("출력 앞부분: \"" + hundred + "\"").doesNotContain("SECRET");
  }

  /** 빈 stdout·공백뿐인 stdout 은 지금처럼 0행(실패 아님) — DB 무접촉. */
  @Test
  void emptyOrBlankStdout_keepsZeroRows() {
    DataTableRowService rowService = mock(DataTableRowService.class);
    LocalPythonOutputLoader loader = loaderWith(rowService);
    assertThat(loader.load("t", "", Map.of())).isZero();
    assertThat(loader.load("t", " \n\t ", Map.of())).isZero();
    assertThat(loader.load("t", null, Map.of())).isZero();
    verifyNoInteractions(rowService);
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

  /**
   * 적재 실패 문구는 실행기 켠 경로의 사용자 문구("Python 실행 실패: " + executor 오류)와 같다 — 로컬 경로는 러너가 이 예외를 감싸지 않는다(Task
   * 5 리뷰 M1). 첫 행 뒤 원소가 객체가 아니면 파싱 단계에서 실패한다(DB 접근 전).
   */
  @Test
  void loadFailure_usesSameUserMessageAsExecutorPath() {
    LocalPythonOutputLoader loader =
        new LocalPythonOutputLoader(
            mock(DataTableRowService.class), mock(PlatformTransactionManager.class));
    assertThatThrownBy(() -> loader.load("t", "[{\"a\": 1}, 2]", Map.of()))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageStartingWith("Python 실행 실패: Script succeeded but data insert failed: ");
  }
}
