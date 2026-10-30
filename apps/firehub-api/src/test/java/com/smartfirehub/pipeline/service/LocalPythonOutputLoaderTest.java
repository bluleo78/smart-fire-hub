package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
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

  /** 바꿀 수 없는 값 — WD-431 부터 null 이 아니라 변환 실패(행·키는 applyTypeConversion 이 붙인다). */
  private static void fails(Object value, String dtype) {
    assertThatThrownBy(() -> c(value, dtype))
        .as(value + " → " + dtype)
        .isInstanceOf(ValueConversion.Failed.class);
  }

  @Test
  void integer_matchesPythonInt() {
    assertThat(c("42", "INTEGER")).isEqualTo(42L);
    assertThat(c(" +1_000 ", "INTEGER")).isEqualTo(1000L);
    assertThat(c(42, "integer")).as("타입 이름은 대소문자 무시").isEqualTo(42L);
    // WD-431: 정수 값인 실수 표기는 받는다(3.0·"3.0"·"1e2") — 실행기 value_conversion.to_int_strict 와 같은 규칙
    assertThat(c("1.0", "INTEGER")).isEqualTo(1L);
    assertThat(c(1.0d, "INTEGER")).isEqualTo(1L);
    assertThat(c("1e2", "INTEGER")).isEqualTo(100L);
    assertThat(c(1e2d, "INTEGER")).isEqualTo(100L);
    // 소수부·64비트 범위 밖·불리언은 변환 실패(예전엔 None, 64비트 초과는 BigInteger 로 통과했다)
    fails("1.5", "INTEGER");
    fails(1.5d, "INTEGER");
    fails(1e20d, "INTEGER");
    fails("9223372036854775808", "INTEGER");
    fails(new BigInteger("12345678901234567890"), "INTEGER");
    fails("1e999999999", "INTEGER");
    fails(true, "INTEGER");
    assertThat(c(Long.MAX_VALUE, "INTEGER")).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void decimal_matchesPythonDecimalOfStr() {
    assertThat(c("1.50", "DECIMAL")).isEqualTo(new BigDecimal("1.50"));
    assertThat(c(0.1d, "DECIMAL")).isEqualTo(new BigDecimal("0.1"));
    assertThat(c(1e-05d, "NUMERIC")).isEqualTo(new BigDecimal("0.00001"));
    assertThat(c(1e16d, "FLOAT")).isEqualTo(new BigDecimal("1E+16"));
    assertThat(c("1_000.5", "DOUBLE")).isEqualTo(new BigDecimal("1000.5"));
    fails("abc", "DECIMAL");
    fails(true, "DECIMAL");
  }

  /**
   * 비유한수: executor 는 Decimal(str(v)) 로 NaN/Infinity 를 만들고 psycopg2 가 그 전부를 'NaN'::numeric 으로
   * 보낸다(CR6) — 로컬 경로도 Double.NaN 을 넘겨 NUMERIC 에 NaN 이 들어가게 한다. Python 이 거부하는 철자(infinit·nan.5)는 변환
   * 실패(WD-431).
   */
  @Test
  void decimal_nonFinite_becomesNaNLikePsycopg2() {
    assertThat(c(Double.NaN, "DECIMAL")).isEqualTo(Double.NaN);
    assertThat(c(Double.POSITIVE_INFINITY, "NUMERIC")).isEqualTo(Double.NaN);
    assertThat(c(Double.NEGATIVE_INFINITY, "FLOAT")).isEqualTo(Double.NaN);
    for (String s : List.of("NaN", " -nan ", "Infinity", "-inf", "inFINity", "sNaN5", "nan1_0")) {
      assertThat(c(s, "DOUBLE")).as(s).isEqualTo(Double.NaN);
    }
    fails("infinit", "DECIMAL");
    fails("nan.5", "DECIMAL");
    // INTEGER·TEXT 는 그대로 Python 규칙: int('nan') 은 ValueError, str(nan) 은 'nan'
    fails(Double.NaN, "INTEGER");
    assertThat(c(Double.POSITIVE_INFINITY, "TEXT")).isEqualTo("inf");
  }

  /** WD-431: true/false/1/0/yes/no(대소문자·공백 무시)와 JSON 불리언·숫자 1/0 만 받는다 — 그 밖의 값은 거짓이 아니라 변환 실패. */
  @Test
  void boolean_acceptsOnlyKnownTokens() {
    assertThat(c(true, "BOOLEAN")).isEqualTo(true);
    assertThat(c(false, "BOOLEAN")).isEqualTo(false);
    assertThat(c("Yes", "BOOLEAN")).isEqualTo(true);
    assertThat(c(" TRUE ", "BOOLEAN")).isEqualTo(true);
    assertThat(c("yes", "BOOLEAN")).isEqualTo(true);
    assertThat(c("1", "BOOLEAN")).isEqualTo(true);
    assertThat(c(1, "BOOLEAN")).isEqualTo(true);
    assertThat(c(1.0d, "BOOLEAN")).as("값이 1 과 같은 실수").isEqualTo(true);
    assertThat(c("false", "BOOLEAN")).isEqualTo(false);
    assertThat(c("0", "BOOLEAN")).isEqualTo(false);
    assertThat(c(0, "BOOLEAN")).isEqualTo(false);
    assertThat(c("no", "BOOLEAN")).isEqualTo(false);
    fails("Y", "BOOLEAN");
    fails("예", "BOOLEAN");
    fails("N", "BOOLEAN");
    fails(2, "BOOLEAN");
    fails(Double.NaN, "BOOLEAN");
  }

  /** WD-431: 숫자·불리언·날짜 타입의 빈 문자열(공백뿐 포함)은 NULL(데이터 임포트와 같은 규칙), 텍스트는 그대로. */
  @Test
  void blankString_isNullForNonTextTypes() {
    for (String t : List.of("INTEGER", "DECIMAL", "NUMERIC", "BOOLEAN", "DATE", "TIMESTAMP")) {
      assertThat(c("", t)).as(t).isNull();
      assertThat(c("  ", t)).as(t).isNull();
    }
    assertThat(c("", "TEXT")).isEqualTo("");
  }

  /**
   * WD-431: 바꿀 수 없는 값은 적재 전에 행 번호(1부터)·키·값·타입을 짚는 한국어 문구로 실패한다 — 예전엔 경고 로그 후 NULL 로 넣고 「완료」였다. 문구는
   * executor(test_python_executor.py 의 test_value_conversion_failure_reports_row_key_value_type)와
   * 같은 입력에 대해 글자 단위로 같고, 러너 접두가 붙는다. DB 는 건드리지 않는다.
   */
  @Test
  void conversionFailure_failsBeforeInsertWithRowKeyValueType() {
    DataTableRowService rowService = mock(DataTableRowService.class);
    String stdout =
        "[{\"name\": \"a\", \"n\": 1}, {\"name\": \"b\", \"n\": 2},"
            + " {\"name\": \"c\", \"n\": \"not-a-number\"}]";
    assertThatThrownBy(
            () -> loaderWith(rowService).load("t", stdout, Map.of("name", "TEXT", "n", "INTEGER")))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage(
            "Python 실행 실패: 출력 JSON 의 3번째 행 'n' 값 'not-a-number' 을(를) 정수(INTEGER)로 변환할 수 없습니다."
                + " 출력 데이터셋 컬럼 타입에 맞는 값으로 출력해 주세요.");
    verifyNoInteractions(rowService);
  }

  /** WD-431: 소수·날짜·날짜시간·불리언 실패 문구 — 타입 라벨은 데이터 임포트와 같고, 불리언은 허용 값을 덧붙인다. */
  @Test
  void conversionFailure_messagesPerType() {
    assertThat(convertFailure("DECIMAL", "\"1,2x\""))
        .isEqualTo(
            "Python 실행 실패: 출력 JSON 의 1번째 행 'v' 값 '1,2x' 을(를) 소수(DECIMAL)로 변환할 수 없습니다."
                + " 출력 데이터셋 컬럼 타입에 맞는 값으로 출력해 주세요.");
    assertThat(convertFailure("DATE", "\"2024-13-40\""))
        .contains("'v' 값 '2024-13-40' 을(를) 날짜(DATE)로 변환할 수 없습니다.");
    assertThat(convertFailure("TIMESTAMP", "\"nope\""))
        .contains("'v' 값 'nope' 을(를) 날짜시간(TIMESTAMP)으로 변환할 수 없습니다.");
    assertThat(convertFailure("BOOLEAN", "\"예\""))
        .contains("'v' 값 '예' 을(를) 참/거짓(BOOLEAN)으로 변환할 수 없습니다(허용 값: true/false/1/0/yes/no).");
    // 값은 Python str() 표기로 인용한다(실행기 켠 경로와 같은 글자)
    assertThat(convertFailure("INTEGER", "true")).contains("'v' 값 'True' 을(를) 정수(INTEGER)로");
    assertThat(convertFailure("INTEGER", "1.5")).contains("'v' 값 '1.5' 을(를) 정수(INTEGER)로");
  }

  /** WD-431: 긴 값은 50 코드 포인트로 잘라 「…」을 붙인다(사용자 데이터가 길게 실리지 않게). */
  @Test
  void conversionFailure_truncatesLongValue() {
    String longValue = "x".repeat(49) + "\uD83D\uDE00" + "TAIL";
    String message = convertFailure("INTEGER", "\"" + longValue + "\"");
    assertThat(message)
        .contains("'v' 값 '" + "x".repeat(49) + "\uD83D\uDE00…' 을(를)")
        .doesNotContain("TAIL");
  }

  private static String convertFailure(String dtype, String jsonValue) {
    try {
      loaderWith(mock(DataTableRowService.class))
          .load("t", "[{\"v\": " + jsonValue + "}]", Map.of("v", dtype));
    } catch (ScriptExecutionException e) {
      return e.getMessage();
    }
    throw new AssertionError("변환 실패가 나야 한다: " + dtype + " " + jsonValue);
  }

  @Test
  void date_matchesPythonFromisoformat() {
    assertThat(c("2024-02-29", "DATE")).isEqualTo(LocalDate.of(2024, 2, 29));
    assertThat(c("20240301", "DATE")).isEqualTo(LocalDate.of(2024, 3, 1));
    assertThat(c("2024-W01-1", "DATE")).isEqualTo(LocalDate.of(2024, 1, 1));
    fails("2023-02-29", "DATE");
    fails("2024-01-01T00:00", "DATE");
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
    fails("nope", "TIMESTAMP");
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
   * "Python 실행 실패: " 가 붙는다. 적재 실패 문구(PythonStepErrorMessages)로 바뀌지 않는다. DB 는 건드리지 않는다.
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
   * WD-211: 첫 행 뒤 원소가 객체가 아니면 파싱 단계(DB 접근 전)에서 행 번호(1부터)를 짚는 한국어 문구로 실패한다 — 예전엔 「… row is not a
   * JSON object: 3」. 문구는 executor(test_python_executor.py 의
   * test_row_not_object_reports_row_number)와 같은 입력에 대해 글자 단위로 같고, 러너 접두 "Python 실행 실패: " 가 붙는다(로컬
   * 경로는 러너가 감싸지 않는다).
   */
  @Test
  void rowNotObject_reportsRowNumberInKorean() {
    DataTableRowService rowService = mock(DataTableRowService.class);
    assertThatThrownBy(
            () ->
                loaderWith(rowService)
                    .load("t", "[{\"name\": \"a\"}, {\"name\": \"b\"}, 3]", Map.of("name", "TEXT")))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage(
            "Python 실행 실패: 출력 JSON 의 3번째 행이 객체가 아닙니다. 모든 행을 컬럼 이름을 키로 하는 JSON 객체로 출력해 주세요.");
    verifyNoInteractions(rowService);
  }

  /**
   * WD-211: 첫 행의 키가 출력 데이터셋 컬럼에 없으면 적재 전에 그 키를 짚어 실패한다 — 예전엔 jOOQ bad SQL grammar 원문(내부
   * 스키마·INSERT).
   */
  @Test
  void unknownColumnKey_failsBeforeInsertNamingTheKey() {
    DataTableRowService rowService = mock(DataTableRowService.class);
    assertThatThrownBy(
            () -> loaderWith(rowService).load("t", "[{\"nocol\": \"a\"}]", Map.of("name", "TEXT")))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage(
            "Python 실행 실패: 출력 JSON 의 'nocol' 가 출력 데이터셋 컬럼에 없습니다. 데이터셋 컬럼 이름과 같은 키로 출력해 주세요.");
    verifyNoInteractions(rowService);
  }

  /** WD-211: 시스템 컬럼(created_at 등) 키는 데이터셋 컬럼 목록에 없어도 예전처럼 DB 판단에 맡긴다 — 이 확인으로 동작이 바뀌지 않는다. */
  @Test
  void systemColumnKey_isNotRejectedByColumnCheck() {
    DataTableRowService rowService = mock(DataTableRowService.class);
    LocalPythonOutputLoader loader = loaderWith(rowService);
    assertThat(
            loader.load("t", "[{\"name\": \"a\", \"created_at\": \"x\"}]", Map.of("name", "TEXT")))
        .isEqualTo(1);
  }

  /** WD-211: 이후 행에 첫 행의 키가 없으면 행 번호와 키를 짚는다 — executor 와 같은 문구. */
  @Test
  void rowMissingFirstRowKey_reportsRowNumberAndKey() {
    DataTableRowService rowService = mock(DataTableRowService.class);
    assertThatThrownBy(
            () ->
                loaderWith(rowService)
                    .load(
                        "t",
                        "[{\"name\": \"a\"}, {\"other\": \"b\"}]",
                        Map.of("name", "TEXT", "other", "TEXT")))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage(
            "Python 실행 실패: 출력 JSON 의 2번째 행에 첫 행의 키 'name' 가 없습니다. 모든 행에 첫 행과 같은 키를 넣어 주세요.");
    verifyNoInteractions(rowService);
  }

  /** WD-211: 분류하지 못한 적재 실패는 원문(SQL·스키마)을 숨기고 일반 한국어 안내만 보인다. */
  @Test
  void otherInsertFailure_hidesRawSqlBehindGenericMessage() {
    DataTableRowService rowService = mock(DataTableRowService.class);
    doThrow(
            new BadSqlGrammarException(
                "jOOQ",
                "INSERT INTO data.\"r8_out\" (\"name\") VALUES (?)",
                new SQLException("column does not exist", "42703")))
        .when(rowService)
        .insertBatch(anyString(), anyList(), anyList(), anyMap());
    LocalPythonOutputLoader loader = loaderWith(rowService);
    assertThatThrownBy(() -> loader.load("r8_out", "[{\"name\": \"a\"}]", Map.of("name", "TEXT")))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage("Python 실행 실패: 출력 결과를 데이터셋에 적재하지 못했습니다. 출력 JSON 의 키와 값 형식을 확인해 주세요.");
  }

  /** WD-211: DB 데이터 오류(22xxx)는 DataExceptionTranslator 사유 문장을 재사용한다(원문 없이). */
  @Test
  void dataErrorOnInsert_usesDataExceptionTranslatorReason() {
    DataTableRowService rowService = mock(DataTableRowService.class);
    doThrow(
            new DataIntegrityViolationException(
                "jOOQ; SQL [INSERT INTO data.\"r8_out\" ...]",
                new SQLException("value too long", "22001")))
        .when(rowService)
        .insertBatch(anyString(), anyList(), anyList(), anyMap());
    LocalPythonOutputLoader loader = loaderWith(rowService);
    assertThatThrownBy(() -> loader.load("r8_out", "[{\"name\": \"a\"}]", Map.of("name", "TEXT")))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage(
            "Python 실행 실패: 출력 결과를 데이터셋에 적재하지 못했습니다: 글자 수 제한을 넘는 값이 있습니다. 출력 JSON 의 값이 데이터셋 컬럼의"
                + " 타입·형식·길이에 맞는지 확인해 주세요.")
        .hasMessageNotContaining("INSERT");
  }

  /**
   * WD-211: 타임아웃·예기치 못한 오류 문구 — executor(TIMEOUT_MESSAGE·UNEXPECTED_ERROR_MESSAGE)와 같은 문장에 러너 접두.
   */
  @Test
  void timeoutAndUnexpectedMessages_matchExecutor() {
    assertThat(PythonStepErrorMessages.timeout(300))
        .isEqualTo("Python 실행 실패: 실행 시간 제한(300초)을 넘어 스크립트를 중단했습니다.");
    assertThat(PythonStepErrorMessages.unexpectedError())
        .isEqualTo("Python 실행 실패: 스크립트 실행 중 예기치 못한 오류가 발생했습니다. 잠시 후 다시 실행하고, 계속되면 관리자에게 문의해 주세요.");
  }
}
