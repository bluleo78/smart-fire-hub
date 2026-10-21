package com.smartfirehub.dataimport.service.export;

import java.time.temporal.Temporal;
import java.util.Locale;
import java.util.Set;

/**
 * CSV 수식 주입(OWASP CSV Injection / Formula Injection) 방어 도우미.
 *
 * <p>CSV 셀 값이 {@code =}, {@code +}, {@code -}, {@code @}, 탭({@code \t}), 캐리지 리턴({@code \r}) 으로 시작하면
 * Excel·LibreOffice 가 파일을 열 때 그 값을 수식으로 실행한다(예: {@code =HYPERLINK("http://…","클릭")}). 행을 쓸 수 있는 사람
 * 누구나(행 추가·임포트·API 임포트·파이프라인) 이런 값을 심을 수 있으므로, OWASP 권고대로 위험 문자로 시작하는 <b>문자열</b> 값 앞에 작은따옴표 {@code
 * '} 를 붙여 스프레드시트가 텍스트로 취급하게 한다.
 *
 * <p>규칙:
 *
 * <ul>
 *   <li>문자열 값만 중화한다. 숫자·날짜/시각·불리언·지오메트리 값은 원형을 유지한다 — BIGINT/NUMERIC 의 음수 {@code -5} 에 따옴표가 붙으면
 *       데이터가 망가지기 때문이다.
 *   <li>첫 글자만 본다(OWASP 권고의 첫 글자 목록을 그대로 따르는 결정). 앞 공백 뒤의 위험 문자({@code " =1+1"})는 이번 범위에서 제외했다 —
 *       공백으로 시작하는 정상 데이터를 바꾸지 않기 위해서다.
 *   <li>타입을 알 수 없으면 문자열로 본다(안전 우선).
 * </ul>
 *
 * <p>CSV 전용이다. XLSX 는 문자열을 문자열 셀(inlineStr)로 쓰므로 수식으로 해석되지 않고, 여기서 따옴표를 붙이면 셀에 {@code '} 가 그대로 보여
 * 데이터가 오염된다 — 그래서 범용 {@link ExportWriter} 가 아니라 CSV 를 만드는 호출부에서만 쓴다(헤더는 {@link CsvExportWriter}).
 */
public final class CsvFormulaGuard {

  /**
   * 데이터셋 컬럼 타입 중 문자열이 아닌 것 — 이 타입 컬럼의 값은 중화하지 않는다. 목록에 없는 타입(TEXT·VARCHAR·모르는 타입)은 문자열로 보고 중화한다(안전
   * 우선). GEOMETRY 는 GeoJSON({@code {...}})으로 나가 위험 문자로 시작하지 않지만, 값 형태를 그대로 두려고 명시적으로 제외한다.
   */
  private static final Set<String> NON_TEXT_DATA_TYPES =
      Set.of(
          "INTEGER",
          "INT",
          "BIGINT",
          "SMALLINT",
          "DECIMAL",
          "NUMERIC",
          "DOUBLE",
          "REAL",
          "FLOAT",
          "BOOLEAN",
          "DATE",
          "TIMESTAMP",
          "TIMESTAMPTZ",
          "TIME",
          "DATETIME",
          "GEOMETRY");

  private CsvFormulaGuard() {}

  /**
   * 문자열을 중화한다. 첫 글자가 수식 시작 문자({@code = + - @ \t \r})면 앞에 {@code '} 를 붙이고, 아니면 그대로 돌려준다.
   *
   * @param value CSV 셀에 쓸 문자열(null 이면 null)
   */
  public static String neutralize(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    char first = value.charAt(0);
    if (first == '='
        || first == '+'
        || first == '-'
        || first == '@'
        || first == '\t'
        || first == '\r') {
      return "'" + value;
    }
    return value;
  }

  /**
   * 데이터셋 내보내기 CSV 셀 값을 만든다. 컬럼 타입이 문자열이 아니거나 값 자체가 숫자·날짜 등이면 원형({@link ExportValues#text} 모양), 그
   * 외에는 중화한다.
   *
   * @param value 행 값(null 이면 빈 문자열)
   * @param dataType 데이터셋 컬럼 타입(dataset_column.data_type, 예: TEXT·INTEGER). null 이면 값 타입으로만 판정
   */
  public static String datasetCell(Object value, String dataType) {
    if (value == null) {
      return "";
    }
    if (isNonTextDataType(dataType)) {
      return ExportValues.text(value);
    }
    return queryCell(value);
  }

  /**
   * 쿼리 결과 내보내기 CSV 셀 값을 만든다. 쿼리 결과는 컬럼 메타가 없으므로 JDBC/jOOQ 가 돌려준 값의 Java 타입으로 판정한다 — {@link Number}·
   * {@link Temporal}·{@link java.util.Date}(java.sql.Date/Timestamp 포함)·{@link Boolean} 은 원형,
   * 나머지(문자열·모르는 타입)는 문자열로 보고 중화한다(안전 우선).
   *
   * @param value 결과 셀 값(null 이면 빈 문자열)
   */
  public static String queryCell(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof Number
        || value instanceof Temporal
        || value instanceof java.util.Date
        || value instanceof Boolean) {
      return ExportValues.text(value);
    }
    return neutralize(value.toString());
  }

  /** 컬럼 타입이 문자열이 아닌 타입인지 — 대소문자 무시, {@code VARCHAR(255)} 같은 길이 접미사는 떼고 비교한다. */
  static boolean isNonTextDataType(String dataType) {
    if (dataType == null) {
      return false;
    }
    String base = dataType.trim().toUpperCase(Locale.ROOT);
    int paren = base.indexOf('(');
    if (paren >= 0) {
      base = base.substring(0, paren).trim();
    }
    return NON_TEXT_DATA_TYPES.contains(base);
  }
}
