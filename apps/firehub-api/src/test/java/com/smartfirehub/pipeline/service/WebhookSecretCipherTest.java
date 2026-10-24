package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.CryptoException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * 웹훅 시크릿 암호화 단위 테스트(WD-220, 스프링 없음) — 새 키 왕복, 레거시(기본 키·WEBHOOK_SECRET_KEY) ECB 암호문 호환, 변조·틀린 키 거부,
 * 키 원천이 없을 때 기본 키로 돌아가지 않는지를 확인한다.
 */
class WebhookSecretCipherTest {

  private static final String MASTER = "dGVzdC1lbmNyeXB0aW9uLWtleS0zMmJ5dGVzLWhlcmU=";

  /** 옛 TriggerService.encryptSecret 과 같은 방식(32바이트 정규화 키, AES-ECB)으로 암호문을 만든다. */
  private static String legacyEncrypt(String key, String plaintext) throws Exception {
    Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(WebhookSecretCipher.legacyKeyBytes(key), "AES"));
    return Base64.getEncoder()
        .encodeToString(c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void encrypt_usesV2FormatAndRoundTrips() {
    WebhookSecretCipher cipher = new WebhookSecretCipher(null, MASTER);
    String enc = cipher.encrypt("my-secret");

    assertThat(enc).startsWith("v2:");
    assertThat(cipher.decrypt(enc)).isEqualTo("my-secret");
    // 무작위 IV — 같은 평문도 매번 다른 암호문
    assertThat(cipher.encrypt("my-secret")).isNotEqualTo(enc);
  }

  @Test
  void encrypt_isNotDecryptableWithLegacyDefaultKey() {
    // 새 암호문은 공개 기본 키를 아는 것만으로는 풀 수 없다 — 다른 마스터키로 만든 cipher 는 실패해야 한다
    String enc = new WebhookSecretCipher(null, MASTER).encrypt("s");
    WebhookSecretCipher other = new WebhookSecretCipher(null, "b3RoZXItbWFzdGVyLWtleQ==");
    assertThatThrownBy(() -> other.decrypt(enc)).isInstanceOf(CryptoException.class);
  }

  @Test
  void decrypt_legacyDefaultKeyCiphertext() throws Exception {
    String legacy = legacyEncrypt(WebhookSecretCipher.LEGACY_DEFAULT_KEY, "old-secret");
    assertThat(new WebhookSecretCipher(null, MASTER).decrypt(legacy)).isEqualTo("old-secret");
    // WEBHOOK_SECRET_KEY 를 나중에 설정해도 기본 키 암호문이 풀린다
    assertThat(new WebhookSecretCipher("env-key", MASTER).decrypt(legacy)).isEqualTo("old-secret");
  }

  @Test
  void decrypt_legacyEnvKeyCiphertext() throws Exception {
    String legacy = legacyEncrypt("deploy-specific-key", "env-secret");
    assertThat(new WebhookSecretCipher("deploy-specific-key", MASTER).decrypt(legacy))
        .isEqualTo("env-secret");
  }

  @Test
  void decrypt_v2SurvivesAddingEnvKeyLater() {
    String enc = new WebhookSecretCipher(null, MASTER).encrypt("s2");
    assertThat(new WebhookSecretCipher("added-later", MASTER).decrypt(enc)).isEqualTo("s2");
  }

  @Test
  void decrypt_envKeyTakesPrecedenceForNewEncryption() {
    String enc = new WebhookSecretCipher("env-key", MASTER).encrypt("s3");
    // 같은 env 키면 마스터키가 달라도 풀린다
    assertThat(new WebhookSecretCipher("env-key", "b3RoZXI=").decrypt(enc)).isEqualTo("s3");
  }

  @Test
  void decrypt_tamperedV2Rejected() {
    WebhookSecretCipher cipher = new WebhookSecretCipher(null, MASTER);
    String enc = cipher.encrypt("my-secret");
    char last = enc.charAt(enc.length() - 2);
    String tampered =
        enc.substring(0, enc.length() - 2)
            + (last == 'A' ? 'B' : 'A')
            + enc.charAt(enc.length() - 1);
    assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(CryptoException.class);
  }

  @Test
  void constructor_withoutAnyKeySource_fails() {
    assertThatThrownBy(() -> new WebhookSecretCipher(null, ""))
        .isInstanceOf(IllegalStateException.class);
  }
}
