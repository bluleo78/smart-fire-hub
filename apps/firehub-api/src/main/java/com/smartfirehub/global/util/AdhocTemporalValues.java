package com.smartfirehub.global.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;

/**
 * 애드혹 SQL 결과의 날짜·시각 값이 Java 날짜 객체를 거치며 <b>다른 값으로 바뀌지 않게</b> 하는 ResultSet 래퍼(#768).
 *
 * <p><b>왜 필요한가.</b> jOOQ 는 PG {@code date}/{@code timestamp}/{@code time} 을 pgjdbc 의 {@code java.sql.Date}/
 * {@code Timestamp}/{@code Time} 으로, {@code timestamptz}/{@code timetz} 를 {@code OffsetDateTime}/{@code OffsetTime}
 * 으로 읽는다. PostgreSQL 은 허용하지만 이 객체들이 표현하지 못하는 값은 <b>오류 없이 다른 값</b>이 됐다:
 *
 * <ul>
 *   <li>5자리 연도 — {@code 10000-01-01} → {@code 0000-01-01}, {@code 12345-06-07} → {@code 2345-06-07}(4자리 절삭)
 *   <li>기원전 — {@code 0044-03-15 BC} → {@code 0044-03-15}(기원 소실)
 *   <li>{@code infinity} — pgjdbc 센티넬 millis 가 {@code 8994-08-17} 같은 날짜로 보임. timestamptz 는 읽기 예외로
 *       쿼리 전체 실패
 *   <li>{@code 24:00:00}(time/timetz) → {@code 00:00:00}
 *   <li>Java 혼합 달력·JVM 시간대의 공백 — {@code 1582-10-10} → {@code 1582-10-20}, DST 공백 시각이 한 시간 밀림
 *   <li>interval — jOOQ 가 시간을 일로 정규화({@code 24:00:00} → {@code 1 day})하거나 int 범위를 넘으면 읽기 예외
 * </ul>
 *
 * <p><b>방법.</b> 날짜·시각 컬럼을 읽을 때 pgjdbc 의 PG 텍스트({@code getString})를 함께 본다. 값이 범위 밖
 * (infinity·BC·5자리 연도·24시)이거나, pgjdbc 가 만든 Java 객체가 그 텍스트를 그대로 재현하지 못하거나, 읽기가
 * 실패하면 jOOQ 에는 null 을 주고 결과를 다 읽은 뒤 그 셀을 <b>PG 텍스트 원문</b>으로 채운다. interval 은 응답이
 * 원래 PG 텍스트이므로 늘 원문을 쓴다. 정상 값은 jOOQ 가 읽은 그대로라 기존 응답 형태(타임존 처리 포함)가 같다.
 *
 * <p><b>executor 경로와 같은 계약.</b> executor(psycopg2, #762)는 변환할 수 없는 스칼라를 그 값만 PG 리터럴 텍스트로,
 * 배열은 셀 전체를 PG 배열 리터럴 텍스트로 폴백한다. 여기서도 같다 — 날짜·시각 배열의 원소 하나라도 위 조건에
 * 걸리면 셀 전체가 배열 리터럴({@code {10000-01-01,2024-01-01}})이 된다. interval 배열은 원래 원소가 텍스트이므로
 * 원소별 PG 텍스트의 (중첩) 리스트로 준다.
 *
 * <p>{@link AdhocMultiDimArrays} 보다 안쪽에 둔다 — 그래야 다차원 배열도 이 래퍼가 먼저 보고 폴백할 수 있다.
 */
final class AdhocTemporalValues {

  /** 컬럼 분류. */
  private enum Kind {
    NONE,
    /** date/timestamp/timestamptz/time/timetz 스칼라 */
    DATETIME,
    /** interval 스칼라 */
    INTERVAL,
    /** 날짜·시각 배열 */
    DATETIME_ARRAY,
    /** interval 배열 */
    INTERVAL_ARRAY
  }

  /** 5자리 이상 연도(10000년 이후). PG 는 DateStyle ISO(pgjdbc 가 강제)로 연도를 맨 앞에 쓴다. */
  private static final Pattern BIG_YEAR = Pattern.compile("^\\d{5,}-.*");

  /** 배열 리터럴 앞의 차원 장식({@code [0:1]=}). */
  private static final Pattern BOUNDS_PREFIX = Pattern.compile("^(?:\\[-?\\d+:-?\\d+\\])+=");

  private final ResultSet delegate;

  /** 1-based 컬럼 인덱스 → 분류. 첫 가로채기 때 메타데이터로 채운다. */
  private Kind[] kinds;

  /**
   * 1-based 컬럼 인덱스 → Java 값의 텍스트 재현 검사 대상인가. 시간대 없는 date/timestamp/time(및 배열)만이다 —
   * timestamptz/timetz 배열은 pgjdbc 가 {@code java.sql.Timestamp} 로 주므로 PG 텍스트(오프셋 포함)와 문자열 비교가
   * 성립하지 않는다(스칼라는 jOOQ 가 PG 텍스트를 파싱한다).
   */
  private boolean[] roundTrip;

