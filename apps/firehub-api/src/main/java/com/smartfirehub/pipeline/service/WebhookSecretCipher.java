package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.exception.CryptoException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 웹훅 트리거 시크릿(HMAC 서명 키)을 DB 에 저장할 때 쓰는 암호화·복호화(WD-220).
 *
 * <p>예전에는 {@code WEBHOOK_SECRET_KEY} 가 없으면 소스에 박힌 기본 키로 AES-ECB 암호화했다. 저장소의 배포 설정 어디에도 이 키가 없어 사실상
 * 모든 배포가 공개된 기본 키를 썼고, 트리거 조회 응답에 암호문이 그대로 실려 조회 권한만 있는 사용자가 시크릿을 풀어 서명을 위조할 수 있었다.
 *
 * <p>새 암호화는 {@code v2:} 접두어를 붙인 AES-256-GCM(무작위 IV, 인증 태그)이다. 키는 {@code WEBHOOK_SECRET_KEY} 가 있으면
 * 그것에서, 없으면 배포마다 반드시 설정되는 {@code ENCRYPTION_MASTER_KEY}(저장 데이터 암호화용 마스터키)에서 용도 라벨을 붙여 SHA-256 으로
 * 파생한다 — 마스터키 자체와는 다른 키가 되게 한다. 복호화는 접두어로 형식을 가린다. 접두어가 없는 값은 옛 ECB 암호문으로 보고 {@code
 * WEBHOOK_SECRET_KEY}·레거시 기본 키 순으로 시도한다 — 운영에 이미 저장된 시크릿이 깨지지 않게 하기 위함이다.
 *
 * <p>스프링 의존이 없어 단위 테스트로 레거시 호환과 왕복을 바로 검증할 수 있다.
 */
public final class WebhookSecretCipher {

  /** 새 형식(GCM) 암호문의 접두어. 옛 ECB 암호문은 접두어 없는 Base64 라 구분된다. */
  static final String V2_PREFIX = "v2:";

  /**
   * 레거시 기본 키 — 기존 암호문 호환용, 새 암호화에 쓰지 않음. {@code WEBHOOK_SECRET_KEY} 없이 저장된 옛 웹훅 시크릿을 풀 때만 쓴다. 공개된
   * 값이라 이 키로 새로 암호화하면 WD-220 결함이 되살아난다.
   */
  static final String LEGACY_DEFAULT_KEY = "SmartFireHub2026DefaultSecretKey";

  /** 마스터키에서 웹훅 시크릿용 키를 파생할 때 붙이는 용도 라벨(다른 용도의 파생 키와 겹치지 않게). */
  private static final String MASTER_DERIVE_LABEL = "smartfirehub:webhook-secret:v2:";

  private static final String GCM = "AES/GCM/NoPadding";
  private static final int GCM_TAG_BITS = 128;
  private static final int IV_LENGTH = 12;

  /** 새 암호화에 쓰는 키(첫 번째 후보). */
  private final byte[] currentKey;

  /** v2 복호화 후보 키 — 현재 키 먼저, 그다음 다른 원천에서 파생한 키(나중에 WEBHOOK_SECRET_KEY 를 추가해도 기존 v2 값이 풀리게). */
  private final List<byte[]> v2Keys;

  /** 레거시 ECB 복호화 후보 키 — WEBHOOK_SECRET_KEY(설정된 경우) 먼저, 그다음 레거시 기본 키. */
  private final List<byte[]> legacyKeys;

