package com.smartfirehub.settings.service;

import static com.smartfirehub.support.SettingsTestSupport.rawSystemSettingValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * SettingsService CLI OAuth 토큰 및 추가 AI 설정 유효성 검증 테스트. validateValues() 내의 나머지 분기(agent_type,
 * session_max_tokens, max_tokens)를 커버한다.
 *
 * <p><b>P7-b Task 5 이후</b> {@code ai.cli_oauth_token}/{@code ai.agent_type} 은 플랫폼 잠금 키라
 * {@code updatePlatformSettings} 로만 쓸 수 있다 — 이 두 키를 다루는 테스트만 그쪽으로 옮겼다.
 * 나머지(테넌트 오버라이드 6키: system_prompt/model/max_turns/temperature/max_tokens/
 * session_max_tokens)는 여전히 {@code updateSettings} 를 부른다 — {@code getValue} 가 오버라이드를
 * 우선 해석하므로(기본 테넌트 컨텍스트가 서 있는 이 테스트 환경에서) 검증 로직이 같다면 결과
 * 단언은 그대로 유효하다.
 */
@Transactional
class SettingsServiceCliTokenTest extends IntegrationTestBase {

  @Autowired private SettingsService settingsService;
  @Autowired private DSLContext dsl;

  @Test
  void updateSettings_cliOauthToken_encryptsBeforeStore() {
    settingsService.updatePlatformSettings(Map.of("ai.cli_oauth_token", "oauth-test-token-abc"), null);

    // getValue("ai.cli_oauth_token") 는 이제 번들 키라 거부된다(rejectBundleKey) — DB 원문을
    // 직접 읽는다.
    String raw = rawSystemSettingValue(dsl, "ai.cli_oauth_token");
    assertThat(raw).isNotNull();
    // 평문이 그대로 저장되지 않음 (암호화됨)
    assertThat(raw).isNotEqualTo("oauth-test-token-abc");
    assertThat(raw).contains(":");
  }

  // getAiCredentials_cliOauthToken_returnsOriginal / getAiCredentials_cliOauthToken_notSet_returnsEmpty
  // 는 지웠다 — 검증 대상이던 SettingsService.getAiCredentials()/AiCredentials 자체가 타입형
  // 전환(2026-09)으로 사라졌다. ai.cli_oauth_token 은 이제 플랫폼 기본값 전용 레거시 값이라
  // 아무도 읽지 않는다(암호화 저장 자체는 위 updateSettings_cliOauthToken_encryptsBeforeStore 가
  // 계속 지킨다). 복호화된 원문 복원 계약은 지금은 AiCredentialService.resolve()/
  // AiCredentialServiceTest 가 진다.

  @Test
  void updateSettings_agentType_validValues_success() {
    // sdk, cli, cli-api 모두 허용
    settingsService.updatePlatformSettings(Map.of("ai.agent_type", "sdk"), null);
    settingsService.updatePlatformSettings(Map.of("ai.agent_type", "cli"), null);
    settingsService.updatePlatformSettings(Map.of("ai.agent_type", "cli-api"), null);
  }

  @Test
  void updateSettings_agentType_invalidValue_throwsIllegalArgument() {
    assertThatThrownBy(
            () -> settingsService.updatePlatformSettings(Map.of("ai.agent_type", "unknown"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("에이전트 유형은");
  }

  @Test
  void updateSettings_maxTokensAboveRange_throwsIllegalArgument() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("ai.max_tokens", "99999"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("최대 토큰 수는");
  }

  @Test
  void updateSettings_maxTokensBelowRange_throwsIllegalArgument() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("ai.max_tokens", "0"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("최대 토큰 수는");
  }

  @Test
  void updateSettings_sessionMaxTokensBelowRange_throwsIllegalArgument() {
    assertThatThrownBy(
            () -> settingsService.updateSettings(Map.of("ai.session_max_tokens", "500"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("세션 최대 토큰 수는");
  }

  @Test
  void updateSettings_sessionMaxTokensAboveRange_throwsIllegalArgument() {
    assertThatThrownBy(
            () -> settingsService.updateSettings(Map.of("ai.session_max_tokens", "999999"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("세션 최대 토큰 수는");
  }

  @Test
  void updateSettings_systemPromptBlank_throwsIllegalArgument() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("ai.system_prompt", "  "), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("시스템 프롬프트는");
  }

  @Test
  void updateSettings_systemPrompt_validValue_success() {
    settingsService.updateSettings(
        Map.of("ai.system_prompt", "You are a helpful assistant."), null);

    Optional<String> val = settingsService.getValue("ai.system_prompt");
    assertThat(val).isPresent().hasValue("You are a helpful assistant.");
  }

  @Test
  void updateSettings_maxTurnsAboveRange_throwsIllegalArgument() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("ai.max_turns", "51"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("최대 턴 수는 1에서 50 사이");
  }

  @Test
  void updateSettings_temperatureBelowRange_throwsIllegalArgument() {
    assertThatThrownBy(() -> settingsService.updateSettings(Map.of("ai.temperature", "-0.1"), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Temperature는 0.0에서 1.0 사이");
  }

  @Test
  void updateSettings_model_freeFormString_success() {
    // ai.model은 자유 형식 문자열 — 검증 없이 저장
    settingsService.updateSettings(Map.of("ai.model", "claude-opus-4-5"), null);

    Optional<String> val = settingsService.getValue("ai.model");
    assertThat(val).isPresent().hasValue("claude-opus-4-5");
  }

  @Test
  void updateSettings_cliOauthToken_maskedValue_skipsUpdate() {
    // 먼저 토큰 저장
    settingsService.updatePlatformSettings(Map.of("ai.cli_oauth_token", "real-cli-token-stored"), null);
    // getValue("ai.cli_oauth_token") 는 번들 키라 거부된다 — DB 원문을 직접 읽는다.
    String encryptedValue = rawSystemSettingValue(dsl, "ai.cli_oauth_token");
    assertThat(encryptedValue).isNotNull();

    // masked 값 전송 시 업데이트 스킵.
    // Task 5 가 센티널 판정을 EncryptionService.maskValue 의 **형태**(길이 4 또는 8)로 좁혔다 —
    // 예전 값 "****masked"(길이 10)는 서버가 만들 수 없는 마스크였고, 이제는 센티널이 아니라
    // 사용자가 새로 입력한 토큰으로 저장된다. 화면이 실제로 되돌려 보내는 형태
    // (maskValue("real-cli-token-stored") == "****ored")로 바꾼다.
    settingsService.updatePlatformSettings(Map.of("ai.cli_oauth_token", "****ored"), null);

    assertThat(rawSystemSettingValue(dsl, "ai.cli_oauth_token")).isEqualTo(encryptedValue);
  }
}
