package com.smartfirehub.dataimport.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;

/**
 * SAX(XLSX)·바이너리(XLSB)·HSSF 이벤트(XLS) 파서가 숫자 셀을 문자열로 바꿀 때 쓰는 DataFormatter.
 *
 * <p>POI 기본 DataFormatter 는 셀의 표시 서식(예: "#,##0", "m/d/yyyy")을 그대로 적용해 로캘에 따라 모양이 달라지므로, 검증·삽입이 받을 수
 * 있는 일정한 모양으로 바꾼다. 셀 값이 경고 없이 바뀌지 않게 하는 것이 목적이다(WD-252).
 *
 * <ul>
 *   <li>숫자: 지수 표기 없는 평문. 2^53 이하 정수는 그대로, 그 밖(소수·더 큰 수)은 엑셀 표시처럼 유효숫자 15자리로 반올림해 부동소수 꼬리를
 *       없앤다(0.1+0.2 → 0.3, 1e20 → 100000000000000000000). 예전에는 {@code (long)} 캐스트로 1e20 이 Long
 *       최댓값으로 포화되고 {@code Double.toString} 으로 1.23456785E7 같은 지수 표기가 저장됐다.
 *   <li>날짜 서식: 시간대 변환 없이 엑셀 일련값을 그대로 날짜시각으로 읽는다. 예전에는 JVM 기본 시간대(Asia/Seoul)를 거쳐 1899년 옛 시간대
 *       규칙(+8:27:52)이 끼어들었다. 날짜 부분이 없으면(값 &lt; 1) {@code HH:mm:ss}, 날짜만 보이는 서식의 자정이면 {@code
 *       yyyy-MM-dd}, 그 밖은 {@code yyyy-MM-dd HH:mm:ss}.
 *   <li>1904 날짜 체계: 워크북이 1904 체계면 파서가 {@link #setUse1904Windowing(boolean)} 로 알려 준다. POI 이벤트 핸들러는
 *       1904 여부를 넘기지 않는 3인자 {@link #formatRawCellContents(double, int, String)} 만 부르므로 인스턴스 상태로 들고
 *       있어야 한다.
 * </ul>
 *
 * <p>엑셀 오류 셀(#DIV/0! 등)은 이 클래스가 만든 {@link #errorCell(String)} 표식으로 파서가 내보내고, 검증 단계가 행·열과 함께 거부한다.
 * 표식에는 NUL 문자가 들어 있다 — NUL 은 PostgreSQL 텍스트에 저장할 수 없어 CSV 등 다른 경로의 정상 값과 겹치지 않는다.
 */
public class LegacyExcelDataFormatter extends DataFormatter {

  /**
   * 엑셀 오류 셀 표식 앞부분. 뒤에 엑셀 표시 문자열(예: #DIV/0!)이 붙는다. NUL 을 맨 앞이 아닌 가운데 둔다 — 검증이 값을 {@code trim()} 하면
   * 앞쪽 NUL 은 공백으로 취급돼 지워지기 때문이다.
   */
  static final String ERROR_CELL_PREFIX = "EXCEL_ERROR\u0000";

  /** 2^53 — 이 이하의 정수는 double 로 정확히 표현되므로 반올림 없이 그대로 쓴다(예: 1234567890123456). */
  private static final double EXACT_INTEGER_LIMIT = 9007199254740992d;

  /** 엑셀 표시와 같은 유효숫자 15자리. */
  private static final MathContext EXCEL_PRECISION = new MathContext(15, RoundingMode.HALF_UP);

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

  /** 워크북이 1904 날짜 체계인지. 파서가 워크북 속성을 읽은 뒤 설정한다. */
  private boolean use1904Windowing;

  public LegacyExcelDataFormatter() {
    this(false);
  }

  public LegacyExcelDataFormatter(boolean use1904Windowing) {
    this.use1904Windowing = use1904Windowing;
  }

  /** XLS 처럼 시트보다 앞서 오는 워크북 레코드에서 1904 여부를 알게 되는 경로가 쓴다. */
  public void setUse1904Windowing(boolean use1904Windowing) {
    this.use1904Windowing = use1904Windowing;
  }

  @Override
  public String formatRawCellContents(double value, int formatIndex, String formatString) {
    return formatRawCellContents(value, formatIndex, formatString, use1904Windowing);
  }

  @Override
  public String formatRawCellContents(
      double value, int formatIndex, String formatString, boolean use1904Windowing) {
    if (DateUtil.isADateFormat(formatIndex, formatString)) {
      String date = formatExcelDate(value, formatString, use1904Windowing);
      if (date != null) {
        return date;
      }
      // 음수 등 날짜로 읽을 수 없는 일련값은 엑셀도 날짜로 보여 주지 못한다 — 숫자 그대로 둔다.
    }
    return formatNumber(value);
  }

