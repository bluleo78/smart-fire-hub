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
 * AnalyticsQueryExecutionService#formatExecutionError})와 글자 하나까지 같아야 한다 — AI 에이전트가 이 문자열의 {@code
 * ERROR / HINT / SQLState / Position} 줄을 그대로 읽는다. 한국어 안내 줄은 {@link #formatWithGuide}(분석 편집기 직접 실행,
 * WD-571)·{@link #withGuide}(분석 편집기 실행기 경로, WD-571)·{@link #formatForUser}(데이터셋 편집기, jOOQ 메아리 제거
 * 포함)에서만 첫 줄로 붙이고, 둘째 줄부터는 {@link #format} 결과를 그대로 둔다.
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

  /**
   * 애드혹 SQL(분석·데이터셋 편집기) 한 문장의 실행 제한 시간(초, WD-571). 실행 경로의 {@code SET LOCAL statement_timeout} 과 시간
   * 초과 안내 문구가 같은 값을 쓰게 해, 제한을 바꿨는데 안내만 옛 숫자를 말하는 일이 없게 한다.
   *
   * <p><b>함께 바꿀 곳.</b> 운영 기본 경로인 파이썬 실행기의 분석 조회({@code
   * apps/firehub-executor/app/services/query_executor.py} 의 {@code execute_query})는 이 상수를 읽지 못해
   * {@code SET LOCAL statement_timeout = '30s'} 를 직접 적어 둔다. 이 값을 바꾸면 그 줄도 같은 값으로 바꿔야 실행기 경로의 안내 숫자가
   * 실제 제한과 맞는다({@code ExecutorClient#executeQuery} 의 응답 대기 35초도 「제한 + 5초」다).
   */
  public static final int ADHOC_STATEMENT_TIMEOUT_SECONDS = 30;

  /** {@code SET LOCAL statement_timeout} 문장 — 위 상수로 만든다(WD-571). */
  public static final String SET_STATEMENT_TIMEOUT_SQL =
      "SET LOCAL statement_timeout = '" + ADHOC_STATEMENT_TIMEOUT_SECONDS + "s'";

  /**
   * 쿼리 취소(57014) 안내(WD-571). 제한 시간과 줄이는 방법을 함께 알려 준다 — 원문({@code canceling statement due to
   * statement timeout})만으로는 몇 초 제한인지·무엇을 바꿔야 하는지 알 수 없었다.
   *
   * <p><b>원문으로 가르지 않는 이유.</b> 57014 는 statement_timeout 과 관리자 취소({@code pg_cancel_backend}) 모두에서
   * 나오지만, 서버 원문은 {@code lc_messages} 설정에 따라 현지화될 수 있어 영어 문구 대조는 믿을 수 없다(실행기도 WD-261 에서 원문 대조를 버렸다).
   * 그래서 두 경우를 함께 말하는 안내 하나를 직접 실행·실행기 경로 모두에 쓴다 — 편집기에서는 거의 언제나 시간 초과다.
   */
  public static final String STATEMENT_TIMEOUT_GUIDE =
      "쿼리 실행이 "
          + ADHOC_STATEMENT_TIMEOUT_SECONDS
          + "초 제한 시간을 넘어 중단됐습니다(드물게 관리자가 취소한 경우일 수도 있습니다). 조건(WHERE)을 좁히거나 LIMIT 을 쓰거나 조인 대상을"
          + " 줄여 다시 실행하세요.";

  /**
   * 없는 테이블(42P01) 안내(WD-571). 파이프라인 문장({@code PipelineSqlErrorMessages#guide})은 스키마 한정자를 말하는데,
   * 편집기에서는 검색 경로가 데이터셋 스키마로 잡혀 있어 데이터셋 테이블 이름을 확인하라고 따로 둔다.
   */
  public static final String UNDEFINED_TABLE_GUIDE =
      "SQL 에 없는 테이블 이름이 있습니다. 테이블 이름 철자와 데이터셋의 테이블 이름을 확인하세요.";

  /**
   * 없는 함수·타입이 맞지 않는 연산자/함수(42883) 안내(WD-571). 42883 은 {@code text = integer} 같은 비교와 없는 함수 호출에서 모두
   * 나오므로 두 경우를 함께 말하고, 편집기에서 흔한 타입 맞추기 예시를 든다.
   */
  static final String UNDEFINED_FUNCTION_GUIDE =
      "없는 함수이거나, 값의 타입이 맞지 않아 비교·계산하거나 함수를 부를 수 없습니다. 함수 이름을 확인하고 비교하는 두 값의 타입을 맞추세요"
          + "(예: CAST(컬럼 AS integer) 또는 '값'::text).";

  /** 날짜·시간 형식(22007)·범위(22008) 오류 안내(WD-571). to_date·::date 같은 변환에서 흔하다. */
  static final String DATETIME_FORMAT_GUIDE =
      "날짜·시간 값의 형식이나 범위가 올바르지 않습니다. 값과 형식 문자열(예: to_date 의 'YYYY-MM-DD')을 확인하세요.";

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
   * 분석 쿼리 편집기용 error 문자열(WD-571) — 안내가 있는 오류면 한국어 안내 한 줄을 앞에 두고, 그 아래에 {@link #format} 결과를 그대로 둔다.
   * 안내가 없으면 {@link #format} 결과와 같다.
   *
   * <p>원문을 지우지 않는 이유: AI 에이전트와 고급 사용자가 SQLState·Position 을 계속 읽어 SQL 을 고친다. 웹은 문자열을 그대로 보여 주므로 첫 줄
   * 안내가 먼저 보인다.
   */
  public static String formatWithGuide(Exception e) {
    return prependGuide(e, format(e));
  }

  /**
   * 데이터셋 「SQL」 편집기용 error 문자열(WD-421) — {@link #formatWithGuide} 와 같은 안내 줄 + 원문이되, 원문에서 jOOQ 메아리를
   * 뗀다. 쿼리 이력에도 이 문자열이 그대로 저장된다.
   *
   * <p>PSQLException 이 없는 예외는 원인 메시지에 jOOQ 의 {@code SQL [실행 SQL]; } 메아리가 남을 수 있어 걷어낸다 — 실행 SQL 에는
   * 사용자가 쓰지 않은 행 제한({@code LIMIT 1000})이 붙어 있다. 메아리에 담긴 SQL 은 대괄호({@code arr[1]})·여러 줄을 담을 수 있어,
   * 실제로 실행한 문자열을 알면 그 문자열 그대로 떼고, 모를 때만 정규식으로 뗀다. 메아리는 원문 맨 앞에 있으므로 안내를 붙이기 전에 뗀다.
   *
   * <p>Position 은 사용자 SQL 기준이다 — 행 제한은 SQL 뒤에 덧붙이기만 하므로(SqlLexicalMask#applyRowLimit) 앞부분 위치가 바뀌지
   * 않는다.
   *
   * @param e 실행 예외
   * @param executedSql 실제로 DB 에 보낸 SQL(메아리 정확 제거용, 모르면 null)
   */
  public static String formatForUser(Exception e, String executedSql) {
    return prependGuide(e, stripJooqSqlEcho(format(e), executedSql));
  }

  /**
   * 실행기 경로(운영 기본, {@code app.executor.enabled: true})용 error 문자열(WD-571) — 실행기가 돌려준 원문 앞에 SQLState
   * 로 고른 한국어 안내 한 줄을 붙인다. 실행기는 예외 객체가 아니라 원문 문자열과 {@code db_error.sql_state} 만 돌려주므로 {@link
   * #formatWithGuide} 를 쓸 수 없다.
   *
   * <p>원문은 손대지 않는다(자르지도 않는다) — 이 경로는 원래 실행기 원문을 그대로 돌려줬고, 안내 한 줄만 앞에 더한다.
   *
   * @param sqlState 실행기 응답 {@code db_error.sql_state}(없으면 null — 검증 거부·구버전 실행기)
   * @param rawError 실행기 응답 {@code error} 원문(null 가능)
   * @return 안내가 있으면 {@code 안내 + "\n" + 원문}(원문이 null 이면 안내만), 없으면 원문 그대로
   */
  public static String withGuide(String sqlState, String rawError) {
    String guide = guide(sqlState);
    if (guide == null) {
      return rawError;
    }
    return rawError != null ? guide + "\n" + rawError : guide;
  }

  /** 안내가 있으면 {@code 안내 + "\n" + formatted}(2000자 상한), 없으면 formatted 그대로. */
  private static String prependGuide(Exception e, String formatted) {
    PSQLException psql = psqlOf(e);
    // PSQLException 이 없으면 SQLState 를 알 수 없어 안내를 붙이지 않는다.
    String guide = psql != null ? guide(psql.getSQLState()) : null;
    return guide != null ? truncate(guide + "\n" + formatted) : formatted;
  }

  /**
   * SQLState 별 한국어 안내 — 편집기에서 흔한 오류만 둔다(모르는 코드는 null — 안내를 추측해 붙이지 않는다). 서버 원문은 보지 않는다(현지화될 수 있다,
   * {@link #STATEMENT_TIMEOUT_GUIDE} 참고).
   *
   * @param sqlState PG SQLState
   */
  static String guide(String sqlState) {
    if (sqlState == null) {
      return null;
    }
    return switch (sqlState) {
      case "42703" -> UNDEFINED_COLUMN_GUIDE;
      case "22012" -> DIVISION_BY_ZERO_GUIDE;
      case "22P02" -> INVALID_TEXT_REPRESENTATION_GUIDE;
      // WD-571 — 분석 편집기에서 영어 원문만 나가던 흔한 오류들
      case "42P01" -> UNDEFINED_TABLE_GUIDE;
      case "42883" -> UNDEFINED_FUNCTION_GUIDE;
      case "22007", "22008" -> DATETIME_FORMAT_GUIDE;
      case "57014" -> STATEMENT_TIMEOUT_GUIDE;
      default -> null;
    };
  }

  /** {@link #format} 과 같은 방식(cause 체인 unwrap)으로 찾은 PSQLException. 없으면 null. */
  private static PSQLException psqlOf(Exception e) {
    Throwable cause = e;
    while (cause.getCause() != null && !(cause instanceof PSQLException)) {
      cause = cause.getCause();
    }
    return cause instanceof PSQLException psql ? psql : null;
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
