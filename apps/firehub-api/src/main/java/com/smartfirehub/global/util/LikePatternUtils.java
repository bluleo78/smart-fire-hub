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

  /**
   * 설정 키 프리픽스용 LIKE 패턴: {@code escaped_prefix + ".%"}.
   *
   * <p><b>프리픽스에 마침표를 붙이지 말 것</b> — {@code "ai"}(O), {@code "ai."}(X). 이 메서드가
   * 스스로 {@code "."} 를 붙이므로 {@code "ai."} 를 넘기면 {@code "ai..%"} 가 되어 <b>아무 행도
   * 매칭하지 않고 예외도 없이 빈 결과</b>가 나온다. 실제로 {@code ProactiveJobAsyncRunner} 가 이
   * 함정에 빠져 저장된 {@code ai.agent_type} 을 한 번도 읽지 못했다(P7-b Task 8 에서 수정).
   *
   * <p>이 자리가 헬퍼인 이유: 같은 패턴을 {@code SettingsRepository}·{@code TenantSettingsRepository}
   * 가 각자 손으로 만들면서, 위 함정 설명이 javadoc 세 곳에 복사돼 있었다. 프리픽스 조회를 새로
   * 추가하는 네 번째 호출자는 그 설명을 하나도 물려받지 못한다 — 프로즈 사본이 셋이라는 것 자체가
   * 헬퍼가 없다는 신호다.
   *
   * @param prefix 마침표 없는 키 프리픽스 (예: {@code "ai"}, {@code "smtp"}, {@code "embedding"})
   * @return {@code .like(pattern, '\\')} 에 넘길 패턴
   */
  public static String settingKeyPrefixPattern(String prefix) {
    return escape(prefix) + ".%";
  }
}
