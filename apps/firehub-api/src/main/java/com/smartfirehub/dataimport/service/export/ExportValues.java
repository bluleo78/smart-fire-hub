package com.smartfirehub.dataimport.service.export;

import com.smartfirehub.global.util.JsSafeNumbers;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 내보내기 셀 값의 문자열 모양과 숫자 안전 범위를 한곳에서 정한다(WD-159).
 *
 * <p>왜: 예전에는 모든 값을 {@code toString()} 으로 썼다. 그래서 NUMERIC(18,6) 은 scale 대로 {@code 1234567.891000},
 * {@code java.sql.Timestamp} 는 끝에 {@code .0} 이 붙어 화면(WD-101 직렬화)과 모양이 달랐다. CSV·GeoJSON 의 문자열 값과
 * Excel 의 문자열 대체 셀이 모두 같은 규칙을 쓰도록 여기에 모은다.
 *
 * <ul>
 *   <li>소수({@link BigDecimal}) → 끝자리 0 을 지운 평문({@link JsSafeNumbers#plainDecimal}, 지수 표기 없음) — 화면과
 *       같다.
 *   <li>시각({@code java.sql.Timestamp}·{@link LocalDateTime}) → {@code yyyy-MM-dd HH:mm:ss}, 소수 초는 0
 *       이 아닐 때만 끝자리 0 을 지워 붙인다. PG 텍스트 원문({@code 2024-01-01 10:00:00.123})과 같은 모양이라, 범위 밖 값을 원문으로
 *       읽는 #769 경로와도 한 열 안에서 모양이 맞는다. JDBC 가 만든 벽시계 값을 그대로 쓰고 시간대는 바꾸지 않는다.
 *   <li>날짜({@code java.sql.Date}·{@link LocalDate}) → {@code yyyy-MM-dd}.
 *   <li>참거짓 → {@code true}/{@code false}(CSV 를 다시 임포트할 때 그대로 읽힌다).
 * </ul>
 */
public final class ExportValues {

  /** Excel 이 숫자를 정확히 보여 주고 편집해도 바뀌지 않는 유효 숫자 자릿수(Excel 사양의 15자리). */
  static final int EXCEL_MAX_DIGITS = 15;

  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private ExportValues() {}

  /**
   * 셀 값을 내보내기 문자열로 바꾼다(null 이면 빈 문자열).
   *
   * @param value 행 조회가 돌려준 값(Long·BigDecimal·Boolean·java.sql.Date/Timestamp·String 등)
   */
  public static String text(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof BigDecimal decimal) {
      return JsSafeNumbers.plainDecimal(decimal);
    }
    if (value instanceof java.sql.Timestamp ts) {
      return dateTimeText(ts.toLocalDateTime());
    }
    if (value instanceof LocalDateTime ldt) {
      return dateTimeText(ldt);
    }
    // java.sql.Date·LocalDate·숫자·참거짓·문자열은 toString 이 이미 원하는 모양이다(yyyy-MM-dd, 10진 정수, true/false).
    return value.toString();
  }

  /** {@code yyyy-MM-dd HH:mm:ss} + 0 이 아닌 소수 초(끝자리 0 제거). */
  static String dateTimeText(LocalDateTime ldt) {
    String base = DATE_TIME.format(ldt);
    int nanos = ldt.getNano();
    if (nanos == 0) {
      return base;
    }
    String fraction = String.format("%09d", nanos).replaceFirst("0+$", "");
    return base + "." + fraction;
  }

  /**
   * Excel 숫자 셀로 써도 값이 그대로인지 — 유효 숫자 15자리 이하인 정수·소수만 숫자 셀로 쓴다. 넘는 값(NUMERIC(18,6) 의 큰 값, 2^50 같은
   * BIGINT)은 double 로 깎여 다른 값으로 보이므로 문자열 셀로 쓴다.
   */
  static boolean fitsExcelNumber(Number number) {
    if (number instanceof Integer || number instanceof Short || number instanceof Byte) {
      return true;
    }
    if (number instanceof Long l) {
      return BigDecimal.valueOf(l).precision() <= EXCEL_MAX_DIGITS;
    }
    if (number instanceof BigInteger bi) {
      return new BigDecimal(bi).precision() <= EXCEL_MAX_DIGITS;
    }
    if (number instanceof BigDecimal decimal) {
      return decimal.signum() == 0 || decimal.stripTrailingZeros().precision() <= EXCEL_MAX_DIGITS;
    }
    if (number instanceof Double d) {
      return !d.isNaN() && !d.isInfinite();
    }
    if (number instanceof Float f) {
      return !f.isNaN() && !f.isInfinite();
    }
    return false;
  }
}
