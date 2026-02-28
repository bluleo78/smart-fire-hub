package com.smartfirehub.global.util;

/**
 * Utility for safely escaping user input used in SQL LIKE patterns. Escapes the LIKE wildcard
 * characters %, _, and the escape character \ itself.
 *
 * <p>Usage with jOOQ:
 *
 * <pre>
 *   String pattern = LikePatternUtils.containsPattern(search);
 *   field.likeIgnoreCase(pattern, '\\')
 * </pre>
 */
public final class LikePatternUtils {

  private static final char ESCAPE_CHAR = '\\';

  private LikePatternUtils() {}

  /**
   * Escape LIKE special characters (%, _, \) in the given input string.
   *
   * @param input the raw user input to escape
   * @return the escaped string safe for use in LIKE patterns
   */
  public static String escape(String input) {
    if (input == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(input.length() + 8);
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (c == '%' || c == '_' || c == ESCAPE_CHAR) {
        sb.append(ESCAPE_CHAR);
      }
      sb.append(c);
    }
    return sb.toString();
  }

  /**
   * Build a "contains" LIKE pattern: %escaped_input%.
   *
   * @param input the raw user search input
   * @return pattern string for use with {@code .likeIgnoreCase(pattern, '\\')}
   */
  public static String containsPattern(String input) {
    return "%" + escape(input) + "%";
  }
}
