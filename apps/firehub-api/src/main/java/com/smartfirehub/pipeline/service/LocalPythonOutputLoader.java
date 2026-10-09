package com.smartfirehub.pipeline.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로컬(실행기 끔) PYTHON 스텝의 stdout JSON 출력을 API 가 적재한다 — executor 계약의 Java 이식(R5, WD-29).
 *
 * <p><b>왜 API 가 적재하는가.</b> WD-29 부터 자식 프로세스는 읽기 전용 슬롯 롤로만 접속한다. 예전처럼 자식이 테넌트 실행 롤로 출력에 직접 쓰면 그 롤이
 * 등급 밖 데이터도 읽는다. executor 는 이미 "스크립트는 stdout 에 JSON 행 배열을 내고, 적재는 executor 가 테넌트 롤로 한다"는 계약이라, 로컬
 * 경로도 같은 계약을 따르게 해 두 경로에서 같은 스크립트가 같은 결과를 낸다.
 *
 * <p><b>이식 원본</b>(apps/firehub-executor): {@code python_executor.py} 의 {@code
 * _parse_stdout_json}·{@code _apply_type_conversion}·{@code _convert_single} 과 {@code
 * db_utils.insert_batch} 의 컬럼 처리. 형식·변환 규칙을 바꾸면 두 경로가 갈라지므로 원본과 함께 바꾼다. 원본은 Python 3.12(executor
 * 이미지) 의미를 기준으로 한다.
 *
 * <ul>
 *   <li>stdout 전체(앞뒤 공백 제거)가 JSON 이어야 한다. 뒤에 다른 내용이 붙으면 파싱 실패 → 적재 0행(Python {@code json.loads} 의
 *       "Extra data").
 *   <li>첫 원소가 객체인 비어 있지 않은 배열만 행으로 본다. 아니면 0행(오류 아님).
 *   <li>컬럼은 <b>첫 행의 키</b>(순서 유지). 이후 행에 그 키가 없거나 행이 객체가 아니면 적재 실패(Python {@code KeyError}/{@code
 *       TypeError}). 이후 행의 여분 키는 무시.
 *   <li>타입 변환 실패는 오류가 아니라 null(Python 쪽이 경고 로그 후 None).
 *   <li>모든 행을 한 트랜잭션으로 적재한다(executor 는 한 커넥션에서 마지막에 한 번 commit).
 * </ul>
 */
@Slf4j
@Component
public class LocalPythonOutputLoader {

  /** executor 의 적재 실패 문구와 같다 — 러너가 "Python 실행 실패: " 를 앞에 붙인다. */
  static final String INSERT_FAILED_PREFIX = "Script succeeded but data insert failed: ";

  /**
   * Python {@code json.loads} 와 같은 규칙의 파서: 뒤따르는 내용 거부, NaN/Infinity 허용, 실수는 Double(Python float).
   * 공유 ObjectMapper 를 바꾸지 않도록 전용 인스턴스를 쓴다.
   */
  private static final ObjectReader JSON =
      JsonMapper.builder()
          .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build()
          .readerFor(Object.class);

  private final DataTableRowService dataTableRowService;
  private final TransactionTemplate transactionTemplate;

  public LocalPythonOutputLoader(
      DataTableRowService dataTableRowService, PlatformTransactionManager txManager) {
    this.dataTableRowService = dataTableRowService;
    // 기본 전파(REQUIRED) — 러너는 트랜잭션 밖이라 새 트랜잭션이 열린다. 테넌트 GUC 는 @Primary 트랜잭션 매니저가 넣는다.
    this.transactionTemplate = new TransactionTemplate(txManager);
  }

  /**
   * stdout 을 파싱·변환해 {@code tableName}(현재 테넌트 데이터 스키마)에 적재한다.
   *
   * @return 적재한 행 수(JSON 이 없거나 행이 아니면 0 — REPLACE 는 이 값으로 맞바꿈 여부를 정한다, #685)
   * @throws ScriptExecutionException 적재 실패(executor 의 "Script succeeded but data insert failed" 와
   *     같은 문구)
   */
  public long load(String tableName, String stdout, Map<String, String> columnTypeMap) {
    List<Map<String, Object>> rows;
    try {
      rows = parseStdoutJson(stdout);
    } catch (RuntimeException e) {
      throw new ScriptExecutionException(INSERT_FAILED_PREFIX + e.getMessage(), e);
    }
    if (rows == null) {
      return 0;
    }
    try {
      applyTypeConversion(rows, columnTypeMap);
      List<String> columns = new ArrayList<>(rows.get(0).keySet());
      transactionTemplate.executeWithoutResult(
          status ->
              dataTableRowService.insertBatch(
                  tableName, columns, rows, geometryOnly(columnTypeMap)));
      return rows.size();
    } catch (RuntimeException e) {
      throw new ScriptExecutionException(INSERT_FAILED_PREFIX + e.getMessage(), e);
    }
  }

