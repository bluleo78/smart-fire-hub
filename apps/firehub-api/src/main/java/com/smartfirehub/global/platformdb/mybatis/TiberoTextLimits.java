package com.smartfirehub.global.platformdb.mybatis;

/**
 * Tibero 텍스트 열 한도에 맞춰 값을 자르는 공용 도우미(WD-712, WD-728). MyBatis 플랫폼 리포지토리가 오류 메시지·사유 같은 길이를 통제할 수 없는
 * 텍스트를 쓸 때 바인딩 직전에 쓴다.
 *
 * <p>왜: PG 의 {@code TEXT} 는 길이 제한이 없지만 Tibero 기준선은 같은 열을 {@code VARCHAR2(65532)} — <b>바이트</b> 한도 —
 * 로 옮겼다. 잡이 넘기는 예외 메시지(외부 응답 본문이 섞일 수 있다)를 그대로 넣으면 INSERT/UPDATE 가 실패해 기록 자체가 사라진다.
 *
 * <p>어디에 적용되나: MyBatis 리포지토리 빈은 Tibero 설치본({@code firehub.platform-db.type=tibero})과 MyBatis 배선을 켠
 * 시험에서만 등록된다({@link PlatformMyBatisRepository}). PG 운영은 jOOQ 구현을 쓰며 자르지 않는다 — 계약은 「원문의 접두어」만 요구하므로
 * 두 결과가 모두 계약을 지킨다. (PG 위에서 도는 MyBatis 계약 시험은 같은 길이로 잘린 값을 본다.)
 *
 * <p>글자→바이트 전제: 한도는 Java char(UTF-16 단위) 수다. Tibero 는 값을 UTF-8 바이트로 센다(LENGTHB 실측, 실서버 시험 {@code
 * TiberoMyBatisNotificationOutboxRepositoryContractTest.databaseCharset_countsUtf8Bytes}) — BMP
 * 글자(char 하나)는 최대 3바이트, 보충 문자(char 두 개)는 4바이트다 — char 하나당 최대 3바이트. 그래서 {@link #MAX_TRUNCATE_CHARS}
 * 글자 이하면 어떤 값이든 {@code VARCHAR2(65532)} 에 들어간다.
 */
public final class TiberoTextLimits {

  /** Tibero 기준선이 PG {@code text} 를 옮긴 {@code VARCHAR2(65532)} 의 바이트 한도. */
  public static final int VARCHAR2_TEXT_MAX_BYTES = 65_532;

  /**
   * {@link #truncate} 가 받는 최대 글자 수 — char 하나당 최대 3바이트라 이 글자 수까지는 {@link #VARCHAR2_TEXT_MAX_BYTES} 를
   * 넘지 않는다. 더 큰 한도는 바이트 한도를 보장할 수 없으므로 호출 시점에 바로 거부한다.
   */
  public static final int MAX_TRUNCATE_CHARS = VARCHAR2_TEXT_MAX_BYTES / 3;

  /** 오류 메시지 최대 글자 수 — 최악 48000 바이트로 {@link #MAX_TRUNCATE_CHARS} 안이다. */
  public static final int MAX_ERROR_CHARS = 16_000;

  private TiberoTextLimits() {}

  /** 메시지를 {@link #MAX_ERROR_CHARS} 글자 이하의 앞부분으로 자른다({@link #truncate(String, int)} 와 같은 규칙). */
  public static String truncateErrorMessage(String message) {
    return truncate(message, MAX_ERROR_CHARS);
  }

  /**
   * 값을 {@code maxChars} 글자(char) 이하의 앞부분으로 자른다. null·한도 이하는 그대로(같은 인스턴스) 돌려준다.
   *
   * <p>왜 단순 {@code substring(0, maxChars)} 가 아닌가: 자른 끝이 보충 문자(이모지 등)의 상위 서로게이트면 짝 잃은 서로게이트가 남아
   * UTF-8 인코딩에서 '?' 로 깨지거나 드라이버가 거부한다. 그래서 끝이 상위 서로게이트면 한 글자 덜 자른다.
   *
   * @param maxChars 1 이상 {@link #MAX_TRUNCATE_CHARS} 이하인 최대 글자 수(벗어나면 {@link
   *     IllegalArgumentException})
   */
  public static String truncate(String value, int maxChars) {
    if (maxChars < 1 || maxChars > MAX_TRUNCATE_CHARS) {
      throw new IllegalArgumentException(
          "maxChars 는 1 이상 " + MAX_TRUNCATE_CHARS + " 이하여야 한다: " + maxChars);
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
