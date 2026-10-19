package com.smartfirehub.global.util;

import com.smartfirehub.dataset.exception.SqlQueryException;
import java.util.Locale;
import java.util.Set;
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

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** 끝 세미콜론(뒤 공백 포함). */
  private static final Pattern TRAILING_SEMICOLON = Pattern.compile(";\\s*$");

  /** 메시지에 싣는 사용자 입력 조각의 최대 길이(코드 포인트) — 거대한 입력이 오류 표시를 덮지 않게 한다. */
  private static final int MAX_TOKEN_DISPLAY_LENGTH = 30;

  /** 데이터 수정 CTE 안에 올 수 있는 데이터 수정 키워드(이슈 #227). */
  private static final String[] CTE_INNER_DML_KEYWORDS = {
    "DELETE", "UPDATE", "INSERT", "MERGE", "TRUNCATE"
  };

  /** WITH 절 뒤 본문이 될 수 있는 문 키워드. PostgreSQL 15+ 는 {@code WITH ... MERGE} 도 받는다. */
  private static final String[] CTE_BODY_KEYWORDS = {
    "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE"
  };

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
    String withoutTrailingSemicolon = removeTrailingSemicolon(stripped);
    if (withoutTrailingSemicolon.contains(";")) {
      // 허용 문장 목록은 싣지 않는다 — 읽기 전용 애널리틱스와 DML 을 허용하는 데이터셋 SQL 탭이 이 검사를 공유한다.
      throw new SqlQueryException("SQL 문장은 한 번에 하나만 실행할 수 있습니다. 세미콜론(;)으로 나뉜 문장을 하나씩 실행하세요.");
    }

    // 판정은 PostgreSQL 어휘 규칙으로 뽑은 첫 키워드로 한다(WD-91) — 공백으로만 자르면 SELECT*FROM 처럼
    // 키워드 바로 뒤에 구두점이 붙은 정상 문장을 거부한다.
    String firstWord = firstKeyword(stripped);
    if (firstWord == null || !ALLOWED_KEYWORDS.contains(firstWord)) {
      throw new SqlQueryException(rejectedStartMessage(withoutTrailingSemicolon, stripped));
    }

    return stripped;
  }

  /**
   * SQL 첫 키워드를 ASCII 대문자로 돌려준다. 맨 앞 토큰({@link SqlLexicalMask#leadingKeyword})이 없거나 비ASCII 문자가 섞여
   * 있으면 null 이다.
   *
   * <p>왜 비ASCII 를 거르고 ASCII 만 대문자로 바꾸나(WD-91 리뷰): PostgreSQL 키워드는 ASCII 뿐이라 {@code
   * ſelect}(U+017F)·{@code ınsert}(U+0131)는 키워드가 아니라 식별자다. 그런데 {@link String#toUpperCase()} 는 이들을
   * {@code SELECT}·{@code INSERT} 로 접고, 터키어 로캘에선 {@code with} 를 {@code WİTH} 로 바꿔 판정이 PostgreSQL 과
   * 갈린다. ASCII 토큰은 {@link Locale#ROOT} 대문자화가 ASCII 대문자화와 같다.
   */
  static String firstKeyword(String sql) {
    String token = SqlLexicalMask.leadingKeyword(sql);
    if (token == null || !SqlLexicalMask.isAscii(token)) {
      return null;
    }
    return token.toUpperCase(Locale.ROOT);
  }

  /**
   * 첫 단어 검사에서 거부된 SQL 의 원인별 메시지를 고른다(판정은 호출부가 끝냈다). 빈 SQL · 허용 키워드 뒤 특수 공백 · 금지 명령어 · 괄호/기호 시작 · 그
   * 밖(오타)으로 나눠, 사용자가 문법 오류인지 금지된 문장인지 알 수 있게 한다. 토큰은 판정과 같은 {@link SqlLexicalMask#leadingKeyword} 로
   * 자른다 — 판정과 메시지가 다른 토크나이저를 쓰면 안내가 실제 거부 원인과 어긋난다.
   */
  private static String rejectedStartMessage(String withoutTrailingSemicolon, String stripped) {
    if (withoutTrailingSemicolon.isBlank()) {
      return "실행할 SQL 이 없습니다.";
    }
    String token = SqlLexicalMask.leadingKeyword(stripped);
    if (token == null) {
      // (SELECT 1) 은 PG 에선 맞는 문장이라 "문법 오류"라 하지 않는다. 기호·숫자 시작은 토큰 대신 시작 규칙만 안내한다.
      return stripped.startsWith("(")
          ? "괄호로 시작하는 문장은 실행할 수 없습니다. SELECT 나 WITH 로 시작하게 바꿔 주세요."
          : "SQL 문장은 SELECT·WITH 같은 SQL 명령어로 시작해야 합니다.";
    }
    String keywordBeforeSpace = allowedKeywordBeforeSpecialSpace(token);
    if (keywordBeforeSpace != null) {
      // NBSP 등은 PostgreSQL 에서 식별자 글자라 "SELECT"+NBSP+"*" 는 SELECT 가 아니다. 눈에 안 보이므로 오타 안내 대신 원인을 짚는다.
      return "'" + keywordBeforeSpace + "' 뒤에 보이지 않는 특수 공백(NBSP 등)이 있습니다. 일반 공백으로 바꿔 다시 실행하세요.";
    }
    if (SqlLexicalMask.isAscii(token)) {
      String upper = token.toUpperCase(Locale.ROOT);
      if (KNOWN_DISALLOWED_COMMANDS.contains(upper)) {
        return upper + " 문은 실행할 수 없습니다.";
      }
    }
    // 허용 키워드 뒤에 식별자 글자가 이어진 경우(SELECT$x·SELECT_1)는 PostgreSQL 에서도 식별자라 오타로 안내한다.
    return "SQL 문법 오류: '" + displayToken(token) + "' 은(는) SQL 명령어가 아닙니다. 철자를 확인하세요.";
  }

  /**
   * 토큰이 "허용 키워드 + 눈에 안 보이는 비ASCII 공백류"로 시작하면 그 키워드(원문 대소문자)를, 아니면 null 을 돌려준다. 메시지 전용이다 — 판정은
   * {@link #firstKeyword} 가 이미 거부했다.
   */
  private static String allowedKeywordBeforeSpecialSpace(String token) {
    int k = 0;
    while (k < token.length() && isAsciiLetter(token.charAt(k))) {
      k++;
    }
    if (k == 0
        || k >= token.length()
        || !ALLOWED_KEYWORDS.contains(token.substring(0, k).toUpperCase(Locale.ROOT))) {
      return null;
    }
    return isSpecialSpace(token.charAt(k)) ? token.substring(0, k) : null;
  }

  /** 비ASCII 공백류(NBSP·전각 공백 등)와 폭 없는 공백(ZWSP·BOM 등). PostgreSQL 은 이들을 공백이 아닌 식별자 글자로 읽는다. */
  private static boolean isSpecialSpace(char c) {
    return c >= 0x80
        && (Character.isSpaceChar(c)
            || Character.isWhitespace(c)
            || c == '\u200B'
            || c == '\u200C'
            || c == '\u200D'
            || c == '\u2060'
            || c == '\uFEFF');
  }

  private static boolean isAsciiLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  /**
   * {@link SqlLexicalMask#mask} 로 주석·리터럴·따옴표 식별자·달러 인용을 가리고 ASCII 글자만 대문자로 바꾼다(길이·비ASCII 문자 보존).
   * 키워드 탐색 전용 — {@link String#toUpperCase} 는 {@code ſelect} 를 {@code SELECT} 로 접어, PostgreSQL 에선
   * 식별자인 CTE 이름을 본문 SELECT 로 오인하게 만든다(WD-91 리뷰).
   */
  private static String maskedUpper(String sql) {
    char[] out = SqlLexicalMask.mask(sql).toCharArray();
    for (int k = 0; k < out.length; k++) {
      if (out[k] >= 'a' && out[k] <= 'z') {
        out[k] = (char) (out[k] - ('a' - 'A'));
      }
    }
    return new String(out);
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
    String masked = removeTrailingSemicolon(maskedUpper(sql).strip());
    if (!"WITH".equals(firstKeyword(masked))) {
      return null;
    }
    return findKeywordAtDepth(masked, false, CTE_INNER_DML_KEYWORDS);
  }

  /**
   * Detect the main DML verb of a (possibly CTE-prefixed) SQL string. Assumes the SQL has already
   * been stripped by {@link #stripAndValidate}.
   *
   * <p>WITH 문은 {@link SqlLexicalMask#mask} 로 주석·문자열·큰따옴표 식별자·달러 인용을 가린 뒤 괄호 깊이를 추적해, CTE 안의 데이터 수정
   * 키워드와 최상위 본문 키워드를 <b>토큰 단위</b>로 찾는다. 본문 키워드를 못 찾으면 SELECT 로 보지 않는다(fail-closed).
   *
   * <p><b>executor {@code query_executor._detect_query_type} 와 짝이다.</b> 공용 픽스처 {@code
   * fixtures/query-type-vectors.json} 으로 같은 분류를 검증한다.
   *
   * @param stripped SQL string without comments
   * @return 첫 키워드(SELECT·INSERT·UPDATE·DELETE 등). WITH 문이면 CTE 안 DML 또는 본문 키워드. 키워드로 시작하지 않거나 본문
   *     키워드를 못 찾으면 "UNKNOWN".
   */
  public static String detectQueryType(String stripped) {
    String withoutTrailingSemicolon = removeTrailingSemicolon(stripped);
    // 첫 키워드는 stripAndValidate 와 같은 어휘 규칙으로 뽑는다(WD-91) — SELECT*FROM 은 SELECT, DELETE*FROM 은 DELETE.
    // 키워드로 시작하지 않으면(stripAndValidate 를 거친 입력이면 오지 않는다) SELECT 로 보지 않는다.
    String firstWord = firstKeyword(withoutTrailingSemicolon);
    if (firstWord == null) {
      return "UNKNOWN";
    }

    if ("WITH".equals(firstWord)) {
      // 주석·문자열·큰따옴표 식별자·달러 인용을 가린다 — 그 안의 단어('needs update', "select", $$delete$$)는
      // PostgreSQL 이 키워드로 읽지 않는다. 대문자화는 ASCII 만 한다(maskedUpper 참고).
      String masked = maskedUpper(withoutTrailingSemicolon);

      // 보안 (이슈 #227): 데이터 수정 CTE(`WITH d AS (DELETE/UPDATE/INSERT/MERGE/TRUNCATE ... RETURNING)
      // SELECT ...`)는 본문이 SELECT여도 PostgreSQL이 CTE 내부 DML을 실제로 실행한다. 따라서 readOnly 모드에서
      // SELECT로 분류하면 우회가 발생한다. CTE 정의 내부에서 DML 키워드가 발견되면 해당 DML 타입을 반환한다.
      String cteDml = findKeywordAtDepth(masked, false, CTE_INNER_DML_KEYWORDS);
      if (cteDml != null) {
        return cteDml;
      }

      // 최상위 본문 키워드. 못 찾으면(괄호로 감싼 본문·VALUES·TABLE 등) SELECT 로 단정하지 않는다(fail-closed).
      String body = findKeywordAtDepth(masked, true, CTE_BODY_KEYWORDS);
      return body == null ? "UNKNOWN" : body;
    }
    return firstWord;
  }

  /**
   * 괄호 깊이를 추적하며 {@code keywords} 중 처음 나오는 키워드 <b>토큰</b>(앞뒤가 식별자 글자가 아님 — {@code SELECTED}·{@code
   * INSERTED_AT} 은 키워드가 아니다)을 돌려준다. 없으면 null.
   *
   * <ul>
   *   <li>{@code topLevel=false}: 깊이 1 이상(CTE 정의 안)의 데이터 수정 키워드 — data-modifying CTE({@code WITH d
   *       AS (DELETE … RETURNING *) SELECT …})는 본문이 SELECT 여도 PostgreSQL 이 안의 DML 을 실행하므로 DML 로
   *       분류해야 한다(이슈 #227).
   *   <li>{@code topLevel=true}: 깊이 0 의 본문 키워드. 예전엔 경계 없이 {@code startsWith} 로 봐서 CTE 이름 {@code
   *       selected} 를 본문 SELECT 로 오인했다(WD-91 리뷰). CTE 이름이 비예약 키워드({@code update} 등)면 DML 로 보는데, 거부
   *       쪽 오판이라 안전하다.
   * </ul>
   *
   * @param maskedUpper {@link #maskedUpper} 를 거친 SQL
   */
  private static String findKeywordAtDepth(
      String maskedUpper, boolean topLevel, String... keywords) {
    int depth = 0;
    for (int i = 0; i < maskedUpper.length(); i++) {
      char c = maskedUpper.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (topLevel ? depth == 0 : depth >= 1) {
        String kw = SqlLexicalMask.keywordAt(maskedUpper, i, keywords);
        if (kw != null) {
          return kw;
        }
      }
    }
    return null;
  }

  /**
   * Remove trailing semicolon from a stripped SQL string.
   *
   * @param stripped SQL string (already comment-stripped)
   * @return SQL without trailing semicolon
   */
  public static String removeTrailingSemicolon(String stripped) {
    return TRAILING_SEMICOLON.matcher(stripped).replaceAll("");
  }
}
