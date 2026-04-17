package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * SettingsService SMTP 관련 메서드 커버리지 보강 테스트.
 * getSmtpSettings / updateSmtpSettings / getSmtpConfig / 유효성 검증을 검증한다.
 */
@Transactional
class SmtpSettingsServiceTest extends IntegrationTestBase {

  @Autowired private SettingsService settingsService;

  @Test
  void getSmtpSettings_returnsSmtpKeys() {
    List<SettingResponse> settings = settingsService.getSmtpSettings();

    assertThat(settings).isNotEmpty();
    assertThat(settings).allSatisfy(s -> assertThat(s.key()).startsWith("smtp."));
  }

  @Test
  void updateSmtpSettings_validKeys_updatesSuccessfully() {
    Map<String, String> update =
        Map.of(
            "smtp.host", "smtp.example.com",
            "smtp.port", "587",
            "smtp.starttls", "true",
            "smtp.from_address", "noreply@example.com");

    // should not throw
    settingsService.updateSmtpSettings(update, null);

    Map<String, String> config = settingsService.getSmtpConfig();
    assertThat(config).containsEntry("smtp.host", "smtp.example.com");
    assertThat(config).containsEntry("smtp.port", "587");
    assertThat(config).containsEntry("smtp.from_address", "noreply@example.com");
  }

  @Test
  void updateSmtpSettings_invalidKey_throwsIllegalArgument() {
    Map<String, String> update = Map.of("ai.model", "gpt-4");

    assertThatThrownBy(() -> settingsService.updateSmtpSettings(update, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("허용되지 않는 SMTP 설정 키");
  }

  @Test
  void updateSmtpSettings_password_encryptsBeforeStore() {
    Map<String, String> update = Map.of("smtp.password", "secret-smtp-pass");

    settingsService.updateSmtpSettings(update, null);

    // getSmtpSettings should mask the password
    List<SettingResponse> settings = settingsService.getSmtpSettings();
    assertThat(settings)
        .filteredOn(s -> "smtp.password".equals(s.key()))
        .hasSize(1)
        .first()
        .satisfies(s -> assertThat(s.value()).startsWith("****"));
  }

  @Test
  void updateSmtpSettings_maskedPassword_skipsUpdate() {
    // Store a real password first
    settingsService.updateSmtpSettings(Map.of("smtp.password", "real-smtp-password"), null);

    // Read encrypted value
    Map<String, String> config1 = settingsService.getSmtpConfig();
    // Password should be decrypted in getSmtpConfig
    assertThat(config1.get("smtp.password")).isEqualTo("real-smtp-password");

    // Now send a masked password (frontend behavior when unchanged)
    settingsService.updateSmtpSettings(Map.of("smtp.password", "****masked"), null);

    // Password should remain unchanged
    Map<String, String> config2 = settingsService.getSmtpConfig();
    assertThat(config2.get("smtp.password")).isEqualTo("real-smtp-password");
  }

  @Test
  void getSmtpConfig_noPassword_returnsEmptyString() {
    // Clear password by storing empty string
    settingsService.updateSmtpSettings(Map.of("smtp.password", ""), null);

    Map<String, String> config = settingsService.getSmtpConfig();
    // Password entry exists but value is empty
    assertThat(config.get("smtp.password")).isIn("", null);
  }

  @Test
  void updateSmtpSettings_username_updatesSuccessfully() {
    settingsService.updateSmtpSettings(Map.of("smtp.username", "smtp-user@example.com"), null);

    Map<String, String> config = settingsService.getSmtpConfig();
    assertThat(config).containsEntry("smtp.username", "smtp-user@example.com");
  }
}
