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
   * @param stripped SQL string without comments
   * @return "SELECT", "INSERT", "UPDATE", or "DELETE"
   */
  public static String detectQueryType(String stripped) {
    String withoutTrailingSemicolon = stripped.replaceAll(";\\s*$", "");
    String firstWord = withoutTrailingSemicolon.split("\\s+")[0].toUpperCase();

    if ("WITH".equals(firstWord)) {
      String upper = withoutTrailingSemicolon.toUpperCase();
      if (upper.matches("(?s).*\\bINSERT\\b.*")) return "INSERT";
      if (upper.matches("(?s).*\\bUPDATE\\b.*")) return "UPDATE";
      if (upper.matches("(?s).*\\bDELETE\\b.*")) return "DELETE";
      return "SELECT";
    }
    return firstWord;
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
