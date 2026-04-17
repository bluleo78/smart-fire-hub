package com.smartfirehub.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * AiAgentProxyService 단위 기능 테스트.
 * 외부 AI 에이전트 호출 없이 검증 가능한 분기 (verifyCliToken, verifyApiKey) 를 커버한다.
 * 실제 WebClient 호출은 외부 의존성이므로 설정값 미존재 분기만 검증한다.
 */
class AiAgentProxyServiceTest extends IntegrationTestBase {

  @Autowired private AiAgentProxyService aiAgentProxyService;

  /**
   * SettingsService를 MockitoBean으로 교체하여 외부 AI 에이전트 호출 없이
   * 토큰/API키 미설정 분기를 검증한다.
   */
  @MockitoBean private SettingsService settingsService;

  @Test
  void verifyCliToken_whenTokenEmpty_returnsInvalidJson() {
    // given: CLI OAuth 토큰이 설정되지 않은 상태
    when(settingsService.getDecryptedCliOauthToken()).thenReturn(Optional.empty());

    // when
    String result = aiAgentProxyService.verifyCliToken();

    // then: 외부 호출 없이 즉시 false 반환
    assertThat(result).isEqualTo("{\"valid\":false}");
  }

  @Test
  void verifyCliToken_whenTokenBlank_returnsInvalidJson() {
    // given: 빈 토큰
    when(settingsService.getDecryptedCliOauthToken()).thenReturn(Optional.of(""));

    String result = aiAgentProxyService.verifyCliToken();

    assertThat(result).isEqualTo("{\"valid\":false}");
  }

  @Test
  void verifyApiKey_whenKeyEmpty_returnsInvalidJson() {
    // given: API 키가 설정되지 않은 상태
    when(settingsService.getDecryptedApiKey()).thenReturn(Optional.empty());

    String result = aiAgentProxyService.verifyApiKey();

    assertThat(result).isEqualTo("{\"valid\":false}");
  }

  @Test
  void verifyApiKey_whenKeyBlank_returnsInvalidJson() {
    // given: 빈 API 키
    when(settingsService.getDecryptedApiKey()).thenReturn(Optional.of("  "));

    String result = aiAgentProxyService.verifyApiKey();

    assertThat(result).isEqualTo("{\"valid\":false}");
  }
}
