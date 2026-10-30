package com.smartfirehub.dbexecutor.sql;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 적재 값의 변환·바인딩(스펙 §3.3). api 의 Tibero 행 저장소와 <b>같은 결과</b>를 내야 하므로 두 단계를 api 와 똑같이 옮겨 두고, 공유 벡터
 * ({@code tibero-bind-vectors.json}, {@code TiberoBindVectorsParityTest})로 고정한다(공용 모듈은 만들지 않는다).
 *
 * <ol>
 *   <li>{@link #coerce}: JSON 값 + 앱 타입 → Java 값. api {@code JsonResponseParser#convertValue} 와 같다 —
 *       INTEGER→Long, DECIMAL→BigDecimal, BOOLEAN→true/false/1/0/yes/no, DATE→LocalDate,
 *       TIMESTAMP→UTC 기준 LocalDateTime(타임존이 있으면 UTC 로 환산, 없으면 UTC 로 간주), TEXT·VARCHAR→문자열. 타입이 없거나
 *       모르면 원시 값을 보존한다.
 *   <li>{@link #bind}: Java 값 → PreparedStatement. api {@code TiberoValues#bind} 와 같다 — null→{@code
 *       setNull(NULL)}, Boolean→1/0, LocalDate→setDate, LocalDateTime→setTimestamp, 그 외 setObject.
 * </ol>
 *
 * <p><b>변환 실패</b>: 바꿀 수 없는 값은 {@link InvalidValue} 로 요청 전체를 실패시킨다(fail-closed — 행을 빼면 일부만 적재된다). 값
 * 규칙(불리언 true/false/1/0/yes/no, 빈 문자열 처리 등)은 api {@code ValueConversion} 을 따른다(WD-431). 호출자가 먼저 변환해
 * 보내므로 여기서 실패하면 버그이며, 공유 벡터의 {@code expectFailure} 가 고정한다.
 */
final class LoadValues {

  private LoadValues() {}

  /** 값을 앱 타입으로 바꿀 수 없다. 메시지에 값을 싣지 않는다(적재 데이터가 응답·로그로 새지 않게). */
  static final class InvalidValue extends RuntimeException {
    InvalidValue(String appType) {
      super("값을 " + appType + " 형식으로 바꿀 수 없습니다.", null, false, false);
    }
  }

  /**
   * JSON 값 하나를 앱 타입에 맞는 Java 값으로 바꾼다.
   *
   * @param appType 앱 타입 이름(대소문자 무시), null 이면 변환하지 않는다
   * @throws InvalidValue 형식이 맞지 않을 때
   */
  static Object coerce(Object raw, String appType) {
    if (raw == null || appType == null) {
      return raw;
    }
    try {
      return switch (appType.toUpperCase(Locale.ROOT)) {
        case "TEXT", "VARCHAR" -> raw.toString();
        // longValue() 는 64비트 초과 정수·소수를 조용히 다른 값으로 바꾼다 — 정확히 long 인 값만 받는다(fail-closed). 정수 값인
        // 실수 표기("3.0"·"1e2")는 받는다(WD-431 — api ValueConversion#toLongStrict 와 같은 규칙).
        case "INTEGER" ->
            raw instanceof Number n
                ? (Object) new BigDecimal(n.toString()).longValueExact()
                : toLongExact(raw.toString().strip());
        case "DECIMAL" ->
            raw instanceof Number n
                ? new BigDecimal(n.toString())
                : new BigDecimal(raw.toString().strip());
        // 불리언의 빈 문자열(공백뿐 포함)은 NULL — api·Python 실행기의 빈 문자열 규칙과 같다(WD-431). 숫자·날짜의 빈 문자열은
        // 실행기가 None 으로 바꿔 보내므로 여기서는 예전처럼 실패로 둔다(공유 벡터 expectFailure).
        case "BOOLEAN" ->
            raw instanceof Boolean
                ? raw
                : (raw instanceof String s && s.isBlank() ? null : toBoolean(raw));
        case "DATE" -> LocalDate.parse(raw.toString());
        case "TIMESTAMP" -> toUtcLocalDateTime(raw.toString());
        case "GEOMETRY" -> raw instanceof String ? raw : raw.toString();
        default -> raw;
      };
    } catch (NumberFormatException | ArithmeticException | DateTimeParseException e) {
      throw new InvalidValue(appType);
    }
  }

  /** 정수 문자열 → long. 정수 표기가 아니면 십진수로 읽어 소수부 없이 long 범위일 때만 받는다("3.0"·"1e2"). */
  private static long toLongExact(String s) {
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return new BigDecimal(s).longValueExact();
    }
  }

  /**
   * 불리언 해석 — 숫자는 1·0 만, 문자열은 true/false/1/0/yes/no(대소문자·앞뒤 공백 무시)만 받는다(WD-431). 그 밖의 값('Y'·'예' 등)은
   * 거짓이 아니라 {@link InvalidValue}. <b>규칙의 기준</b>은 api {@code ValueConversion#parseBoolean}(과 Python
   * 실행기 {@code value_conversion.parse_boolean})이다 — 모듈이 달라 여기 따로 두지만 허용 값을 바꾸면 함께 바꾼다.
   */
  private static Boolean toBoolean(Object raw) {
    if (raw instanceof Number n) {
      // 1.0 처럼 값이 1·0 과 같으면 받는다. NaN·무한대는 BigDecimal 이 NumberFormatException 으로 거부한다(→
      // InvalidValue).
      BigDecimal d = new BigDecimal(n.toString());
      if (d.compareTo(BigDecimal.ONE) == 0) {
        return true;
      }
      if (d.signum() == 0) {
        return false;
      }
      throw new NumberFormatException("not a boolean number");
    }
    String v = raw.toString().strip().toLowerCase(Locale.ROOT);
    return switch (v) {
      case "true", "1", "yes" -> true;
      case "false", "0", "no" -> false;
      default -> throw new NumberFormatException("not a boolean token");
    };
  }

  /** 타임존이 있으면 UTC 로 환산하고, 없으면 UTC 로 간주한다(api JsonResponseParser 기본). */
  private static LocalDateTime toUtcLocalDateTime(String s) {
    try {
      return ZonedDateTime.parse(s).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    } catch (DateTimeParseException ignored) {
      // 타임존이 없는 ISO 일시 — 아래에서 그대로 읽는다.
    }
    return LocalDateTime.parse(s);
  }

  /** Java 값 타입에 맞춰 파라미터를 바인딩한다(api {@code TiberoValues#bind} 와 같다). null 은 {@code Types.NULL}. */
  static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.NULL);
    } else if (value instanceof Boolean b) {
      // Tibero 에는 BOOLEAN 컬럼이 없어 NUMBER(1) 에 1/0 으로 저장
      ps.setInt(index, b ? 1 : 0);
    } else if (value instanceof LocalDate d) {
      ps.setDate(index, java.sql.Date.valueOf(d));
    } else if (value instanceof LocalDateTime t) {
      ps.setTimestamp(index, Timestamp.valueOf(t));
    } else {
      ps.setObject(index, value);
    }
  }
}