  /**
   * @param webhookSecretKey {@code WEBHOOK_SECRET_KEY} 환경변수 값(없으면 null 또는 빈 문자열)
   * @param encryptionMasterKey {@code app.encryption.master-key} 원문(배포마다 필수)
   */
  public WebhookSecretCipher(String webhookSecretKey, String encryptionMasterKey) {
    boolean hasEnvKey = webhookSecretKey != null && !webhookSecretKey.isEmpty();
    boolean hasMaster = encryptionMasterKey != null && !encryptionMasterKey.isEmpty();
    if (!hasEnvKey && !hasMaster) {
      // 두 원천이 모두 없으면 공개 기본 키로 돌아가는 대신 기동 시점에 실패시킨다.
      throw new IllegalStateException(
          "웹훅 시크릿 암호화 키가 없습니다: WEBHOOK_SECRET_KEY 또는 ENCRYPTION_MASTER_KEY 를 설정하세요");
    }
    List<byte[]> v2 = new ArrayList<>();
    if (hasEnvKey) {
      v2.add(sha256(webhookSecretKey));
    }
    if (hasMaster) {
      v2.add(sha256(MASTER_DERIVE_LABEL + encryptionMasterKey));
    }
    this.v2Keys = List.copyOf(v2);
    this.currentKey = v2.get(0);

    List<byte[]> legacy = new ArrayList<>();
    if (hasEnvKey) {
      legacy.add(legacyKeyBytes(webhookSecretKey));
    }
    legacy.add(legacyKeyBytes(LEGACY_DEFAULT_KEY));
    this.legacyKeys = List.copyOf(legacy);
  }

  /** 평문 시크릿을 새 형식({@code v2:iv:ciphertext}, Base64)으로 암호화한다. */
  public String encrypt(String plaintext) {
    try {
      byte[] iv = new byte[IV_LENGTH];
      new SecureRandom().nextBytes(iv);
      Cipher cipher = Cipher.getInstance(GCM);
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(currentKey, "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, iv));
      byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      Base64.Encoder b64 = Base64.getEncoder();
      return V2_PREFIX + b64.encodeToString(iv) + ":" + b64.encodeToString(ct);
    } catch (Exception e) {
      throw new CryptoException("Webhook secret encryption failed", e);
    }
  }

  /**
   * 저장된 암호문을 푼다. {@code v2:} 이면 GCM 후보 키를, 아니면 레거시 ECB 후보 키를 차례로 시도한다. 모두 실패하면 {@link
   * CryptoException}.
   */
  public String decrypt(String stored) {
    if (stored == null || stored.isEmpty()) {
      throw new CryptoException("Webhook secret is empty");
    }
    return stored.startsWith(V2_PREFIX) ? decryptV2(stored) : decryptLegacy(stored);
  }

  private String decryptV2(String stored) {
    String[] parts = stored.substring(V2_PREFIX.length()).split(":", 2);
    if (parts.length != 2) {
      throw new CryptoException("Invalid webhook secret format");
    }
    byte[] iv;
    byte[] ct;
    try {
      iv = Base64.getDecoder().decode(parts[0]);
      ct = Base64.getDecoder().decode(parts[1]);
    } catch (IllegalArgumentException e) {
      throw new CryptoException("Invalid webhook secret format", e);
    }
    Exception last = null;
    for (byte[] key : v2Keys) {
      try {
        Cipher cipher = Cipher.getInstance(GCM);
        cipher.init(
            Cipher.DECRYPT_MODE,
            new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(GCM_TAG_BITS, iv));
        // 틀린 키는 GCM 태그 검사에서 예외로 끝나므로 엉뚱한 평문이 나오지 않는다.
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
      } catch (Exception e) {
        last = e;
      }
    }
    throw new CryptoException("Webhook secret decryption failed", last);
  }

  private String decryptLegacy(String stored) {
    byte[] ct;
    try {
      ct = Base64.getDecoder().decode(stored);
    } catch (IllegalArgumentException e) {
      throw new CryptoException("Invalid webhook secret format", e);
    }
    Exception last = null;
    for (byte[] key : legacyKeys) {
      try {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
      } catch (Exception e) {
        last = e;
      }
    }
    throw new CryptoException("Webhook secret decryption failed", last);
  }

  /**
   * 옛 코드의 키 정규화를 바이트 그대로 재현한다(getBytes → 32바이트로 자르거나 0 채움 → 문자열 → 다시 getBytes). 바이트가 하나라도 달라지면 운영의
   * 옛 암호문이 풀리지 않는다.
   */
  static byte[] legacyKeyBytes(String key) {
    byte[] keyBytes = Arrays.copyOf(key.getBytes(StandardCharsets.UTF_8), 32);
    return new String(keyBytes, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] sha256(String input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new CryptoException("SHA-256 hashing failed", e);
    }
  }
}
