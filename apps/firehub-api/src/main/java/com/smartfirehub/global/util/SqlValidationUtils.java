package com.smartfirehub.global.util;

import com.smartfirehub.dataset.exception.SqlQueryException;
import java.util.Set;

/**
 * Shared SQL validation utilities used by DataTableQueryService and AnalyticsQueryExecutionService.
 *
 * <p>Validates user-supplied SQL strings before execution: strips comments, rejects multi-statement
 * input, and enforces a keyword whitelist.
 */
public final class SqlValidationUtils {

  private static final Set<String> ALLOWED_KEYWORDS =
      Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "WITH");

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
      throw new SqlQueryException("Multiple statements are not allowed.");
    }

    String firstWord = stripped.split("\\s+")[0].toUpperCase();
    if (!ALLOWED_KEYWORDS.contains(firstWord)) {
      throw new SqlQueryException(
          "Only SELECT, INSERT, UPDATE, DELETE, and WITH statements are allowed.");
    }

    return stripped;
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
      return detectCteBodyKeyword(noLiterals);
    }
    return firstWord;
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
