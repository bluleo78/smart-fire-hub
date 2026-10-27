package com.smartfirehub.dataset.service;

import java.sql.SQLException;
import java.util.function.Predicate;
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
    return describeDataError(
        dataError.getSQLState(),
        sem != null ? sem.getColumn() : null,
        sem != null ? sem.getMessage() : null);
  }

  /**
   * SQLState 22xxx 하나를 사유 문장으로 바꾼다 — {@link #describe} 와 파이프라인 스텝 오류 안내(WD-111, {@code
   * PipelineSqlErrorMessages})가 같은 문구를 쓰도록 한곳에 둔다. 파이프라인 실행기(firehub-executor)는 예외 객체가 아니라
   * SQLState·컬럼·서버 메시지 필드만 돌려주므로 예외가 아닌 필드로 받는다.
   *
   * @param sqlState 22 로 시작하는 SQLState
   * @param column 서버 구조화 오류의 컬럼명(없으면 null)
   * @param serverMessage 서버 구조화 오류의 주 메시지(길이 상한을 읽는 데만 쓴다, 없으면 null)
   */
  public static String describeDataError(String sqlState, String column, String serverMessage) {
    String columnPart = column != null && !column.isBlank() ? "'" + column + "' 컬럼에 " : "";
    return switch (sqlState) {
      case "22001" -> {
        // string_data_right_truncation — VARCHAR(N) 초과. 길이 N 만 안내한다.
        Matcher m = VARCHAR_LIMIT.matcher(serverMessage != null ? serverMessage : "");
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
   * 원인 체인(getCause)과 각 SQLException 의 연쇄 예외(getNextException)에서 PostgreSQL 서버 구조화 오류(SQLState·제약
   * 이름·세부)를 찾는다(없으면 null). 배치 실행은 바깥 BatchUpdateException 이 제약 이름 없이 오고 실제 오류는 연쇄 예외에 있어 둘 다 본다.
   *
   * <p>제약 이름으로 도메인 오류를 고르는 쓰기 지점(필드명·보안 등급 이름 중복)과 전역 무결성 처리기(외래 키 방향)가 함께 쓴다.
   */
  public static ServerErrorMessage findServerError(Throwable e) {
    SQLException found = findSqlException(e, DataExceptionTranslator::hasServerError);
    return found != null ? ((PSQLException) found).getServerErrorMessage() : null;
  }

  /**
   * 원인 체인(getCause)과 각 SQLException 의 연쇄 예외(getNextException)를 걸으며 {@code matches} 를 만족하는 첫
   * SQLException 을 찾는다(없으면 null). 두 방향 모두 {@link #MAX_DEPTH} 까지만 걷는다 — 순환 체인에서도 끝난다.
   *
   * <p>이 클래스의 탐색들과 파이프라인 PYTHON 스텝의 적재 실패 문구({@code PythonStepErrorMessages.loadFailed} — Tibero 벤더
   * 코드를 SQLState 로 바꿔 본다)가 같은 걷기·깊이 상한을 쓰도록 한 곳에 둔다.
   */
  public static SQLException findSqlException(Throwable e, Predicate<SQLException> matches) {
    Throwable t = e;
    for (int i = 0; t != null && i < MAX_DEPTH; i++, t = t.getCause()) {
      if (!(t instanceof SQLException se)) {
        continue;
      }
      SQLException cur = se;
      for (int j = 0; cur != null && j < MAX_DEPTH; j++, cur = cur.getNextException()) {
        if (matches.test(cur)) {
          return cur;
        }
      }
    }
    return null;
  }

  /** 서버 구조화 오류를 가진 PostgreSQL 예외인가. */
  private static boolean hasServerError(SQLException e) {
    return e instanceof PSQLException pe && pe.getServerErrorMessage() != null;
  }

  /** SQLState 클래스 22(data exception)인가. */
  private static boolean isDataError(SQLException e) {
    String state = e.getSQLState();
    return state != null && state.startsWith("22");
  }

  /**
   * 원인 체인(getCause)과 각 SQLException 의 연쇄 예외(getNextException)에서 SQLState 22xxx 예외를 찾는다. 서버 구조화 오류를
   * 가진 {@link PSQLException} 을 우선한다 — 바깥 BatchUpdateException 도 같은 SQLState 를 달고 오지만 컬럼·원문 메시지가 없다.
   */
  private static SQLException findDataException(Throwable e) {
    // 서버 구조화 오류를 가진 22xxx 를 먼저 찾고, 없으면 처음 만난 22xxx 로 물러선다(오류 경로라 두 번 걸어도 된다).
    SQLException structured = findSqlException(e, x -> isDataError(x) && hasServerError(x));
    return structured != null
        ? structured
        : findSqlException(e, DataExceptionTranslator::isDataError);
  }
}
