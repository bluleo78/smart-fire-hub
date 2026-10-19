package com.smartfirehub.global.util;

import com.smartfirehub.dataset.exception.SqlQueryException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared SQL preprocessing utilities used by DataTableQueryService and
 * AnalyticsQueryExecutionService.
 *
 * <p><b>이 클래스는 보안 판정을 하지 않는다.</b> 하는 일은 주석 제거, 다중 문 거부, 첫 단어 기반 문 타입 판별(`detectQueryType`)뿐이다. 스키마
 * 화이트리스트·차단 함수·`pg_*` 카탈로그 그림자 차단 등 실제 보안 판정은 {@link
 * com.smartfirehub.pipeline.service.validator.SqlValidator}(AST 기반)가 전담한다. 이 클래스는 그 앞단에서 호출되는 전처리기일
 * 뿐이며, 여기 남은 첫 단어 키워드 화이트리스트는 `SqlValidator` 를 우회할 수 없다는 전제하의 심층 방어(defense in depth)일 뿐 단독 방어선이
 * 아니다(이슈 #385).
 */
public final class SqlValidationUtils {

  private static final Set<String> ALLOWED_KEYWORDS =
      Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "WITH");

  /**
   * 허용 목록 밖의 알려진 SQL 명령어. 거부 메시지를 "DROP 문은 실행할 수 없습니다"(금지)와 "철자를 확인하세요"(명령어 아님) 중 고르는 데만 쓴다 — 판정은
   * {@link #ALLOWED_KEYWORDS} 만 본다.
   */
  private static final Set<String> KNOWN_DISALLOWED_COMMANDS =
      Set.of(
          "DROP",
          "CREATE",
          "ALTER",
          "TRUNCATE",
          "GRANT",
          "REVOKE",
          "COPY",
          "MERGE",
          "CALL",
          "DO",
          "SET",
          "RESET",
          "SHOW",
          "VACUUM",
          "ANALYZE",
          "ANALYSE",
          "EXPLAIN",
          "BEGIN",
          "START",
          "COMMIT",
          "ROLLBACK",
          "LOCK",
          "COMMENT",
          "REINDEX",
          "CLUSTER",
          "REFRESH",
          "DISCARD",
          "LISTEN",
          "NOTIFY",
          "UNLISTEN",
          "PREPARE",
          "EXECUTE",
          "DEALLOCATE",
          "DECLARE",
          "FETCH",
          "MOVE",
          "CLOSE",
          "IMPORT",
          "SECURITY",
          "VALUES",
          "TABLE",
          "CHECKPOINT",
          "LOAD",
          "SAVEPOINT",
          "RELEASE",
          "ABORT",
          "END");

  /** 구두점이 붙은 첫 토큰({@code SELECT*FROM})에서 명령어 부분을 뽑는다. */
  private static final Pattern LEADING_ASCII_LETTERS = Pattern.compile("^[A-Za-z]+");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** 메시지에 싣는 사용자 입력 조각의 최대 길이(코드 포인트) — 거대한 입력이 오류 표시를 덮지 않게 한다. */
  private static final int MAX_TOKEN_DISPLAY_LENGTH = 30;

  private SqlValidationUtils() {}

  /**
   * Strip block and line comments from SQL, then validate it is a single statement starting with an
   * allowed keyword.
   *
   * @param sql raw SQL input from the user
   * @return stripped SQL without comments (trailing semicolon preserved then removed internally)
   * @throws SqlQueryException if validation fails
   */
  public static String stripAndValidate(String sql) {
    String stripped =
        sql.strip()
            .replaceAll("(?s)/\\*.*?\\*/", " ") // block comments (multiline-safe)
            .replaceAll("--[^\n]*", " ") // line comments
            .strip();

    // Reject multi-statement SQL (semicolons not at the very end)
    String withoutTrailingSemicolon = stripped.replaceAll(";\\s*$", "");
    if (withoutTrailingSemicolon.contains(";")) {
      // 허용 문장 목록은 싣지 않는다 — 읽기 전용 애널리틱스와 DML 을 허용하는 데이터셋 SQL 탭이 이 검사를 공유한다.
      throw new SqlQueryException("SQL 문장은 한 번에 하나만 실행할 수 있습니다. 세미콜론(;)으로 나뉜 문장을 하나씩 실행하세요.");
    }

    String firstToken = stripped.split("\\s+")[0];
    String firstWord = firstToken.toUpperCase();
    if (!ALLOWED_KEYWORDS.contains(firstWord)) {
      throw new SqlQueryException(rejectedStartMessage(withoutTrailingSemicolon, firstToken));
    }

    return stripped;
  }

  /**
   * 첫 단어 검사에서 거부된 SQL 의 원인별 메시지를 고른다(판정은 호출부가 끝냈다). 빈 SQL · 허용 키워드에 구두점이 붙음 · 금지 명령어 · 괄호/기호 시작 · 그
   * 밖(오타)으로 나눠, 사용자가 문법 오류인지 금지된 문장인지 알 수 있게 한다.
   */
  private static String rejectedStartMessage(String withoutTrailingSemicolon, String firstToken) {
    if (withoutTrailingSemicolon.isBlank()) {
      return "실행할 SQL 이 없습니다.";
    }
    Matcher leading = LEADING_ASCII_LETTERS.matcher(firstToken);
    if (leading.find()) {
      String word = leading.group();
      String upper = word.toUpperCase(Locale.ROOT);
      if (ALLOWED_KEYWORDS.contains(upper)) {
        // 키워드 바로 뒤에 구두점이 붙어 첫 토큰이 키워드와 달라졌다 — 공백을 넣으면 첫 단어 검사를 지난다.
        return "'" + word + "' 뒤에 공백을 넣어 다시 실행하세요.";
      }
      if (KNOWN_DISALLOWED_COMMANDS.contains(upper)) {
        return upper + " 문은 실행할 수 없습니다.";
      }
    } else if (!Character.isLetter(firstToken.codePointAt(0))) {
      // (SELECT 1) 은 PG 에선 맞는 문장이라 "문법 오류"라 하지 않는다. 기호·숫자 시작은 토큰 대신 시작 규칙만 안내한다.
      return firstToken.startsWith("(")
          ? "괄호로 시작하는 문장은 실행할 수 없습니다. SELECT 나 WITH 로 시작하게 바꿔 주세요."
          : "SQL 문장은 SELECT·WITH 같은 SQL 명령어로 시작해야 합니다.";
    }
    return "SQL 문법 오류: '" + displayToken(firstToken) + "' 은(는) SQL 명령어가 아닙니다. 철자를 확인하세요.";
  }

  /** 메시지에 실을 사용자 입력 조각을 공백 한 칸으로 접고, 길면 코드 포인트 단위로 잘라 "…"를 붙인다. */
  public static String displayToken(String token) {
    String collapsed = WHITESPACE.matcher(token.strip()).replaceAll(" ");
    if (collapsed.codePointCount(0, collapsed.length()) <= MAX_TOKEN_DISPLAY_LENGTH) {
      return collapsed;
    }
    return collapsed.substring(0, collapsed.offsetByCodePoints(0, MAX_TOKEN_DISPLAY_LENGTH)) + "…";
  }

  /**
   * WITH 문의 CTE 안에 있는 데이터 수정 키워드를 돌려준다(없으면 null). 파싱 실패 메시지 전용 — 판정은 {@link #detectQueryType} 이 한다.
   *
   * <p>{@link SqlLexicalMask#mask} 로 주석·리터럴·큰따옴표 식별자·달러 인용을 먼저 가려, 그 안의 DML 단어({@code -- delete
   * old}, {@code "update"})를 원인으로 잘못 안내하지 않게 한다.
   */
  public static String cteInnerDmlKeyword(String sql) {
    String masked =
        removeTrailingSemicolon(SqlLexicalMask.mask(sql).strip()).toUpperCase(Locale.ROOT);
    if (!"WITH".equals(masked.split("\\s+")[0])) {
      return null;
    }
    return detectCteInnerDml(masked);
  }

  /**
   * Detect the main DML verb of a (possibly CTE-prefixed) SQL string. Assumes the SQL has already
   * been stripped by {@link #stripAndValidate}.
   *
   * <p>CTE 쿼리의 경우 문자열 리터럴 내부에 DML 키워드가 포함될 수 있으므로, 단순 정규식 매칭 대신 괄호 깊이를 추적하여 최상위 레벨의 본문 키워드만 확인한다.
   *
   * @param stripped SQL string without comments
   * @return "SELECT", "INSERT", "UPDATE", or "DELETE"
   */
  public static String detectQueryType(String stripped) {
    String withoutTrailingSemicolon = stripped.replaceAll(";\\s*$", "");
    String firstWord = withoutTrailingSemicolon.split("\\s+")[0].toUpperCase();

    if ("WITH".equals(firstWord)) {
      // 문자열 리터럴을 제거한 후 괄호 깊이를 추적하여 최상위 레벨 키워드를 확인한다.
      // 단순 upper().contains() 방식은 CTE 컬럼 리터럴 'needs update' 같은 값을 오탐한다.
      String noLiterals = stripStringLiterals(withoutTrailingSemicolon).toUpperCase();

      // 보안 (이슈 #227): 데이터 수정 CTE(`WITH d AS (DELETE/UPDATE/INSERT/MERGE/TRUNCATE ... RETURNING)
      // SELECT ...`)는
      // 본문이 SELECT여도 PostgreSQL이 CTE 내부 DML을 실제로 실행한다. 따라서 readOnly 모드에서 SELECT로 분류하면
      // 우회가 발생한다. CTE 정의 내부에서 DML 키워드가 발견되면 해당 DML 타입을 반환해 readOnly 검증이 차단하도록 한다.
      String cteDml = detectCteInnerDml(noLiterals);
      if (cteDml != null) {
        return cteDml;
      }

      return detectCteBodyKeyword(noLiterals);
    }
    return firstWord;
  }

  /**
   * WITH 절 내부의 CTE 정의(괄호 안)에서 DML 키워드를 탐지한다.
   *
   * <p>data-modifying CTE는 PostgreSQL 표준 기능으로, `WITH name AS (DELETE/UPDATE/INSERT/MERGE/TRUNCATE
   * ... RETURNING)` 형태가 가능하며 본문이 SELECT여도 내부 DML이 실제로 실행된다. readOnly 검증을 우회하지 못하도록 CTE 내부에서 DML이
   * 발견되면 해당 키워드를 반환한다.
   *
   * <p>괄호 깊이 1 이상(CTE 본체)에서 단어 경계로 구분된 DML 키워드를 탐색한다. 문자열 리터럴은 호출 전에 이미 제거되어 있어야 한다.
   *
   * @param upperNoLiterals 대문자 변환 + 문자열 리터럴 제거된 SQL
   * @return CTE 내부에서 발견된 DML 키워드("DELETE"/"UPDATE"/"INSERT"/"MERGE"/"TRUNCATE"), 없으면 null
   */
  static String detectCteInnerDml(String upperNoLiterals) {
    int depth = 0;
    int len = upperNoLiterals.length();
    int i = 0;

    while (i < len) {
      char c = upperNoLiterals.charAt(i);
      if (c == '(') {
        depth++;
        i++;
      } else if (c == ')') {
        depth--;
        i++;
      } else if (depth >= 1 && isWordBoundary(upperNoLiterals, i)) {
        // 단어 경계에서 DML 키워드 매칭 — 부분 매치 방지(예: 식별자 INSERTED_AT은 INSERT가 아님)
        String kw = matchDmlKeyword(upperNoLiterals, i);
        if (kw != null) {
          return kw;
        }
        i++;
      } else {
        i++;
      }
    }
    return null;
  }

  /** 위치 i가 단어 경계인지(앞 문자가 영문/숫자/밑줄이 아닌지) 검사한다. i==0이면 단어 경계로 간주한다. */
  private static boolean isWordBoundary(String s, int i) {
    if (i == 0) return true;
    char prev = s.charAt(i - 1);
    return !(Character.isLetterOrDigit(prev) || prev == '_');
  }

  /**
   * 위치 i에서 시작하는 DML 키워드를 매칭한다. 매칭은 단어 경계 조건(다음 문자가 영문/숫자/밑줄이 아님)도 만족해야 한다.
   *
   * @return 매칭된 키워드 또는 null
   */
  private static String matchDmlKeyword(String s, int i) {
    String[] kws = {"DELETE", "UPDATE", "INSERT", "MERGE", "TRUNCATE"};
    for (String kw : kws) {
      if (s.startsWith(kw, i)) {
        int end = i + kw.length();
        if (end >= s.length()) return kw;
        char next = s.charAt(end);
        if (!(Character.isLetterOrDigit(next) || next == '_')) {
          return kw;
        }
      }
    }
    return null;
  }

  /**
   * WITH 절이 있는 SQL에서 CTE 정의를 건너뛴 후 최상위 레벨 본문 키워드를 반환한다.
   *
   * <p>괄호 깊이를 추적하여 depth==0인 최상위 레벨에서 처음 나타나는 DML/SELECT 키워드를 반환한다. 문자열 리터럴은 호출 전에 이미 제거되어 있어야 한다.
   *
   * @param upperNoLiterals 대문자 변환 + 문자열 리터럴 제거된 SQL
   * @return "SELECT", "INSERT", "UPDATE", "DELETE" 중 하나 (기본값: "SELECT")
   */
  static String detectCteBodyKeyword(String upperNoLiterals) {
    int depth = 0;
    int len = upperNoLiterals.length();
    int i = 0;

    while (i < len) {
      char c = upperNoLiterals.charAt(i);
      if (c == '(') {
        depth++;
        i++;
      } else if (c == ')') {
        depth--;
        i++;
      } else if (depth == 0) {
        // 최상위 레벨에서 키워드를 확인한다
        if (upperNoLiterals.startsWith("SELECT", i)) return "SELECT";
        if (upperNoLiterals.startsWith("INSERT", i)) return "INSERT";
        if (upperNoLiterals.startsWith("UPDATE", i)) return "UPDATE";
        if (upperNoLiterals.startsWith("DELETE", i)) return "DELETE";
        i++;
      } else {
        i++;
      }
    }
    // 최상위 레벨 키워드를 찾지 못한 경우 SELECT로 간주 (안전한 기본값)
    return "SELECT";
  }

  /**
   * SQL 문자열에서 단일 따옴표로 감싸인 문자열 리터럴을 제거하고 빈 따옴표쌍으로 교체한다.
   *
   * <p>이중 따옴표 이스케이프(`''`)는 리터럴 내부로 처리하여 올바르게 건너뛴다. 제거 목적이므로 리터럴 내용은 보존하지 않는다.
   *
   * @param sql 원본 SQL 문자열
   * @return 문자열 리터럴이 제거된 SQL
   */
  static String stripStringLiterals(String sql) {
    StringBuilder sb = new StringBuilder(sql.length());
    int i = 0;
    int len = sql.length();
    while (i < len) {
      char c = sql.charAt(i);
      if (c == '\'') {
        // 문자열 리터럴 시작 — 닫는 따옴표까지 건너뛴다 ('' 이스케이프 처리 포함)
        sb.append('\'');
        i++;
        while (i < len) {
          char lc = sql.charAt(i);
          if (lc == '\'') {
            // 이중 따옴표('')는 리터럴 내부 이스케이프 → 계속 건너뜀
            if (i + 1 < len && sql.charAt(i + 1) == '\'') {
              i += 2;
            } else {
              // 닫는 따옴표 — 리터럴 종료
              i++;
              break;
            }
          } else {
            i++;
          }
        }
        sb.append('\'');
      } else {
        sb.append(c);
        i++;
      }
    }
    return sb.toString();
  }

  /**
   * Remove trailing semicolon from a stripped SQL string.
   *
   * @param stripped SQL string (already comment-stripped)
   * @return SQL without trailing semicolon
   */
  public static String removeTrailingSemicolon(String stripped) {
    return stripped.replaceAll(";\\s*$", "");
  }
}