  /** 숫자를 지수 표기 없는 평문으로 바꾼다(규칙은 클래스 주석). */
  static String formatNumber(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return String.valueOf(value);
    }
    if (value == Math.rint(value) && Math.abs(value) <= EXACT_INTEGER_LIMIT) {
      // 정수 판정·출력을 long 캐스트 없이 한다 — 범위 밖 값이 포화되지 않게.
      return BigDecimal.valueOf(value).toBigInteger().toString();
    }
    BigDecimal rounded = BigDecimal.valueOf(value).round(EXCEL_PRECISION).stripTrailingZeros();
    // -0.0 처럼 0 으로 반올림된 값은 "0" 으로 둔다.
    return rounded.signum() == 0 ? "0" : rounded.toPlainString();
  }

  /** 엑셀 날짜 일련값을 시간대 변환 없이 문자열로 바꾼다. 날짜로 읽을 수 없으면 null. */
  private static String formatExcelDate(double value, String formatString, boolean use1904) {
    // 초 단위 반올림 — 엑셀은 10:30:14.9995 를 10:30:15 로 보여 준다.
    LocalDateTime dateTime = DateUtil.getLocalDateTime(value, use1904, true);
    if (dateTime == null) {
      return null;
    }
    if (value < 1) {
      // 날짜 부분이 없는 시각(엑셀 h:mm 서식의 0.5 = 12:00). 날짜로 바꾸면 1899-12-31 같은 가짜 날짜가 생긴다.
      return dateTime.toLocalTime().format(TIME);
    }
    if (dateTime.toLocalTime().equals(LocalTime.MIDNIGHT) && !hasTimePart(formatString)) {
      return dateTime.toLocalDate().format(DATE);
    }
    return dateTime.format(DATE_TIME);
  }

  /**
   * 서식 문자열이 시각(시·초·오전/오후·경과 시간)을 보여 주는지 판정한다. 따옴표 문자열·이스케이프 문자·색/조건 대괄호는 걷어 내고 본다. 분(m)은 월과 같은 글자라
   * 단독으로는 보지 않는다 — 분을 보여 주는 서식은 시나 초가 함께 있다.
   */
  static boolean hasTimePart(String formatString) {
    if (formatString == null) {
      return false;
    }
    StringBuilder plain = new StringBuilder();
    boolean inQuote = false;
    for (int i = 0; i < formatString.length(); i++) {
      char c = formatString.charAt(i);
      if (inQuote) {
        if (c == '"') inQuote = false;
        continue;
      }
      if (c == '"') {
        inQuote = true;
      } else if (c == '\\' || c == '_' || c == '*') {
        i++; // 다음 한 글자는 문자 그대로(또는 채움 문자)
      } else if (c == '[') {
        int end = formatString.indexOf(']', i);
        if (end < 0) break;
        String bracket = formatString.substring(i + 1, end).toLowerCase();
        // [h]·[mm]·[ss] 경과 시간은 시각으로 본다. 그 밖([Red]·[$-412]·[>100])은 무시.
        if (bracket.matches("h+|m+|s+")) plain.append('h');
        i = end;
      } else {
        plain.append(Character.toLowerCase(c));
      }
    }
    String s = plain.toString();
    return s.indexOf('h') >= 0 || s.indexOf('s') >= 0 || s.contains("am/pm") || s.contains("a/p");
  }

  /** 엑셀 오류 셀 표식을 만든다. 알 수 없는 코드도 표식으로 감싸 검증에서 거부되게 한다. */
  static String errorCell(String code) {
    return ERROR_CELL_PREFIX + (code == null ? "" : code);
  }

  /** XSSF 이벤트 핸들러가 오류 셀에 붙이는 "ERROR:" 접두를 떼고 표식으로 바꾼다. */
  static String errorCellFromXssf(String formatted) {
    String code = formatted;
    if (code != null && code.startsWith("ERROR:")) {
      code = code.substring("ERROR:".length());
    }
    return errorCell(code);
  }

  /** 값이 엑셀 오류 셀 표식이면 엑셀 표시 문자열(예: #DIV/0!)을, 아니면 null 을 돌려준다. */
  public static String errorCodeOf(String value) {
    if (value == null || !value.startsWith(ERROR_CELL_PREFIX)) {
      return null;
    }
    String code = value.substring(ERROR_CELL_PREFIX.length());
    return code.isEmpty() ? "#오류" : code;
  }

  /** 미리보기·오류 상세처럼 사용자에게 값을 그대로 보여 줄 때 표식을 엑셀 표시 문자열로 바꾼다. */
  public static String toDisplay(String value) {
    String code = errorCodeOf(value);
    return code != null ? code : value;
  }
}
