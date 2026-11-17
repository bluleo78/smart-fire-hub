package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.CryptoException;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for EncryptionService — no Spring context required.
 *
 * <p>Test key: 32 random bytes, Base64-encoded. "dGVzdC1tYXN0ZXIta2V5LWZvci1lbmNyeXB0aW9uLTMyYg=="
 * decodes to "test-master-key-for-encryption-32b" (34 bytes); we use a proper 32-byte key below.
 */
class EncryptionServiceTest {

  // 32 bytes (256 bits) Base64-encoded — valid AES-256 key
  private static final String TEST_KEY_BASE64 =
      Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes());

  private static final String DIFFERENT_KEY_BASE64 =
      Base64.getEncoder().encodeToString("ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".getBytes());

  private EncryptionService service;

  @BeforeEach
  void setUp() {
    service = new EncryptionService(TEST_KEY_BASE64);
  }

  @Test
  void encrypt_decrypt_roundTrip() {
    String original = "super-secret-api-key-12345";

    String encrypted = service.encrypt(original);
    String decrypted = service.decrypt(encrypted);

    assertThat(decrypted).isEqualTo(original);
  }

  @Test
  void encrypt_producesUniqueOutputs() {
    String text = "same-plain-text";

    String first = service.encrypt(text);
    String second = service.encrypt(text);

    // Random IV ensures different ciphertext each call
    assertThat(first).isNotEqualTo(second);
    // But both must decrypt correctly
    assertThat(service.decrypt(first)).isEqualTo(text);
    assertThat(service.decrypt(second)).isEqualTo(text);
  }

  @Test
  void decrypt_withWrongKey_throwsException() {
    String encrypted = service.encrypt("confidential");

    EncryptionService differentKeyService = new EncryptionService(DIFFERENT_KEY_BASE64);

    assertThatThrownBy(() -> differentKeyService.decrypt(encrypted))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void decrypt_withCorruptedData_throwsException() {
    String garbage = "bm90LWJhc2U2NA==:dGhpc2lzZ2FyYmFnZQ==";

    assertThatThrownBy(() -> service.decrypt(garbage)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void maskValue_longerThan4Chars() {
    assertThat(service.maskValue("my-secret-key")).isEqualTo("****-key");
  }

  @Test
  void maskValue_shorterThan4Chars() {
    assertThat(service.maskValue("abc")).isEqualTo("****");
  }

  @Test
  void maskValue_exactly4Chars() {
    assertThat(service.maskValue("abcd")).isEqualTo("****abcd");
  }

  /**
   * 「iv:암호문」 형식이 아닌 저장값은 저장 데이터 손상이라 {@link CryptoException} 이다(WD-780) — 예전에는 {@link
   * IllegalArgumentException} 이 그대로 나가 전역 처리기가 입력 오류(400)로 답하고 영문 내부 문구가 샐 수 있었다. 문구에 암호문은 싣지 않는다.
   */
  @Test
  void decrypt_withoutSeparator_throwsCryptoExceptionWithoutCiphertext() {
    assertThatThrownBy(() -> service.decrypt("not-a-ciphertext-780"))
        .isExactlyInstanceOf(CryptoException.class)
        .hasMessageNotContaining("not-a-ciphertext-780")
        .hasMessageNotContaining("Invalid encrypted format");
  }

  /** Base64 가 깨진 저장값도 같은 {@link CryptoException} 이다(WD-780). 문구에 암호문 조각은 싣지 않는다. */
  @Test
  void decrypt_withInvalidBase64_throwsCryptoExceptionWithoutCiphertext() {
    assertThatThrownBy(() -> service.decrypt("@@iv780@@:##cipher780##"))
        .isExactlyInstanceOf(CryptoException.class)
        .hasMessageNotContaining("iv780")
        .hasMessageNotContaining("cipher780");
  }

  /** 다른 키로 만든 암호문(GCM 태그 불일치)은 예전처럼 {@link CryptoException} 이다 — 세 경우가 한 타입으로 모인다(WD-780). */
  @Test
  void decrypt_withWrongKey_throwsCryptoException() {
    String encrypted = new EncryptionService(DIFFERENT_KEY_BASE64).encrypt("confidential");

    assertThatThrownBy(() -> service.decrypt(encrypted)).isExactlyInstanceOf(CryptoException.class);
  }
}
