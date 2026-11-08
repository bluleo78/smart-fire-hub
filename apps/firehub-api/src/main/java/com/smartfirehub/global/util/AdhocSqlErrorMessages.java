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
 * ERROR / HINT / SQLState / Position} 줄을 그대로 읽는다(2000자를 넘는 긴 오류만은 WD-641 부터 끝의 SQLState·Position 줄을
 * 남기고 본문을 자른다, {@link #limit}). 한국어 안내 줄은 {@link #formatWithGuide}(분석 편집기 직접 실행, WD-571)·{@link
 * #withGuide}(분석 편집기 실행기 경로, WD-571)·{@link #formatForUser}(데이터셋 편집기, jOOQ 메아리 제거 포함)에서만 첫 줄로 붙이고,
 * 둘째 줄부터는 {@link #format} 결과를 그대로 둔다.
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
   * <p><b>실행기 경로(WD-641).</b> 직접 실행 경로는 {@link #SET_STATEMENT_TIMEOUT_SQL} 로 이 값을 건다. 운영 기본 경로에서는
   * 분석 서비스가 이 값을 {@code ExecutorClient#executeQuery(String, int, boolean, int)} 로 넘겨
   * firehub-executor {@code /execute/query} 요청의 {@code timeout_seconds} 로 보내고, 응답은 이 값 + 5초 기다린다.
   * 실행기 자체 기본값(30초)은 이 필드가 없는 요청(구버전 api·3인자 호출부)에만 쓰인다. db-executor(Tibero)는 자기 설정 {@code
   * app.query.timeout-seconds} 를 쓰고 이 필드는 무시한다 — 그 경로의 제한은 이 상수와 따로 맞춰야 한다.
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

  /** 잘라 낸 본문 끝에 붙이는 표시 — 예전 {@code truncate} 와 같은 문구(AI 에이전트·테스트가 이 표시로 잘림을 안다). */
  private static final String TRUNCATED_MARKER = "... [truncated]";

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
    return limit(null, formatUntruncated(e));
  }

  /**
   * {@link #format} 의 자르기 전 문자열(WD-641). 안내를 붙이는 경로가 이미 잘린 문자열에 안내를 더해 다시 자르면 끝의 SQLState·Position
   * 줄이 먼저 잘려 나가므로, 자르기는 안내까지 붙인 뒤 {@link #limit} 한 번으로만 한다.
   */
  private static String formatUntruncated(Exception e) {
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
        return sb.toString();
      }
      return "ERROR: " + nullToEmpty(psql.getMessage());
    }

    String msg = cause.getMessage();
    return msg != null ? msg : e.toString();
  }

  /**
   * 분석 쿼리 편집기용 error 문자열(WD-571) — 안내가 있는 오류면 한국어 안내 한 줄을 앞에 두고, 그 아래에 {@link #format} 결과를 그대로 둔다.
   * 안내가 없으면 {@link #format} 결과와 같다.
   *
   * <p>원문을 지우지 않는 이유: AI 에이전트와 고급 사용자가 SQLState·Position 을 계속 읽어 SQL 을 고친다. 웹은 문자열을 그대로 보여 주므로 첫 줄
   * 안내가 먼저 보인다.
   */
  public static String formatWithGuide(Exception e) {
    return prependGuide(e, formatUntruncated(e));
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
    return prependGuide(e, stripJooqSqlEcho(formatUntruncated(e), executedSql));
  }

  /**
   * 실행기 경로(운영 기본, {@code app.executor.enabled: true})용 error 문자열(WD-571, WD-641) — 직접 실행 경로({@link
   * #formatWithGuide})와 같은 모양으로 만든다: 첫 줄 한국어 안내(있으면), 실행기 원문, 끝에 {@code SQLState: <code>}·{@code
   * Position: <n>} 줄({@link #format} 과 같은 순서·조건). 실행기는 예외 객체가 아니라 원문 문자열과 {@code db_error} 의 {@code
   * sql_state}·{@code position} 만 돌려주므로 {@link #formatWithGuide} 를 쓸 수 없다.
   *
   * <p><b>꼬리 줄을 붙이는 이유(WD-641).</b> 직접 경로는 끝에 SQLState·Position 줄이 있는데 실행기 원문(psycopg2 {@code
   * str(exc)})에는 없어, AI 에이전트·고급 사용자가 경로마다 다른 모양을 봤다. 위치는 libpq 의 {@code LINE n:}/캐럿 문자열을 해석하지 않고
   * 실행기가 구조화해 보낸 {@code db_error.position} 을 쓴다. 실행기 원문에는 SQLState·Position 줄이 없으므로 중복 확인은 하지 않는다.
   * 원문 끝의 줄바꿈(psycopg2 원문은 {@code \n} 으로 끝난다)은 빈 줄이 생기지 않게 떼고 붙인다.
   *
   * <p><b>자르기(WD-641).</b> 실행기 원문도 매우 길 수 있어 다른 경로와 같은 2000자 상한을 {@link #limit} 규칙(안내 첫 줄·끝 꼬리 줄은
   * 남기고 본문만 줄임)으로 적용한다.
   *
   * @param sqlState 실행기 응답 {@code db_error.sql_state}(없으면 null — 검증 거부·구버전 실행기)
   * @param position 실행기 응답 {@code db_error.position}(사용자 SQL 기준 1부터, 없으면 null — 구버전 실행기·위치 없는 오류)
   * @param rawError 실행기 응답 {@code error} 원문(null 가능)
   * @return {@code [안내 + "\n"] + 원문 [+ "\nSQLState: " + 코드] [+ "\nPosition: " + 위치]}(2000자 상한). 원문이
   *     null 이면 안내만(안내도 없으면 null)
   */
  public static String withGuide(String sqlState, Integer position, String rawError) {
    String guide = guide(sqlState);
    if (rawError == null) {
      // 원문이 없으면 꼬리 줄만 덩그러니 두지 않는다 — 예전처럼 안내만(없으면 null).
      return guide;
    }
    boolean hasState = sqlState != null && !sqlState.isBlank();
    boolean hasPosition = position != null && position > 0;
    if (!hasState && !hasPosition) {
      return limit(guide, rawError);
    }
    StringBuilder body = new StringBuilder(stripTrailingNewlines(rawError));
    if (hasState) {
      body.append("\nSQLState: ").append(sqlState);
    }
    if (hasPosition) {
      body.append("\nPosition: ").append(position);
    }
    return limit(guide, body.toString());
  }

  /** 끝의 줄바꿈({@code \n}·{@code \r})을 뗀다 — 꼬리 줄을 붙일 때 빈 줄이 생기지 않게(정규식 없이 한 번 훑는다). */
  private static String stripTrailingNewlines(String s) {
    int end = s.length();
    while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
      end--;
    }
    return s.substring(0, end);
  }

  /**
   * 안내가 있으면 {@code 안내 + "\n" + formatted}, 없으면 formatted — 어느 쪽이든 {@link #limit} 로 2000자 상한을 건다.
   *
   * @param formatted 자르기 전 원문({@link #formatUntruncated} 결과 등)
   */
  private static String prependGuide(Exception e, String formatted) {
    PSQLException psql = psqlOf(e);
    // PSQLException 이 없으면 SQLState 를 알 수 없어 안내를 붙이지 않는다.
    String guide = psql != null ? guide(psql.getSQLState()) : null;
    return limit(guide, formatted);
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

  /**
   * {@code [안내 + "\n"] + 본문} 을 2000자 상한({@link #ERROR_MAX_LEN}) 안으로 맞춘다(WD-641).
   *
   * <p><b>왜 끝을 그냥 자르지 않는가.</b> 예전에는 다 붙인 문자열의 끝을 잘라, 긴 오류에서 AI 에이전트가 SQL 을 고칠 때 읽는 맨 끝의 {@code
   * SQLState:}·{@code Position:} 줄이 가장 먼저 사라졌다. 그래서 첫 줄 안내와 본문 끝의 이 꼬리 줄들을 남기고, 가운데 본문만 남은 길이로 잘라
   * 예전과 같은 {@code ... [truncated]} 표시를 단다. 안내·꼬리가 없으면 결과가 예전 자르기와 글자까지 같다. 자르는 지점이 서로게이트 쌍 가운데면 한
   * 글자 앞에서 자른다.
   *
   * <p>안내와 꼬리만으로 상한을 넘는(현실에서는 없는) 경우에는 전체의 끝을 잘라 상한만은 반드시 지킨다.
   *
   * @param guide 첫 줄 한국어 안내(없으면 null)
   * @param body 원문(null 이면 빈 문자열로 본다 — 예전 {@code truncate} 와 같다)
   */
  static String limit(String guide, String body) {
    String head = guide != null ? guide + "\n" : "";
    String text = body != null ? body : "";
    if (head.length() + text.length() <= ERROR_MAX_LEN) {
      return head + text;
    }
    int tailStart = tailStart(text);
    String tail = text.substring(tailStart); // 꼬리가 없으면 "", 있으면 "\nSQLState: …[\nPosition: …]"
    // 예전 truncate 와 같이 표시 문구 몫으로 20자를 비워 둔다(표시는 15자라 결과는 상한보다 5자 짧다). 전체가 상한을 넘었으므로 본문은
    // 언제나 이 길이보다 길다 — 잘라야 한다.
    int budget = ERROR_MAX_LEN - 20 - head.length() - tail.length();
    if (budget < 0) {
      return cutAt(head + text, ERROR_MAX_LEN - 20) + TRUNCATED_MARKER;
    }
    return head + cutAt(text, budget) + TRUNCATED_MARKER + tail;
  }

  /**
   * 본문 끝에서부터 연속한 구조화 꼬리 줄({@code SQLState:}·{@code Position:})이 시작하는 위치(그 앞 줄바꿈의 인덱스). 꼬리가 없으면 본문
   * 길이. 앞에 줄바꿈이 있는 줄만 보므로 첫 줄은 꼬리로 치지 않는다(본문이 통째로 꼬리가 되지 않게).
   */
  private static int tailStart(String text) {
    int start = text.length();
    int newline = text.lastIndexOf('\n');
    while (newline >= 0
        && (text.startsWith("SQLState: ", newline + 1)
            || text.startsWith("Position: ", newline + 1))) {
      start = newline;
      newline = text.lastIndexOf('\n', newline - 1);
    }
    return start;
  }

  /** {@code s} 의 앞 {@code n} 글자. 끝 글자가 서로게이트 쌍의 앞쪽이면 쌍을 깨지 않게 한 글자 앞에서 자른다(WD-641). */
  private static String cutAt(String s, int n) {
    int end = Math.min(n, s.length());
    if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
      end--;
    }
    return s.substring(0, end);
  }
}
