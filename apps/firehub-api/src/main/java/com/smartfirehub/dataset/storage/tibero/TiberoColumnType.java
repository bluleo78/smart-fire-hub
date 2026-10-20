package com.smartfirehub.dataset.storage.tibero;

import java.util.Locale;

/**
 * {@code ALL_TAB_COLUMNS} 한 행의 물리 타입(WD-74). 스테이징 DDL 재구성·검색 문자열 식·인덱스 키 폭 계산이 같은 정보를 쓴다.
 *
 * @param dataType {@code DATA_TYPE}(예: VARCHAR2, NUMBER, DATE, TIMESTAMP(6))
 * @param dataLength {@code DATA_LENGTH} — 바이트 길이(CHAR 의미 컬럼도 바이트로 환산된 값)
 * @param precision {@code DATA_PRECISION}
 * @param scale {@code DATA_SCALE}
 * @param charUsed {@code CHAR_USED} — {@code C} 면 문자 의미({@code VARCHAR2(n CHAR)}), {@code B} 면 바이트
 *     의미
 * @param charLength {@code CHAR_LENGTH} — 문자 의미일 때 선언 문자 수
 */
record TiberoColumnType(
    String dataType,
    Integer dataLength,
    Integer precision,
    Integer scale,
    String charUsed,
    Integer charLength) {

  /** 바이트 의미만 아는 간단한 생성(테스트·기존 호출용). */
  static TiberoColumnType of(
      String dataType, Integer dataLength, Integer precision, Integer scale) {
    return new TiberoColumnType(dataType, dataLength, precision, scale, null, null);
  }

  /**
   * 같은 타입의 DDL 문자열을 다시 만든다. 문자 의미({@code CHAR_USED='C'}) VARCHAR2 는 {@code VARCHAR2(CHAR_LENGTH
   * CHAR)} 로 CHAR 의미를 유지한다(바이트로 바꾸면 스테이징이 대상보다 좁아져 한글 값이 ORA-12899 로 거부된다). 정밀도 없는 NUMBER 는 {@code
   * NUMBER}, TIMESTAMP 계열은 {@code TIMESTAMP}. 우리가 만드는 타입 밖(예: CLOB)은 넉넉한 {@code VARCHAR2(4000)} 로
   * 받는다.
   */
  String ddl() {
    String type = dataType.toUpperCase(Locale.ROOT);
    if (type.startsWith("TIMESTAMP")) {
      return "TIMESTAMP";
    }
    return switch (type) {
      case "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR" -> {
        if ("C".equalsIgnoreCase(charUsed) && charLength != null) {
          yield "VARCHAR2(" + charLength + " CHAR)";
        }
        yield "VARCHAR2(" + (dataLength != null ? dataLength : TiberoDialect.MAX_VARCHAR) + ")";
      }
      case "NUMBER" ->
          precision == null
              ? "NUMBER"
              : "NUMBER(" + precision + "," + (scale != null ? scale : 0) + ")";
      case "DATE" -> "DATE";
      default -> "VARCHAR2(" + TiberoDialect.MAX_VARCHAR + ")";
    };
  }

  /**
   * 인덱스 키에서 차지하는 선언 바이트 폭. 문자 타입은 {@code DATA_LENGTH}(실제 바이트 한도), NUMBER 22, DATE 7, TIMESTAMP 11.
   * 모르는 타입은 보수적으로 4000.
   */
  int indexKeyBytes() {
    String type = dataType.toUpperCase(Locale.ROOT);
    if (type.startsWith("TIMESTAMP")) {
      return 11;
    }
    return switch (type) {
      case "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR" ->
          dataLength != null ? dataLength : TiberoDialect.MAX_VARCHAR;
      case "NUMBER" -> 22;
      case "DATE" -> 7;
      default -> TiberoDialect.MAX_VARCHAR;
    };
  }
}
