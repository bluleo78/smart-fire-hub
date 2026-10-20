package com.smartfirehub.dataset.storage.tibero;

import java.util.Locale;

/**
 * 값을 PG {@code ::text}(= {@code CAST(.. AS TEXT)}) 와 같은 모양의 문자열로 바꾸는 SQL 식 모음(WD-74).
 *
 * <p>왜 필요한가: 마스크 없는 {@code TO_CHAR(date)} 는 세션 NLS 설정({@code NLS_DATE_FORMAT} 등)에 따라 {@code
 * 02-JAN-20} 같은 모양이 되고, 숫자 1/0 으로 저장한 BOOLEAN 은 {@code true}/{@code false} 로 검색되지 않는다.
 * 통계(MIN/MAX·상위 값)와 검색이 PG 백엔드와 같은 문자열을 보도록 타입별 명시 마스크를 한 곳에 둔다.
 *
 * <ul>
 *   <li>{@link #ofLogical} — 메타데이터의 논리 타입(TEXT·DECIMAL…) 기준. 통계가 쓴다.
 *   <li>{@link #ofPhysical} — {@code ALL_TAB_COLUMNS} 의 물리 타입 기준. 검색이 쓴다(호출자가 넘기는 타입 힌트는 null·PG
 *       DDL 문자열이 섞여 믿을 수 없다).
 * </ul>
 */
final class TiberoText {

  private TiberoText() {}

  /** DATE 마스크(PG {@code date::text}). */
  private static final String DATE_MASK = "'YYYY-MM-DD'";

  /** TIMESTAMP 마스크 — 마이크로초까지. PG 는 끝자리 0 을 생략하지만 부분 일치 검색에는 영향이 없고, 통계는 Java 후처리로 맞춘다. */
  private static final String TIMESTAMP_MASK = "'YYYY-MM-DD HH24:MI:SS.FF6'";

  /** NUMERIC(18,6) 마스크(소수 6자리 고정, PG {@code 0.500000}). */
  private static final String DECIMAL_MASK = "'FM999999999999990.000000'";

  /**
   * 논리 타입 기준 문자열 식. DATE {@code YYYY-MM-DD}, TIMESTAMP 는 마이크로초까지({@code FF6}, 끝자리 0 제거는 호출부 Java
   * 후처리), DECIMAL 은 NUMERIC(18,6) 의 소수 6자리 고정({@code 0.500000}). 그 외는 기본 변환.
   */
  static String ofLogical(String inner, String logicalType) {
    String type = logicalType == null ? "" : logicalType.trim().toUpperCase(Locale.ROOT);
    return switch (type) {
      case "DATE" -> "TO_CHAR(" + inner + ", " + DATE_MASK + ")";
      case "TIMESTAMP" -> "TO_CHAR(" + inner + ", " + TIMESTAMP_MASK + ")";
      case "DECIMAL", "NUMERIC" -> "TO_CHAR(" + inner + ", " + DECIMAL_MASK + ")";
      default -> "TO_CHAR(" + inner + ")";
    };
  }

  /**
   * 물리 타입 기준 문자열 식. 우리가 만드는 타입별로: DATE·TIMESTAMP 는 위 마스크, {@code NUMBER(1,0)}(BOOLEAN) 은 {@code
   * 'true'/'false'}, 소수 자릿수가 있는 NUMBER 는 그 자릿수 고정 마스크, 그 밖의 NUMBER 는 {@code TO_CHAR(c)}(정수라 NLS 소수점
   * 영향 없음), 문자 타입은 그대로. 타입을 모르면({@code type == null}) {@code TO_CHAR(c)}.
   */
  static String ofPhysical(String inner, TiberoColumnType type) {
    if (type == null || type.dataType() == null) {
      return "TO_CHAR(" + inner + ")";
    }
    String t = type.dataType().toUpperCase(Locale.ROOT);
    if (t.startsWith("TIMESTAMP")) {
      return "TO_CHAR(" + inner + ", " + TIMESTAMP_MASK + ")";
    }
    return switch (t) {
      case "DATE" -> "TO_CHAR(" + inner + ", " + DATE_MASK + ")";
      case "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR" -> inner;
      case "NUMBER" -> numberText(inner, type.precision(), type.scale());
      default -> "TO_CHAR(" + inner + ")";
    };
  }

  /** NUMBER 문자열 식. NUMBER(1,0) 은 BOOLEAN(PG {@code true}/{@code false}), 소수 자릿수 s 가 있으면 s 자리 고정. */
  private static String numberText(String inner, Integer precision, Integer scale) {
    int s = scale == null ? 0 : scale;
    if (precision != null && precision == 1 && s == 0) {
      return "CASE " + inner + " WHEN 1 THEN 'true' WHEN 0 THEN 'false' END";
    }
    if (s > 0) {
      return "TO_CHAR(" + inner + ", 'FM999999999999990." + "0".repeat(s) + "')";
    }
    return "TO_CHAR(" + inner + ")";
  }
}
