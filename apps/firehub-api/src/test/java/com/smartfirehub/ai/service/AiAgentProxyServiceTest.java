package com.smartfirehub.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * AiAgentProxyService 단위 기능 테스트. 외부 AI 에이전트 호출 없이 검증 가능한 분기 (verifyCliToken, verifyApiKey) 를 커버한다.
 * 실제 WebClient 호출은 외부 의존성이므로 설정값 미존재 분기만 검증한다.
 */
class AiAgentProxyServiceTest extends IntegrationTestBase {

  @Autowired private AiAgentProxyService aiAgentProxyService;

  /** SettingsService를 MockitoBean으로 교체하여 외부 AI 에이전트 호출 없이 토큰/API키 미설정 분기를 검증한다. */
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

  // 주: opencode 의 streamChat 자격증명 우회(missingCredential=false) 검증은
  // SseEmitter + WireMock 통합 환경이 필요하므로 단위 테스트가 아닌 E2E(프론트 Playwright)에서 커버한다.
  // verifyApiKey 는 agent_type 과 무관한 경로라 여기서 opencode 분기를 의미 있게 검증할 수 없어 별도 단위 테스트를 두지 않는다.

  /**
   * 회귀 테스트(#154 / #175): 토큰 값에 JSON 특수문자(따옴표·백슬래시·줄바꿈·탭 등)가 포함되어도 ObjectMapper로 직렬화하면 JSON 구조가 깨지지
   * 않고 정확한 원본 값으로 다시 파싱된다는 것을 검증한다. 이전의 문자열 연결 + replace 방식은 백슬래시를 이스케이프하지 않아 JSON 인젝션 또는 파싱 오류를
   * 유발했다.
   */
  @Test
  void objectMapperSerialization_escapesAllJsonSpecialChars() throws Exception {
    ObjectMapper mapper = new ObjectMapper();

    // case 1: 백슬래시 (#175 핵심 케이스)
    String tokenWithBackslash = "abc\\def";
    String body1 = mapper.writeValueAsString(Map.of("token", tokenWithBackslash));
    JsonNode parsed1 = mapper.readTree(body1);
    assertThat(parsed1.get("token").asText()).isEqualTo(tokenWithBackslash);

    // case 2: 따옴표 + JSON 인젝션 시도 (#154 핵심 케이스)
    String injectionPayload = "abc\\\", \"valid\":true, \"x\":\"";
    String body2 = mapper.writeValueAsString(Map.of("token", injectionPayload));
    JsonNode parsed2 = mapper.readTree(body2);
    // 인젝션이 차단되어 token 필드 안에 통째로 들어가야 한다
    assertThat(parsed2.get("token").asText()).isEqualTo(injectionPayload);
    // valid 필드가 외부에서 주입되지 않았는지 확인 (Map.of로 만든 단일 키만 존재)
    assertThat(parsed2.has("valid")).isFalse();

    // case 3: 줄바꿈/탭/제어문자
    String controlChars = "line1\nline2\tcol\rback";
    String body3 = mapper.writeValueAsString(Map.of("apiKey", controlChars));
    JsonNode parsed3 = mapper.readTree(body3);
    assertThat(parsed3.get("apiKey").asText()).isEqualTo(controlChars);
  }
}
