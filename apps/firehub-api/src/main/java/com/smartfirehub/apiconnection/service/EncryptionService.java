package com.smartfirehub.apiconnection.service;

import com.smartfirehub.global.exception.CryptoException;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EncryptionService {

  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final int GCM_TAG_LENGTH = 128;
  private static final int IV_LENGTH = 12;

  /**
   * 복호화 실패 문구 — 키 변경·형식·Base64 오류 모두 같다(WD-780). 전역 처리기가 500 응답에 싣고, 화면 오류 분류(web error-classifier
   * 의 「decryption failed」)가 이 문구로 AI 자격증명 손상을 알아보므로 바꾸지 않는다. 암호문·형식 단서는 싣지 않는다.
   */
  private static final String DECRYPT_FAILED_MESSAGE = "Decryption failed";

  @Value("${app.encryption.master-key}")
  private String masterKeyBase64;

  private SecretKey secretKey;

  @PostConstruct
  void init() {
    byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64);
    secretKey = new SecretKeySpec(keyBytes, "AES");
  }

  // Package-private constructor for unit tests
  EncryptionService(String masterKeyBase64) {
    byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64);
    this.secretKey = new SecretKeySpec(keyBytes, "AES");
  }

  // No-arg constructor for Spring (Spring uses @PostConstruct to set key)
  EncryptionService() {}

  /**
   * Encrypts plainText using AES-256-GCM with a random 12-byte IV.
   *
   * @return Base64-encoded string in the format {@code iv:ciphertext}
   */
  public String encrypt(String plainText) {
    try {
      byte[] iv = new byte[IV_LENGTH];
      new SecureRandom().nextBytes(iv);

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

      byte[] cipherBytes = cipher.doFinal(plainText.getBytes());

      String ivBase64 = Base64.getEncoder().encodeToString(iv);
      String cipherBase64 = Base64.getEncoder().encodeToString(cipherBytes);
      return ivBase64 + ":" + cipherBase64;
    } catch (Exception e) {
      throw new CryptoException("Encryption failed", e);
    }
  }

  /**
   * Base64 로 된 {@code iv:암호문} 저장값을 복호화한다.
   *
   * <p><b>읽을 수 없는 저장값은 모두 {@link CryptoException} 이다(WD-780).</b> 키가 바뀐 암호문(GCM 태그 불일치)뿐 아니라
   * 「iv:암호문」 형식이 아니거나 Base64 가 깨진 값도 저장 데이터 손상(서버 쪽 문제)이다. 예전에는 뒤의 둘이 {@link
   * IllegalArgumentException} 으로 나가 전역 처리기가 입력 오류(400)로 답하고 영문 내부 문구(「Invalid encrypted format …」)가
   * 응답에 실릴 수 있었다. 그래서 여기서 한 번에 감싸 호출처(API 연결·AI 자격증명·알림 채널·외부 저장소 등)마다 따로 바꾸지 않게 한다. 문구에는 암호문을 싣지
   * 않고, 원래 예외는 원인(cause)으로만 남긴다(Base64 오류 문구에 암호문 글자가 실릴 수 있다). 그 밖의 런타임 예외(null 저장값의 NPE 등)는 지금처럼
   * 그대로 나간다.
   *
   * @return 복호화한 평문
   * @throws CryptoException 형식·Base64 오류 또는 복호화 실패
   */
  public String decrypt(String encryptedText) {
    try {
      String[] parts = encryptedText.split(":", 2);
      if (parts.length != 2) {
        // 「iv:암호문」 구분자가 없다 — 암호문·형식 단서는 문구에 싣지 않는다.
        throw new CryptoException(DECRYPT_FAILED_MESSAGE);
      }

      byte[] iv;
      byte[] cipherBytes;
      try {
        iv = Base64.getDecoder().decode(parts[0]);
        cipherBytes = Base64.getDecoder().decode(parts[1]);
      } catch (IllegalArgumentException e) {
        // Base64 가 깨진 저장값(WD-780) — 원래 문구(잘못된 글자가 실린다)는 원인으로만 남긴다. Base64 해석만 감싼다 — 암호기 설정의
        // IllegalArgumentException(코드·설정 오류)까지 「저장값 손상」으로 보이면 진짜 원인이 가려진다.
        throw new CryptoException(DECRYPT_FAILED_MESSAGE, e);
      }

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

      byte[] plainBytes = cipher.doFinal(cipherBytes);
      return new String(plainBytes);
    } catch (RuntimeException e) {
      // CryptoException(형식·Base64 오류)도 여기서 그대로 나간다.
      throw e;
    } catch (Exception e) {
      throw new CryptoException(DECRYPT_FAILED_MESSAGE, e);
    }
  }

  /**
   * Returns {@code ****} followed by the last 4 characters of value, or {@code ****} if value has
   * fewer than 4 characters.
   *
   * <p>형식은 {@link AuthQueryMasker#maskSecret} 한 곳에서 정한다 — 「테스트」 결과 URL 의 쿼리 키 가리기(WD-177)가 같은 형식을
   * 써야 화면마다 가린 모양이 달라지지 않는다.
   */
  public String maskValue(String value) {
    return AuthQueryMasker.maskSecret(value);
  }
}
