package com.smartfirehub.global.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 서비스 간 {@code Authorization: Internal <token>} 헤더 검증 공용 유틸.
 *
 * <p>executor 저장소 기술자 엔드포인트와 Slack inbound 엔드포인트가 같은 규칙(접두어 확인 + 상수 시간 비교)을 각자 구현하던 것을 한 곳으로 모았다.
 * 검증 규칙이 갈라지지 않게 하려는 목적이다.
 */
public final class InternalTokens {

  private static final String PREFIX = "Internal ";

  private InternalTokens() {}

  /**
   * 헤더가 {@code "Internal " + expectedToken} 인지 상수 시간으로 비교한다.
   *
   * @param authorizationHeader Authorization 헤더 원문(없으면 null)
   * @param expectedToken 설정된 기대 토큰. null·공백이면 어떤 요청도 인증할 수 없으므로 false
   */
  public static boolean matches(String authorizationHeader, String expectedToken) {
    if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
      return false;
    }
    if (expectedToken == null || expectedToken.isBlank()) {
      return false;
    }
    String provided = authorizationHeader.substring(PREFIX.length());
    // String.equals 는 첫 불일치에서 반환해 응답 시간으로 토큰을 추측할 수 있다 → MessageDigest.isEqual 로 상수 시간 비교.
    return MessageDigest.isEqual(
        provided.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8));
  }
}
