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

@Transactional
class SettingsServiceTest extends IntegrationTestBase {

  @Autowired private SettingsService settingsService;

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
}
