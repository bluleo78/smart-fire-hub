package com.smartfirehub.dataset.rowsearch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 구조화 필터를 원본 테이블 별칭 t 기준 WHERE 조각으로 컴파일한다.
 *
 * <p>SQL 주입 방어: 컬럼은 호출자가 넘긴 사용자 컬럼 화이트리스트(시스템 컬럼 제외)에 있어야 하고, 식별자는 인용하며,
 * 값은 컬럼 타입으로 변환해 전부 바인딩한다.
 */
public final class RowFilterCompiler {

  private static final Set<String> OPS =
      Set.of("eq", "neq", "in", "gt", "gte", "lt", "lte", "is_null", "is_not_null");
  private static final int MAX_IN = 100;

  /** 공백 구분 날짜시간(yyyy-MM-dd HH:mm[:ss]) — 임포트·UI 에서 흔히 쓰는 표기. */
  private static final DateTimeFormatter SPACE_DATE_TIME =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd HH:mm")
          .optionalStart()
          .appendPattern(":ss")
          .optionalEnd()
          .toFormatter();

  private RowFilterCompiler() {}

  /**
   * 필터를 검증하고 WHERE 조각(원본 별칭 t 기준)과 바인딩 값으로 컴파일한다.
   *
   * @param filter 에이전트가 넘긴 조건(AND 결합). null·빈 필터면 {@link CompiledFilter#none()}
   * @param columnTypes 허용 컬럼 화이트리스트(컬럼명 → 데이터 타입). 여기 없는 컬럼은 거부한다
   * @throws IllegalArgumentException 알 수 없는 컬럼·GEOMETRY 컬럼·미지원 연산자·타입 변환 실패 시(몇 번째 조건인지 포함)
   */
  public static CompiledFilter compile(RowFilter filter, Map<String, String> columnTypes) {
    if (filter == null || filter.isEmpty()) return CompiledFilter.none();
    List<String> parts = new ArrayList<>();
    List<Object> params = new ArrayList<>();
    List<RowFilter.Condition> conds = filter.conditions();
    for (int i = 0; i < conds.size(); i++) {
      RowFilter.Condition c = conds.get(i);
      String at = "filters[" + i + "]: ";
      String type = c.column() == null ? null : columnTypes.get(c.column());
      if (type == null) {
        throw new IllegalArgumentException(at + "알 수 없는 컬럼 '" + c.column() + "'");
      }
      if ("GEOMETRY".equals(type)) {
        throw new IllegalArgumentException(at + "GEOMETRY 컬럼은 필터에 쓸 수 없습니다");
      }
      if (c.op() == null || !OPS.contains(c.op())) {
        throw new IllegalArgumentException(at + "지원하지 않는 연산자 '" + c.op() + "' (" + OPS + ")");
      }
      String col = "t.\"" + c.column() + "\"";
      switch (c.op()) {
        case "is_null" -> parts.add(col + " IS NULL");
        case "is_not_null" -> parts.add(col + " IS NOT NULL");
        case "in" -> {
          if (!(c.value() instanceof Collection<?> values) || values.isEmpty() || values.size() > MAX_IN) {
            throw new IllegalArgumentException(at + "in 값은 1~" + MAX_IN + "개 목록이어야 합니다");
          }
          List<Object> converted = new ArrayList<>();
          for (Object v : values) converted.add(convert(v, type, at));
          parts.add(col + " = ANY(?)");
          // jOOQ 는 배열 원소 타입으로 PG 배열 타입을 정한다 — Object[] 면 text[] 로 묶여 bigint = text[] 오류가 난다.
          params.add(typedArray(converted, type));
        }
        default -> {
          parts.add(col + " " + sqlOp(c.op()) + " ?");
          params.add(convert(c.value(), type, at));
        }
      }
    }
    return new CompiledFilter(String.join(" AND ", parts), params);
  }

