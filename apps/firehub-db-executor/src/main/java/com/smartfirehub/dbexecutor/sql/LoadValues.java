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
 *       INTEGER→Long, DECIMAL→BigDecimal, BOOLEAN→true/1/yes, DATE→LocalDate, TIMESTAMP→UTC 기준
 *       LocalDateTime(타임존이 있으면 UTC 로 환산, 없으면 UTC 로 간주), TEXT·VARCHAR→문자열. 타입이 없거나 모르면 원시 값을 보존한다.
 *   <li>{@link #bind}: Java 값 → PreparedStatement. api {@code TiberoValues#bind} 와 같다 — null→{@code
 *       setNull(NULL)}, Boolean→1/0, LocalDate→setDate, LocalDateTime→setTimestamp, 그 외 setObject.
 * </ol>
 *
 * <p><b>api 와 다른 점(의도된 차이)</b>: api 로컬 모드는 변환에 실패한 행을 조용히 건너뛰지만, db-executor 는 fail-closed 로 {@link
 * InvalidValue} 를 던져 요청 전체를 실패시킨다. Python 실행기가 원격 적재(Task 6) 전에 값을 먼저 변환하므로 변환 불가 값이 여기 도달하면 버그이고,
 * 행을 빼면 적재가 일부만 되기 때문이다. 빈 문자열도 같다 — api 는 INTEGER·DECIMAL·DATE·TIMESTAMP 의 빈 문자열을 NULL 로 바꾸지 않고 행을
 * 건너뛰므로(현행 동작) 여기서도 실패이고, TEXT 는 그대로, BOOLEAN 은 거짓이다. 공유 벡터의 {@code expectFailure} 가 고정한다.
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
        // longValue() 는 64비트 초과 정수·소수를 조용히 다른 값으로 바꾼다 — 정확히 long 인 값만 받는다(fail-closed).
        case "INTEGER" ->
            raw instanceof Number n
                ? (Object) new BigDecimal(n.toString()).longValueExact()
                : Long.parseLong(raw.toString().strip());
        case "DECIMAL" ->
            raw instanceof Number n
                ? new BigDecimal(n.toString())
                : new BigDecimal(raw.toString().strip());
        case "BOOLEAN" -> raw instanceof Boolean ? raw : toBoolean(raw.toString());
        case "DATE" -> LocalDate.parse(raw.toString());
        case "TIMESTAMP" -> toUtcLocalDateTime(raw.toString());
        case "GEOMETRY" -> raw instanceof String ? raw : raw.toString();
        default -> raw;
      };
    } catch (NumberFormatException | ArithmeticException | DateTimeParseException e) {
      throw new InvalidValue(appType);
    }
  }

  private static Boolean toBoolean(String s) {
    String v = s.toLowerCase(Locale.ROOT);
    return v.equals("true") || v.equals("1") || v.equals("yes");
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