  /**
   * GEOMETRY 컬럼만 남긴 타입 맵 — 그 컬럼만 {@code DataTableRowService} 의 GeoJSON 자리표시자를 쓰게 한다. 나머지 컬럼은 이미 변환된
   * Java 값(Long·BigDecimal·Boolean·LocalDate·시각)을 캐스트 없이 바인딩한다. 전체 맵을 넘기면 INTEGER 가 {@code
   * ?::integer} 로 캐스트되는데 물리 컬럼은 BIGINT 라 큰 값이 넘친다(executor 는 캐스트 없이 바인딩).
   */
  private static Map<String, String> geometryOnly(Map<String, String> columnTypeMap) {
    if (columnTypeMap == null) {
      return null;
    }
    return columnTypeMap.entrySet().stream()
        .filter(
            e -> e.getValue() != null && e.getValue().toUpperCase(Locale.ROOT).contains("GEOMETRY"))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  // ---------------------------------------------------------------------------
  // _parse_stdout_json
  // ---------------------------------------------------------------------------

  /**
   * stdout 의 JSON 행 배열. 비었거나 JSON 이 아니거나 "객체로 시작하는 비어 있지 않은 배열"이 아니면 null. 첫 원소만 객체인지 보는 것도 원본과 같다
   * — 이후 원소가 객체가 아니면 적재 단계에서 실패한다.
   */
  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> parseStdoutJson(String stdout) {
    if (stdout == null || stdout.strip().isEmpty()) {
      return null;
    }
    Object data;
    try {
      data = JSON.readValue(stdout.strip());
    } catch (Exception e) {
      return null;
    }
    if (data instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map) {
      List<Map<String, Object>> rows = new ArrayList<>(list.size());
      for (Object element : list) {
        if (!(element instanceof Map)) {
          throw new IllegalArgumentException(
              "row is not a JSON object: " + (element == null ? "null" : pyStr(element)));
        }
        rows.add(new LinkedHashMap<>((Map<String, Object>) element));
      }
      return rows;
    }
    if (data instanceof List || data instanceof Map) {
      log.warn(
          "stdout JSON parsed but is not a list of dicts, ignoring: type={}",
          data instanceof List ? "list" : "dict");
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // _apply_type_conversion / _convert_single
  // ---------------------------------------------------------------------------

  /**
   * 컬럼 타입 맵에 따라 행 값을 제자리 변환한다. 맵이 비면 아무것도 하지 않는다. 그 뒤 첫 행의 키가 모든 행에 있는지 확인한다(원본의 {@code row[c]}
   * KeyError).
   */
  static void applyTypeConversion(List<Map<String, Object>> rows, Map<String, String> typeMap) {
    if (typeMap != null && !typeMap.isEmpty()) {
      for (Map<String, Object> row : rows) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
          if (e.getValue() == null) {
            continue;
          }
          String dtype = typeMap.get(e.getKey());
          if (dtype == null || dtype.isEmpty()) {
            continue;
          }
          e.setValue(convertSingle(e.getValue(), dtype));
        }
      }
    }
    Set<String> columns = rows.get(0).keySet();
    for (Map<String, Object> row : rows) {
      for (String column : columns) {
        if (!row.containsKey(column)) {
          throw new IllegalArgumentException("row is missing key '" + column + "'");
        }
      }
    }
  }

  /** Python 의 int() 문자열 문법(부호·밑줄 구분자). */
  private static final Pattern PY_INT = Pattern.compile("[+-]?[0-9](?:_?[0-9])*");

  /** Python Decimal() 문자열 문법 중 유한수(부호·밑줄·지수). */
  private static final Pattern PY_DECIMAL =
      Pattern.compile(
          "[+-]?(?:[0-9](?:_?[0-9])*(?:\\.(?:[0-9](?:_?[0-9])*)?)?|\\.[0-9](?:_?[0-9])*)(?:[eE][+-]?[0-9](?:_?[0-9])*)?");

  /** 값 하나를 대상 타입으로 바꾼다. 실패하면 null(원본과 같이 경고만). 알 수 없는 타입은 그대로. */
  static Object convertSingle(Object value, String dtype) {
    if (value == null) {
      return null;
    }
    String upper = dtype.toUpperCase(Locale.ROOT);
    try {
      switch (upper) {
        case "TEXT", "VARCHAR", "STRING" -> {
          return pyStr(value);
        }
        case "INTEGER" -> {
          String s = pyStr(value).strip();
          if (!PY_INT.matcher(s).matches()) {
            throw new NumberFormatException("invalid literal for int(): '" + s + "'");
          }
          BigInteger n = new BigInteger(s.replace("_", ""));
          return n.bitLength() < 64 ? (Object) n.longValue() : n;
        }
        case "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE" -> {
          String s = pyStr(value).strip();
          if (!PY_DECIMAL.matcher(s).matches()) {
            throw new NumberFormatException("invalid Decimal literal: '" + s + "'");
          }
          return new BigDecimal(s.replace("_", ""));
        }
        case "BOOLEAN" -> {
          if (value instanceof Boolean b) {
            return b;
          }
          String s = pyStr(value).strip().toLowerCase(Locale.ROOT);
          return s.equals("true") || s.equals("1") || s.equals("yes");
        }
        case "DATE" -> {
          return parseIsoDate(pyStr(value).strip());
        }
        case "TIMESTAMP", "DATETIME" -> {
          return parseIsoDateTime(pyStr(value).strip().replace("Z", "+00:00"));
        }
        default -> {
          return value;
        }
      }
    } catch (NumberFormatException | ArithmeticException | DateTimeException e) {
      log.warn("Type conversion failed for value={} dtype={}: {}", value, dtype, e.getMessage());
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Python str() 재현 — 변환은 str(value) 를 거치므로 JSON 값의 Python 표기가 결과를 정한다
  // ---------------------------------------------------------------------------

  /**
   * JSON 에서 온 값의 Python {@code str()} 표기. bool 은 {@code True/False}, 실수는 Python {@code repr}(최단
   * 표기·지수 규칙), 중첩 객체·배열은 Python {@code repr}({@code {'a': 1}}).
   */
  static String pyStr(Object value) {
    if (value instanceof String s) {
      return s;
    }
    return pyRepr(value);
  }

  private static String pyRepr(Object value) {
    if (value == null) {
      return "None";
    }
    if (value instanceof String s) {
      return pyStringRepr(s);
    }
    if (value instanceof Boolean b) {
      return b ? "True" : "False";
    }
    if (value instanceof Double d) {
      return pyFloatRepr(d);
    }
    if (value instanceof Float f) {
      return pyFloatRepr(f.doubleValue());
    }
    if (value instanceof BigDecimal bd) {
      return pyFloatRepr(bd.doubleValue());
    }
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .map(e -> pyRepr(e.getKey()) + ": " + pyRepr(e.getValue()))
          .collect(Collectors.joining(", ", "{", "}"));
    }
    if (value instanceof List<?> list) {
      return list.stream()
          .map(LocalPythonOutputLoader::pyRepr)
          .collect(Collectors.joining(", ", "[", "]"));
    }
    // Integer·Long·BigInteger
    return value.toString();
  }

  /** Python 문자열 repr — 작은따옴표 우선, 작은따옴표만 들어 있으면 큰따옴표. 흔한 이스케이프만 재현한다. */
  private static String pyStringRepr(String s) {
    char quote = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
    StringBuilder out = new StringBuilder().append(quote);
    for (char c : s.toCharArray()) {
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c == quote) {
            out.append('\\').append(c);
          } else if (c < 0x20 || c == 0x7f) {
            out.append(String.format("\\x%02x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append(quote).toString();
  }

  /**
   * Python float repr: 최단 왕복 자릿수(Java 19+ {@link Double#toString} 과 같은 자릿수), 십진 지수가 -4 이상 16 미만이면
   * 고정 소수점(최소 ".0"), 아니면 {@code 1.5e+16}·{@code 1e-05} 꼴.
   */
  static String pyFloatRepr(double d) {
    if (Double.isNaN(d)) {
      return "nan";
    }
    if (Double.isInfinite(d)) {
      return d > 0 ? "inf" : "-inf";
    }
    if (d == 0) {
      return (1 / d < 0) ? "-0.0" : "0.0";
    }
    BigDecimal bd = new BigDecimal(Double.toString(d)).stripTrailingZeros();
    String sign = bd.signum() < 0 ? "-" : "";
    String digits = bd.unscaledValue().abs().toString();
    int exp10 = digits.length() - 1 - bd.scale();
    if (exp10 >= -4 && exp10 < 16) {
      String plain = bd.abs().toPlainString();
      return sign + (plain.contains(".") ? plain : plain + ".0");
    }
    String mantissa = digits.length() > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
    String exp = String.format("%02d", Math.abs(exp10));
    return sign + mantissa + "e" + (exp10 < 0 ? "-" : "+") + exp;
  }

  // ---------------------------------------------------------------------------
  // date.fromisoformat / datetime.fromisoformat (Python 3.12)
  // ---------------------------------------------------------------------------

  private static final DateTimeFormatter EXTENDED_DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter BASIC_DATE =
      DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern WEEK_DATE = Pattern.compile("(\\d{4})-?W(\\d{2})(?:-?(\\d))?");

  /** Python 3.12 date.fromisoformat — YYYY-MM-DD, YYYYMMDD, YYYY-Www[-D]/YYYYWww[D]. */
  static LocalDate parseIsoDate(String s) {
    if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
      return LocalDate.parse(s, EXTENDED_DATE);
    }
    if (s.matches("\\d{8}")) {
      return LocalDate.parse(s, BASIC_DATE);
    }
    Matcher w = WEEK_DATE.matcher(s);
    if (w.matches()) {
      int year = Integer.parseInt(w.group(1));
      int week = Integer.parseInt(w.group(2));
      int day = w.group(3) == null ? 1 : Integer.parseInt(w.group(3));
      if (week < 1 || day < 1 || day > 7) {
        throw new DateTimeException("Invalid isoformat string: '" + s + "'");
      }
      LocalDate weekOne =
          LocalDate.of(year, 1, 4).with(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR, 1);
      LocalDate result =
          weekOne.with(java.time.DayOfWeek.MONDAY).plusWeeks(week - 1L).plusDays(day - 1L);
      if (result.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR) != year) {
        throw new DateTimeException("Invalid week: " + week);
      }
      return result;
    }
    throw new DateTimeException("Invalid isoformat string: '" + s + "'");
  }

  private static final Pattern TIME =
      Pattern.compile(
          "(\\d{2})(?::?(\\d{2})(?::?(\\d{2})(?:[.,](\\d+))?)?)?"
              + "(?:([+-])(\\d{2})(?::?(\\d{2})(?::?(\\d{2})(?:[.,](\\d+))?)?)?)?");

  /**
   * Python 3.12 datetime.fromisoformat — 날짜(위와 같은 형식) + 선택적 [구분자 한 글자 + 시각[.분수] + 오프셋]. 분수는 6자리 넘으면
   * 잘린다. 오프셋이 있으면 {@link OffsetDateTime}(timestamptz 로 바인딩 — psycopg2 의 aware datetime 과 같다), 없으면
   * {@link LocalDateTime}.
   */
  static Object parseIsoDateTime(String s) {
    int dateLen;
    if (s.length() >= 10 && s.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}")) {
      dateLen = 10;
    } else if (s.length() >= 8 && s.substring(0, 8).matches("\\d{8}")) {
      dateLen = 8;
    } else {
      // 주 날짜 형식(드묾)은 시각 없는 경우만 받는다.
      return parseIsoDate(s).atStartOfDay();
    }
    LocalDate date = parseIsoDate(s.substring(0, dateLen));
    if (s.length() == dateLen) {
      return date.atStartOfDay();
    }
    if (s.length() < dateLen + 2) {
      throw new DateTimeException("Invalid isoformat string: '" + s + "'");
    }
    Matcher t = TIME.matcher(s.substring(dateLen + 1));
    if (!t.matches()) {
      throw new DateTimeException("Invalid isoformat string: '" + s + "'");
    }
    LocalTime time =
        LocalTime.of(
            Integer.parseInt(t.group(1)),
            t.group(2) == null ? 0 : Integer.parseInt(t.group(2)),
            t.group(3) == null ? 0 : Integer.parseInt(t.group(3)),
            micros(t.group(4)) * 1000);
    LocalDateTime local = LocalDateTime.of(date, time);
    if (t.group(5) == null) {
      return local;
    }
    int sign = "-".equals(t.group(5)) ? -1 : 1;
    int offsetSeconds =
        sign
            * (Integer.parseInt(t.group(6)) * 3600
                + (t.group(7) == null ? 0 : Integer.parseInt(t.group(7)) * 60)
                + (t.group(8) == null ? 0 : Integer.parseInt(t.group(8))));
    return OffsetDateTime.of(local, ZoneOffset.ofTotalSeconds(offsetSeconds));
  }

  /** 분수 초 문자열 → 마이크로초(6자리 초과는 잘라냄, 모자라면 0 채움). */
  private static int micros(String fraction) {
    if (fraction == null) {
      return 0;
    }
    String six = fraction.length() > 6 ? fraction.substring(0, 6) : fraction;
    return Integer.parseInt((six + "000000").substring(0, 6));
  }
}