  /** 현재 행 번호(0-based). */
  private int row = -1;

  /** 직전 getter 가 폴백 때문에 null 을 줬는가 — {@code wasNull()} 이 원본과 어긋나지 않게 한다. */
  private boolean forcedNull;

  /** (행 → (1-based 컬럼 인덱스 → 대체 값)). */
  private final Map<Integer, Map<Integer, Object>> replaced = new HashMap<>();

  AdhocTemporalValues(ResultSet delegate) {
    this.delegate = delegate;
  }

  /** jOOQ(또는 바깥 래퍼)에 넘길 감싼 ResultSet. */
  ResultSet proxy() {
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (p, m, args) -> handle(m, args));
  }

  private Object handle(Method m, Object[] args) throws Throwable {
    String name = m.getName();
    if (name.equals("wasNull") && (args == null || args.length == 0)) {
      return forcedNull || (Boolean) invoke(m, delegate, args);
    }
    forcedNull = false;
    if (name.startsWith("get")
        && !name.equals("getMetaData")
        && args != null
        && args.length >= 1
        && args[0] instanceof Integer idx) {
      Kind kind = kindOf(idx);
      if (kind == Kind.DATETIME || kind == Kind.INTERVAL) {
        return readScalar(m, args, idx, kind);
      }
      if ((kind == Kind.DATETIME_ARRAY || kind == Kind.INTERVAL_ARRAY)
          && name.equals("getArray")
          && args.length == 1) {
        return readArray(idx, kind);
      }
    }
    Object out = invoke(m, delegate, args);
    if (name.equals("next") && (args == null || args.length == 0) && Boolean.TRUE.equals(out)) {
      row++;
    }
    return out;
  }

  /** 스칼라 날짜·시각 셀 — 범위 밖·재현 실패·읽기 실패면 PG 텍스트로 대체하고 jOOQ 에는 null 을 준다. */
  private Object readScalar(Method m, Object[] args, int idx, Kind kind) throws Throwable {
    String text = delegate.getString(idx);
    if (text == null) {
      return invoke(m, delegate, args);
    }
    if (kind == Kind.INTERVAL || isOutOfRange(text)) {
      // interval 은 응답이 원래 PG 텍스트다 — jOOQ 정규화(24:00:00 → 1 day)·범위 초과를 피해 원문을 쓴다.
      return replace(idx, text);
    }
    Object out;
    try {
      out = invoke(m, delegate, args);
    } catch (SQLException | RuntimeException e) {
      return replace(idx, text);
    }
    if (roundTrip[idx] && !reproduces(out, text)) {
      return replace(idx, text);
    }
    return out;
  }

  /**
   * 배열 셀 — 날짜·시각 배열은 원소 하나라도 범위 밖·재현 실패·materialize 실패면 셀 전체를 PG 배열 리터럴로 대체한다.
   * interval 배열은 늘 원소별 PG 텍스트의 (중첩) 리스트로 대체한다.
   */
  private Object readArray(int idx, Kind kind) throws SQLException {
    java.sql.Array array = delegate.getArray(idx);
    if (array == null) {
      return null;
    }
    String literal = array.toString();
    Object parsed;
    try {
      parsed = parseLiteral(literal);
    } catch (RuntimeException e) {
      return array; // 해석 못 하는 리터럴 — 예전 처리(jOOQ·다차원 래퍼)에 맡긴다
    }
    if (kind == Kind.INTERVAL_ARRAY) {
      return replace(idx, parsed);
    }
    List<String> texts = new ArrayList<>();
    flatten(parsed, texts);
    for (String t : texts) {
      if (t != null && isOutOfRange(t)) {
        return replace(idx, literal);
      }
    }
    List<Object> javaValues = new ArrayList<>();
    try {
      flatten(array.getArray(), javaValues);
    } catch (SQLException | RuntimeException e) {
      return replace(idx, literal);
    }
    if (roundTrip[idx] && javaValues.size() == texts.size()) {
      for (int i = 0; i < texts.size(); i++) {
        if (texts.get(i) != null && !reproduces(javaValues.get(i), texts.get(i))) {
          return replace(idx, literal);
        }
      }
    }
    return array;
  }

  private Object replace(int idx, Object value) {
    replaced.computeIfAbsent(row, r -> new HashMap<>()).put(idx, value);
    forcedNull = true;
    return null;
  }

  /** 대체할 셀을 결과에 채운다. 대체한 것이 없으면 결과를 그대로 돌려준다. */
  Result<Record> apply(DSLContext dsl, Result<Record> result) {
    return AdhocMultiDimArrays.replaceCells(dsl, result, replaced);
  }

  private Kind kindOf(int idx) throws SQLException {
    if (kinds == null) {
      ResultSetMetaData meta = delegate.getMetaData();
      int n = meta.getColumnCount();
      kinds = new Kind[n + 1];
      roundTrip = new boolean[n + 1];
      for (int i = 1; i <= n; i++) {
        String typeName = meta.getColumnTypeName(i);
        kinds[i] = classify(typeName);
        roundTrip[i] =
            switch (typeName == null ? "" : typeName) {
              case "date", "timestamp", "time", "_date", "_timestamp", "_time" -> true;
              default -> false;
            };
      }
    }
    return idx > 0 && idx < kinds.length ? kinds[idx] : Kind.NONE;
  }

  private static Kind classify(String typeName) {
    if (typeName == null) {
      return Kind.NONE;
    }
    return switch (typeName) {
      case "date", "timestamp", "timestamptz", "time", "timetz" -> Kind.DATETIME;
      case "interval" -> Kind.INTERVAL;
      case "_date", "_timestamp", "_timestamptz", "_time", "_timetz" -> Kind.DATETIME_ARRAY;
      case "_interval" -> Kind.INTERVAL_ARRAY;
      default -> Kind.NONE;
    };
  }

  /** Java 날짜 객체로 표현할 수 없는 PG 날짜·시각 텍스트인가 — infinity·기원전·5자리 연도·24시. */
  static boolean isOutOfRange(String text) {
    return text.equals("infinity")
        || text.equals("-infinity")
        || text.endsWith(" BC")
        || BIG_YEAR.matcher(text).matches()
        || text.startsWith("24:");
  }

  /**
   * pgjdbc 가 만든 Java 값이 PG 텍스트를 그대로 재현하는가. {@code java.sql.*} 는 JVM 혼합 달력·시간대로 만들어져
   * 1582 전환 공백·DST 공백에서 다른 날짜/시각이 된다. 그 밖의 타입(OffsetDateTime 등)은 jOOQ 가 PG 텍스트를
   * 파싱해 만들므로 검사하지 않는다.
   */
  static boolean reproduces(Object value, String text) {
    if (value instanceof java.sql.Timestamp ts) {
      // Timestamp.toString 은 소수 초가 0 이면 ".0" 을 붙이고, PG 는 생략한다. 그 밖엔 둘 다 끝 0 을 뗀다.
      String s = ts.toString();
      if (s.endsWith(".0")) {
        s = s.substring(0, s.length() - 2);
      }
      return s.equals(text);
    }
    if (value instanceof java.sql.Date d) {
      return d.toString().equals(text);
    }
    if (value instanceof java.sql.Time t) {
      // java.sql.Time 은 초 단위까지만 문자열로 낸다(기존 응답도 초까지) — 초까지 비교한다.
      return text.length() >= 8 && t.toString().equals(text.substring(0, 8));
    }
    return true;
  }

  /**
   * PG 배열 리터럴을 원소 텍스트의 중첩 리스트로 해석한다(따옴표 없는 {@code NULL} 은 null). 차원 장식은 버린다 —
   * 다차원 배열(#757)·executor 와 같이 하한은 응답에 남기지 않는다.
   */
  static Object parseLiteral(String literal) {
    String body = BOUNDS_PREFIX.matcher(literal).replaceFirst("");
    int[] pos = {0};
    Object out = parseLevel(body, pos);
    if (pos[0] != body.length()) {
      throw new IllegalArgumentException("배열 리터럴 끝에 남은 문자: " + literal);
    }
    return out;
  }

  private static List<Object> parseLevel(String s, int[] pos) {
    if (s.charAt(pos[0]) != '{') {
      throw new IllegalArgumentException("'{' 가 필요합니다");
    }
    pos[0]++;
    List<Object> list = new ArrayList<>();
    if (s.charAt(pos[0]) == '}') {
      pos[0]++;
      return list;
    }
    while (true) {
      char c = s.charAt(pos[0]);
      if (c == '{') {
        list.add(parseLevel(s, pos));
      } else if (c == '"') {
        StringBuilder sb = new StringBuilder();
        pos[0]++;
        while (s.charAt(pos[0]) != '"') {
          if (s.charAt(pos[0]) == '\\') {
            pos[0]++;
          }
          sb.append(s.charAt(pos[0]));
          pos[0]++;
        }
        pos[0]++;
        list.add(sb.toString());
      } else {
        int start = pos[0];
        while (s.charAt(pos[0]) != ',' && s.charAt(pos[0]) != '}') {
          pos[0]++;
        }
        String token = s.substring(start, pos[0]);
        list.add(token.equals("NULL") ? null : token);
      }
      char sep = s.charAt(pos[0]++);
      if (sep == '}') {
        return list;
      }
      if (sep != ',') {
        throw new IllegalArgumentException("',' 또는 '}' 가 필요합니다");
      }
    }
  }

  /** 중첩 리스트·Java 배열의 잎 원소를 순서대로 모은다. */
  @SuppressWarnings("unchecked")
  private static <T> void flatten(Object value, List<T> out) {
    if (value instanceof List<?> list) {
      for (Object e : list) {
        flatten(e, out);
      }
    } else if (value instanceof Object[] array) {
      for (Object e : array) {
        flatten(e, out);
      }
    } else {
      out.add((T) value);
    }
  }

  private static Object invoke(Method m, Object target, Object[] args) throws Throwable {
    try {
      return m.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
