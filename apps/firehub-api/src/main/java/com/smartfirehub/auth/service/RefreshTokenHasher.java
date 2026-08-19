package com.smartfirehub.auth.service;

import com.smartfirehub.global.exception.CryptoException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 리프레시 토큰의 저장용 해시(SHA-256 hex).
 *
 * <p>왜 별도 클래스인가: {@code refresh_token} 테이블은 전역(테넌트 컬럼·RLS 없음)이라 운영자 평면도
 * 같은 회전·재사용 탐지 기계를 그대로 쓴다. 그러려면 두 평면이 <b>같은 해시</b>를 만들어야 한다.
 * 각자 복사해 두면 한쪽이 알고리즘·인코딩을 바꾸는 순간 저장된 해시가 어긋나 전 사용자의 갱신이
 * 조용히 실패한다 — 그 종류의 중복은 주석으로 동기화할 수 없다.
 */
public final class RefreshTokenHasher {

  private RefreshTokenHasher() {}

  /** 원문 토큰 → 저장용 해시. DB 에는 원문을 넣지 않는다. */
  public static String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new CryptoException("SHA-256 not available", e);
    }
  }
}
