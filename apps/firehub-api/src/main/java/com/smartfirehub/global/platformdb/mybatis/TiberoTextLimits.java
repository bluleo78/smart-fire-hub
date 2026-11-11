package com.smartfirehub.global.platformdb.mybatis;

/**
 * Tibero 텍스트 열 한도에 맞춰 값을 자르는 공용 도우미(WD-712). MyBatis 플랫폼 리포지토리가 오류 메시지 같은 무제한 텍스트를 쓸 때 쓴다.
 *
 * <p>왜: PG 의 {@code TEXT} 는 길이 제한이 없지만 Tibero 기준선은 같은 열을 {@code VARCHAR2(65532)} — <b>바이트</b> 한도 —
 * 로 옮겼다. 잡이 넘기는 예외 메시지(외부 응답 본문이 섞일 수 있다)를 그대로 넣으면 UPDATE 가 실패해 실패·백오프 기록 자체가 사라진다. MyBatis 구현은 PG
 * 에서도 같은 길이로 잘라 두 DB 의 결과를 맞춘다(jOOQ 판은 자르지 않는다 — 계약은 「원문의 접두어」만 요구한다).
 */
public final class TiberoTextLimits {

  /**
   * 오류 메시지 최대 글자(char) 수. UTF-8 에서 char 하나는 최대 3바이트(보충 문자는 char 두 개에 4바이트)라 16000 글자면 48000 바이트 이하로
   * {@code VARCHAR2(65532)} 바이트 한도를 넘지 않는다.
   */
  public static final int MAX_ERROR_CHARS = 16_000;

  private TiberoTextLimits() {}

  /**
   * 메시지를 {@link #MAX_ERROR_CHARS} 글자 이하의 앞부분으로 자른다. null·한도 이하는 그대로(같은 인스턴스) 돌려준다. 보충 문자의 서로게이트 짝을
   * 반으로 가르지 않는다({@link #truncate(String, int)} 에 맡긴다).
   */
  public static String truncateErrorMessage(String message) {
    return truncate(message, MAX_ERROR_CHARS);
  }

  /**
   * 값을 {@code maxChars} 글자(char) 이하의 앞부분으로 자른다. null·한도 이하는 그대로(같은 인스턴스) 돌려준다.
   *
   * <p>왜 단순 {@code substring(0, maxChars)} 가 아닌가: 자른 끝이 보충 문자(이모지 등)의 상위 서로게이트면 짝 잃은 서로게이트가 남아
   * UTF-8 인코딩에서 '?' 로 깨지거나 드라이버가 거부한다. 그래서 끝이 상위 서로게이트면 한 글자 덜 자른다.
   *
   * @param maxChars 1 이상인 최대 글자 수
   */
  public static String truncate(String value, int maxChars) {
    if (maxChars < 1) {
      throw new IllegalArgumentException("maxChars 는 1 이상이어야 한다: " + maxChars);
    }
    if (value == null || value.length() <= maxChars) {
      return value;
    }
    int end = maxChars;
    if (Character.isHighSurrogate(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(0, end);
  }
}
