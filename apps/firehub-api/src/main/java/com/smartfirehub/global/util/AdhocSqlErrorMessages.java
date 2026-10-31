package com.smartfirehub.global.util;

import java.util.regex.Pattern;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * 사용자가 직접 쓴 애드혹 SQL(분석 쿼리 편집기·데이터셋 「SQL」 편집기)이 DB 에서 거부됐을 때 응답 error 필드에 실을 문구를 만든다.
 *
 * <p><b>왜 한곳에 두는가(WD-421).</b> 분석 편집기는 PSQLException 의 서버 구조화 오류를 {@code ERROR: … / SQLState: … /
 * Position: …} 로 풀어 보여 줬지만(PR-2, refs #267, #272), 데이터셋 SQL 편집기는 jOOQ 예외 메시지를 그대로 실어 {@code SQL
 * [SELECT nope FROM t LIMIT 1000]; ERROR: …} 처럼 내부에서 덧붙인 행 제한과 jOOQ 메아리가 화면·쿼리 이력에 그대로 드러났다. 두 편집기가
 * 같은 포맷을 쓰도록 분석 쪽 포맷터를 이리로 옮기고, 데이터셋 화면은 여기에 한국어 안내 한 줄을 앞에 붙인다.
 *
 * <p>{@link #format} 의 출력은 옮기기 전 분석 경로({@code
 * AnalyticsQueryExecutionService#formatExecutionError})와 글자 하나까지 같아야 한다 — 분석 편집기와 AI 에이전트가 이 문자열을
 * 그대로 읽는다. 안내 줄·jOOQ 메아리 제거는 {@link #formatForUser} 에만 둔다.
 */
public final class AdhocSqlErrorMessages {

  /** 모든 경로의 error 문자열 상한 — 거대한 원문(긴 SQL 메아리 등)이 응답·이력을 부풀리지 않게 한다. */
  static final int ERROR_MAX_LEN = 2000;

  /** 없는 컬럼(42703) 안내 — 파이프라인 스텝 실패 안내({@code PipelineSqlErrorMessages#guide})와 같은 문장을 쓴다. */
  public static final String UNDEFINED_COLUMN_GUIDE =
      "SQL 에 없는 컬럼 이름이 있습니다. 컬럼 이름 철자와 참조한 테이블에 그 컬럼이 있는지 확인하세요.";

  /** 0 나눗셈(22012) 안내 — 파이프라인 스텝 실패 안내와 같은 문장을 쓴다. */
  public static final String DIVISION_BY_ZERO_GUIDE =
      "0 으로 나누는 계산이 있습니다. NULLIF(분모, 0) 등으로 0 나눗셈을 피하세요.";

  /**
   * 형 변환 실패(22P02) 안내. 파이프라인·데이터셋 쓰기의 22P02 문장은 "출력 컬럼에 맞지 않는 값" 을 말하는데, 조회 편집기에서는 대개 {@code
   * 'abc'::int} 같은 변환 실패라 따로 둔다.
   */
  static final String INVALID_TEXT_REPRESENTATION_GUIDE =
      "값을 지정한 타입으로 바꿀 수 없습니다. 값의 형식과 CAST(값 AS 타입)·::타입 변환 대상을 확인하세요.";

  /** jOOQ 가 DataAccessException 메시지 앞에 붙이는 실행 SQL 메아리 {@code SQL [...]; } (여러 줄 SQL 포함). */
  private static final Pattern JOOQ_SQL_ECHO = Pattern.compile("^SQL \\[.*?\\]; ", Pattern.DOTALL);

  private AdhocSqlErrorMessages() {}

  /**
   * 실행 예외를 error 문자열로 바꾼다(분석 편집기 포맷 — 동작 변경 금지).
   *
   * <p>분해 우선순위:
   *
   * <ol>
   *   <li>cause 체인 unwrap → PSQLException
   *   <li>ServerErrorMessage 있으면 MESSAGE / HINT / DETAIL / SQLState / Position 조립
   *   <li>PSQL 인데 sem 없으면 psql.getMessage() 만
   *   <li>PSQL 아니면 원본 메시지 (또는 toString)
   * </ol>
   */
  public static String format(Exception e) {
    Throwable cause = e;
    while (cause.getCause() != null && !(cause instanceof PSQLException)) {
      cause = cause.getCause();
    }

    if (cause instanceof PSQLException psql) {
      ServerErrorMessage sem = psql.getServerErrorMessage();
      if (sem != null) {
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR: ").append(nullToEmpty(sem.getMessage()));
        appendIfPresent(sb, "\nHINT: ", sem.getHint());
        appendIfPresent(sb, "\nDETAIL: ", sem.getDetail());
        appendIfPresent(sb, "\nSQLState: ", sem.getSQLState());
        if (sem.getPosition() > 0) {
          sb.append("\nPosition: ").append(sem.getPosition());
        }
        return truncate(sb.toString());
      }
      return truncate("ERROR: " + nullToEmpty(psql.getMessage()));
    }

    String msg = cause.getMessage();
    return truncate(msg != null ? msg : e.toString());
  }

  /**
   * 데이터셋 「SQL」 편집기용 error 문자열(WD-421) — 흔한 오류(42703·22012·22P02)면 한국어 안내 한 줄을 앞에 두고, 그 아래에 {@link
   * #format} 결과를 둔다. 쿼리 이력에도 이 문자열이 그대로 저장된다.
   *
   * <p>PSQLException 이 없는 예외는 원인 메시지에 jOOQ 의 {@code SQL [실행 SQL]; } 메아리가 남을 수 있어 걷어낸다 — 실행 SQL 에는
   * 사용자가 쓰지 않은 행 제한({@code LIMIT 1000})이 붙어 있다. 메아리에 담긴 SQL 은 대괄호({@code arr[1]})·여러 줄을 담을 수 있어,
   * 실제로 실행한 문자열을 알면 그 문자열 그대로 떼고, 모를 때만 정규식으로 뗀다.
   *
   * <p>Position 은 사용자 SQL 기준이다 — 행 제한은 SQL 뒤에 덧붙이기만 하므로(SqlLexicalMask#applyRowLimit) 앞부분 위치가 바뀌지
   * 않는다.
   *
   * @param e 실행 예외
   * @param executedSql 실제로 DB 에 보낸 SQL(메아리 정확 제거용, 모르면 null)
   */
  public static String formatForUser(Exception e, String executedSql) {
    String formatted = stripJooqSqlEcho(format(e), executedSql);
    String guide = guide(sqlStateOf(e));
    return guide != null ? truncate(guide + "\n" + formatted) : formatted;
  }

  /** SQLState 별 한국어 안내 — 이 화면에서 흔한 세 가지만 둔다(모르는 코드는 null — 안내를 추측해 붙이지 않는다). */
  static String guide(String sqlState) {
    if (sqlState == null) {
      return null;
    }
    return switch (sqlState) {
      case "42703" -> UNDEFINED_COLUMN_GUIDE;
      case "22012" -> DIVISION_BY_ZERO_GUIDE;
      case "22P02" -> INVALID_TEXT_REPRESENTATION_GUIDE;
      default -> null;
    };
  }

  /** {@link #format} 과 같은 방식(cause 체인 unwrap)으로 찾은 PSQLException 의 SQLState. 없으면 null. */
  private static String sqlStateOf(Exception e) {
    Throwable cause = e;
    while (cause.getCause() != null && !(cause instanceof PSQLException)) {
      cause = cause.getCause();
    }
    return cause instanceof PSQLException psql ? psql.getSQLState() : null;
  }

  /** 문구 맨 앞의 jOOQ {@code SQL [...]; } 메아리를 뗀다. 실행 SQL 을 알면 그 문자열 그대로, 모르면 정규식으로 뗀다. */
  static String stripJooqSqlEcho(String message, String executedSql) {
    if (message == null || !message.startsWith("SQL [")) {
      return message;
    }
    if (executedSql != null) {
      String exact = "SQL [" + executedSql + "]; ";
      if (message.startsWith(exact)) {
        return message.substring(exact.length());
      }
    }
    return JOOQ_SQL_ECHO.matcher(message).replaceFirst("");
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static void appendIfPresent(StringBuilder sb, String prefix, String value) {
    if (value != null && !value.isBlank()) {
      sb.append(prefix).append(value);
    }
  }

  private static String truncate(String s) {
    if (s == null) return "";
    if (s.length() <= ERROR_MAX_LEN) return s;
    return s.substring(0, ERROR_MAX_LEN - 20) + "... [truncated]";
  }
}
