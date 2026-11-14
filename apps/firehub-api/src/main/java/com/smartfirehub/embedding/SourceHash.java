package com.smartfirehub.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 색인 원문의 해시(ADR 0002 결정 4 ③, WD-720). 원문이 바뀌었는지를 플랫폼 DB 에서 판정하는 데 쓴다.
 *
 * <p>계산은 여기 한 곳에서만 한다. SQL 로 계산하면 DB 마다 함수가 달라(Tibero 에는 PG 의 {@code sha256} 이 없다) 같은 원문이 다른 해시가 될
 * 수 있다. 값은 UTF-8 바이트의 SHA-256 소문자 hex(64자)다.
 */
public final class SourceHash {

  private SourceHash() {}

  /**
   * @param text 원문. null 이면 null 을 돌려준다(원문이 없으면 해시도 없다)
   * @return SHA-256 소문자 hex 64자
   */
  public static String of(String text) {
    if (text == null) {
      return null;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // 모든 JVM 이 SHA-256 을 갖춰야 한다(Java SE 명세) — 없으면 실행 환경이 깨진 것이다.
      throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
    }
  }
}
