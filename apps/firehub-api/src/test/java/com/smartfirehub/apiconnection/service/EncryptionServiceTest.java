package com.smartfirehub.apiconnection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