  /** 컬럼 타입에 맞는 Java 배열(Long[], BigDecimal[] …)로 바꾼다. */
  private static Object typedArray(List<Object> values, String type) {
    return switch (type) {
      case "INTEGER" -> values.toArray(Long[]::new);
      case "DECIMAL" -> values.toArray(BigDecimal[]::new);
      case "BOOLEAN" -> values.toArray(Boolean[]::new);
      case "DATE" -> values.toArray(LocalDate[]::new);
      case "TIMESTAMP" -> values.toArray(LocalDateTime[]::new);
      default -> values.toArray(String[]::new);
    };
  }

  private static String sqlOp(String op) {
    return switch (op) {
      case "eq" -> "=";
      case "neq" -> "<>";
      case "gt" -> ">";
      case "gte" -> ">=";
      case "lt" -> "<";
      case "lte" -> "<=";
      default -> throw new IllegalStateException(op);
    };
  }

  /**
   * TIMESTAMP(시간대 없음) 컬럼 비교값을 해석한다. 허용 형식: yyyy-MM-dd(자정), ISO 로컬(yyyy-MM-ddTHH:mm[:ss]),
   * 공백 구분(yyyy-MM-dd HH:mm[:ss]), 오프셋/Z 가 붙은 ISO(…Z, …+09:00).
   *
   * <p>시간대 규칙: 오프셋이 없는 값은 저장된 벽시계 시각과 그대로 비교한다 — 임포트(DataValidationService)도 값을
   * 시간대 변환 없이 LocalDateTime 으로 저장한다. 오프셋이 있는 값은 같은 순간의 UTC 벽시계 시각으로 바꾼다 — 이
   * 프로젝트에서 시간대 있는 값을 TIMESTAMP 컬럼에 넣는 유일한 경로(API 호출 적재, JsonResponseParser)가 UTC 로
   * 정규화하기 때문이다. JVM 기본 시간대(ZoneId.systemDefault)는 쓰지 않는다 — 운영 컨테이너는 TZ 미설정(UTC),
   * 로컬 개발은 Asia/Seoul 이라 환경마다 결과가 달라진다.
   */
  private static LocalDateTime parseTimestamp(String s) {
    if (s.length() == 10) return LocalDate.parse(s).atStartOfDay();
    try {
      return LocalDateTime.parse(s); // ISO_LOCAL_DATE_TIME
    } catch (DateTimeParseException ignored) {
      // 다음 형식 시도
    }
    try {
      return LocalDateTime.parse(s, SPACE_DATE_TIME);
    } catch (DateTimeParseException ignored) {
      // 다음 형식 시도
    }
    // 마지막으로 오프셋/Z 가 붙은 ISO — 실패하면 호출자의 catch 가 타입 오류 메시지로 바꾼다.
    return OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }

  /** 값을 컬럼 타입의 Java 값으로 바꾼다. 실패하면 어떤 타입을 기대했는지 알려준다. */
  private static Object convert(Object v, String type, String at) {
    if (v == null) throw new IllegalArgumentException(at + "값이 비어 있습니다(null 비교는 is_null 사용)");
    String s = v.toString();
    try {
      return switch (type) {
        case "TEXT", "VARCHAR" -> s;
        case "INTEGER" -> {
          // 에이전트 JSON 의 5.0 은 Double("5.0")로 들어온다 — 소수부가 0 인 값만 정수로 받고 5.5 는 거부한다.
          BigDecimal d = new BigDecimal(s);
          if (d.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException();
          yield d.longValueExact();
        }
        case "DECIMAL" -> new BigDecimal(s);
        case "BOOLEAN" -> {
          if (!s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")) throw new IllegalArgumentException();
          yield Boolean.parseBoolean(s);
        }
        case "DATE" -> LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        case "TIMESTAMP" -> parseTimestamp(s);
        default -> throw new IllegalArgumentException();
      };
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(at + "값 '" + s + "' 을(를) " + type + " 로 해석할 수 없습니다");
    }
  }
}
