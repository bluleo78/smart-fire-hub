package com.smartfirehub.global.platformdb.tibero;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Tibero 인스턴스 전역 이름·id 의 해시 — 잠금 id(WD-493), 아웃박스 알림 이름(WD-493), 테넌트 컨텍스트 이름(WD-492)이 같은 계산을 쓴다.
 *
 * <p>왜 한곳에: 세 값 모두 저장되거나 인스턴스 사이에서 같아야 하는 식별자다. 계산이 흩어져 하나만 바뀌면 같은 스키마의 인스턴스끼리 다른 이름을 쓰게 된다. 결과는
 * 바이트 단위로 고정이다 — 바꾸면 이미 만든 컨텍스트·알림 이름과 어긋난다.
 *
 * <p>왜 SHA-256 인가: {@code String.hashCode} 는 32비트라 잠금 id 의 문자열 공간(약 9억 칸)을 고르게 채우지 못하고, JVM 이 달라도
 * 같은 값이 나와야 하는 식별자의 원천으로는 명세가 고정된 알고리즘이 안전하다. 암호학적 강도가 필요해서가 아니라 분포·결정성 때문이다.
 */
public final class TiberoNameHash {

  private TiberoNameHash() {}

  /** 문자열의 UTF-8 SHA-256 앞 8바이트를 부호 있는 long 으로. */
  public static long hash64(String text) {
    return ByteBuffer.wrap(sha256(text.getBytes(UTF_8)), 0, 8).getLong();
  }

  /** {@link #hash64} 의 위 32비트(= SHA-256 앞 4바이트)를 대문자 16진 8자로 — 알림·컨텍스트 이름의 꼬리. */
  public static String hex8(String text) {
    return String.format("%08X", (int) (hash64(text) >>> 32));
  }

  /** SHA-256 다이제스트. {@link MessageDigest} 는 스레드 안전하지 않아 매번 새로 얻는다(호출 빈도에서 비용은 무시할 만하다). */
  static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      // 모든 JVM 이 SHA-256 을 갖춰야 한다(Java SE 명세) — 여기 오면 환경 결함이다.
      throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
    }
  }
}
