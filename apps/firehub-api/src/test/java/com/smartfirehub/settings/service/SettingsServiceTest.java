package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class SettingsServiceTest extends IntegrationTestBase {

  @Autowired private SettingsService settingsService;
  @Autowired private EncryptionService encryptionService;

  @Test
  void getByPrefix_aiPrefix_returnsAiSettings() {
    List<SettingResponse> settings = settingsService.getByPrefix("ai");

    // Flyway migrations seed at least the ai.* keys
    assertThat(settings).isNotEmpty();
    assertThat(settings).allSatisfy(s -> assertThat(s.key()).startsWith("ai."));
  }

  @Test
  void updateSettings_validKey_updatesSuccessfully() {
    // given: pick a key we know exists from migrations
    // userId is null because no user seed data exists in the test DB;
    // updated_by column is nullable (no NOT NULL constraint).
    Map<String, String> update = Map.of("ai.max_turns", "10");

    // when / then: no exception
    settingsService.updateSettings(update, null);

    // verify the value was persisted within this transaction
    List<SettingResponse> settings = settingsService.getByPrefix("ai");
    assertThat(settings)
        .filteredOn(s -> "ai.max_turns".equals(s.key()))
        .hasSize(1)
        .first()
        .satisfies(s -> assertThat(s.value()).isEqualTo("10"));
  }

  @Test
  void updateSettings_invalidKey_throwsIllegalArgumentException() {
    Map<String, String> update = Map.of("unknown.key", "value");

    assertThatThrownBy(() -> settingsService.updateSettings(update, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("허용되지 않는 설정 키");
  }

  @Test
  void updateSettings_maxTurnsBelowRange_throwsIllegalArgumentException() {
    // ai.max_turns must be 1~50; 0 is invalid
    Map<String, String> update = Map.of("ai.max_turns", "0");

    assertThatThrownBy(() -> settingsService.updateSettings(update, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("최대 턴 수는 1에서 50 사이");
  }

  @Test
  void updateSettings_temperatureAboveRange_throwsIllegalArgumentException() {
    // ai.temperature must be 0.0~1.0; 1.5 is invalid
    Map<String, String> update = Map.of("ai.temperature", "1.5");

    assertThatThrownBy(() -> settingsService.updateSettings(update, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Temperature는 0.0에서 1.0 사이");
  }

  // ── API key tests ─────────────────────────────────────────────────────────

  @Test
  void updateSettings_apiKey_encryptsBeforeStore() {
    // when: store a plain-text API key
    settingsService.updateSettings(Map.of("ai.api_key", "sk-test-plain-key"), null);

    // then: the raw value in DB is NOT the plain text — it is an encrypted iv:ciphertext blob
    Optional<String> rawStored = settingsService.getValue("ai.api_key");
    assertThat(rawStored).isPresent();
    String raw = rawStored.get();
    assertThat(raw).isNotEqualTo("sk-test-plain-key");
    // AES-GCM output format is "base64iv:base64cipher" — both parts are Base64, separated by ':'
    assertThat(raw).contains(":");

    // and the stored value decrypts back to the original
    assertThat(encryptionService.decrypt(raw)).isEqualTo("sk-test-plain-key");
  }

  @Test
  void updateSettings_apiKey_emptyValue_throwsValidation() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("ai.api_key", ""), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("API 키는 비어있을 수 없습니다");
  }

  @Test
  void getByPrefix_apiKey_returnsMasked() {
    // given: store a real API key first
    settingsService.updateSettings(Map.of("ai.api_key", "sk-test-abcdefghij"), null);

    // when: retrieve via getByPrefix
    List<SettingResponse> settings = settingsService.getByPrefix("ai");

    // then: ai.api_key value must start with "****" (masked)
    assertThat(settings)
        .filteredOn(s -> "ai.api_key".equals(s.key()))
        .hasSize(1)
        .first()
        .satisfies(s -> assertThat(s.value()).startsWith("****"));
  }

  @Test
  void getDecryptedApiKey_returnsOriginal() {
    // given: encrypt and persist
    settingsService.updateSettings(Map.of("ai.api_key", "sk-original-secret"), null);

    // when
    Optional<String> decrypted = settingsService.getDecryptedApiKey();

    // then: original plain-text is recovered
    assertThat(decrypted).isPresent().hasValue("sk-original-secret");
  }

  @Test
  void getDecryptedApiKey_notSet_returnsEmpty() {
    // given: ensure api_key is blank (reset to empty by storing a blank-equivalent via direct repo)
    // The V31 migration seeds ai.api_key with '' — within this @Transactional test we can rely on
    // that initial empty state because no other test in this class persists a key before us.
    // We explicitly reset it here through a masked-value update (which skips the DB write),
    // so the value remains the seeded empty string.
    // Actually we just call getDecryptedApiKey on the unmodified seeded empty row.
    Optional<String> result = settingsService.getDecryptedApiKey();

    assertThat(result).isEmpty();
  }

  @Test
  void updateSettings_apiKey_maskedValue_skipsUpdate() {
    // given: store a real key first
    settingsService.updateSettings(Map.of("ai.api_key", "sk-real-key-stored"), null);
    Optional<String> encryptedAfterFirstStore = settingsService.getValue("ai.api_key");
    assertThat(encryptedAfterFirstStore).isPresent();
    String encryptedValue = encryptedAfterFirstStore.get();

    // when: send a masked value (as the frontend does when the user has not changed the key)
    settingsService.updateSettings(Map.of("ai.api_key", "****abcd"), null);

    // then: the stored encrypted value must NOT have changed
    Optional<String> encryptedAfterMaskedUpdate = settingsService.getValue("ai.api_key");
    assertThat(encryptedAfterMaskedUpdate).isPresent().hasValue(encryptedValue);
  }
}
