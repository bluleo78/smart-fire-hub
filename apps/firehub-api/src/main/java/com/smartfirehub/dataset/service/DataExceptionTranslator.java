package com.smartfirehub.dataset.service;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * 데이터셋 테이블에 값을 쓰다 난 DB 데이터 오류(SQLState 클래스 22 — data exception)를 사용자가 이해할 수 있는 한국어 사유 문장으로 바꾼다.
 *
 * <p>DB 메시지 원문은 SQL 문·내부 테이블명·바인딩된 사용자 값을 담고 있어 노출하지 않는다 — SQLState 로 사유를 고르고, 서버 구조화 오류({@link
 * ServerErrorMessage})에서만 컬럼명·길이 상한을 읽는다. 앞뒤 문구(어떤 작업이 실패했는지)는 호출부가 붙인다.
 */
public final class DataExceptionTranslator {

  /** 원인 체인·연쇄 예외 탐색 깊이 상한 — 실제 래핑은 몇 단계뿐이고, 순환 체인에서도 끝나게 한다. */
  private static final int MAX_DEPTH = 16;

  /** PG 의 "value too long for type character varying(N)" 에서 길이 N 만 뽑는다. */
  private static final Pattern VARCHAR_LIMIT = Pattern.compile("character varying\\((\\d+)\\)");

  private DataExceptionTranslator() {}

  /**
   * 데이터 오류면 사유 문장(예: "'code' 컬럼에 글자 수 제한(최대 10자)을 넘는 값이 있습니다"), 아니면 null.
   *
   * <p>다중 행 INSERT 오류에는 행 번호가 없어 행은 특정하지 않는다.
   */
  public static String describe(Throwable e) {
    SQLException dataError = findDataException(e);
    if (dataError == null) {
      return null;
    }
    // 바깥 예외 메시지에는 SQL 문과 사용자 값(예: 값 자체가 "character varying(10)")이 섞여 있어 서버 구조화 오류만 신뢰한다.
    ServerErrorMessage sem =
        dataError instanceof PSQLException pe ? pe.getServerErrorMessage() : null;
    String columnPart =
        sem != null && sem.getColumn() != null && !sem.getColumn().isBlank()
            ? "'" + sem.getColumn() + "' 컬럼에 "
            : "";
    return switch (dataError.getSQLState()) {
      case "22001" -> {
        // string_data_right_truncation — VARCHAR(N) 초과. 길이 N 만 안내한다.
        Matcher m =
            VARCHAR_LIMIT.matcher(sem != null && sem.getMessage() != null ? sem.getMessage() : "");
        String limit = m.find() ? "(최대 " + m.group(1) + "자)" : "";
        yield columnPart + "글자 수 제한" + limit + "을 넘는 값이 있습니다";
      }
      case "22003" ->
          // numeric_value_out_of_range — NUMERIC(18,6) 정수부 초과 또는 BIGINT 범위 초과
          columnPart
              + "숫자 범위를 넘는 값이 있습니다(소수 컬럼은 정수부 최대 "
              + ColumnPhysicalType.DECIMAL_INTEGER_DIGITS
              + "자리)";
      case "22P02" ->
          // invalid_text_representation — 컬럼 타입으로 해석할 수 없는 값
          columnPart + "컬럼 타입에 맞지 않는 형식의 값이 있습니다";
      case "22007", "22008" ->
          // invalid_datetime_format / datetime_field_overflow
          columnPart + "올바르지 않거나 허용 범위를 벗어난 날짜·시간 값이 있습니다";
      case "22021" ->
          // character_not_in_repertoire — 주로 NUL(0x00) 문자
          columnPart + "저장할 수 없는 문자(NUL 문자 등)가 포함된 값이 있습니다";
      default -> columnPart + "저장할 수 없는 값이 있습니다";
    };
  }

  /**
   * 원인 체인(getCause)과 각 SQLException 의 연쇄 예외(getNextException)에서 SQLState 22xxx 예외를 찾는다. 서버 구조화 오류를
   * 가진 {@link PSQLException} 을 우선한다 — 바깥 BatchUpdateException 도 같은 SQLState 를 달고 오지만 컬럼·원문 메시지가 없다.
   */
  private static SQLException findDataException(Throwable e) {
    SQLException fallback = null;
    Throwable t = e;
    for (int i = 0; t != null && i < MAX_DEPTH; i++, t = t.getCause()) {
      if (!(t instanceof SQLException se)) {
        continue;
      }
      SQLException cur = se;
      for (int j = 0; cur != null && j < MAX_DEPTH; j++, cur = cur.getNextException()) {
        String state = cur.getSQLState();
        if (state == null || !state.startsWith("22")) {
          continue;
        }
        if (cur instanceof PSQLException pe && pe.getServerErrorMessage() != null) {
          return pe;
        }
        if (fallback == null) {
          fallback = cur;
        }
      }
    }
    return fallback;
  }
}
