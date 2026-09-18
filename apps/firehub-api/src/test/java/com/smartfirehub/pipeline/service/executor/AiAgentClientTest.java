package com.smartfirehub.pipeline.service.executor;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.settings.service.SettingsService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AiAgentClient 의 자격증명 주입 검증.
 *
 * <p>핵심 회귀 방지 지점: ai-agent 가 {@code /settings/ai-api-key}(ADMIN 전용)를 역호출해 스스로 키를 가져오던
 * 구조를 걷어내고, 채팅 프록시와 동일하게 firehub-api 가 복호화한 자격증명을 요청 바디로 주입한다. prod 는
 * ai.api_key 가 비어 있고 ai.cli_oauth_token 만 설정되어 있으므로 OAuth 토큰 전달이 필수다.
 */
class AiAgentClientTest {

  static WireMockServer wireMock;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @BeforeEach
  void resetWireMock() {
    wireMock.resetAll();
  }

  private SettingsService settingsService;

  /** WireMock 을 ai-agent 로 바라보는 클라이언트를 만든다. */
  private AiAgentClient newClient() {
    settingsService = mock(SettingsService.class);
    AiAgentClient client =
        new AiAgentClient(wireMock.baseUrl(), new ObjectMapper(), settingsService);
    ReflectionTestUtils.setField(client, "internalToken", "test-internal-token");
    return client;
  }

  private static final AiAgentClient.ClassifyRequest REQUEST =
      new AiAgentClient.ClassifyRequest(
          List.of(Map.of("id", 1, "comment", "좋아요")),
          "감성 분류",
          List.of(Map.of("name", "label", "type", "TEXT")));

  private void stubOk() {
    wireMock.stubFor(
        post(urlEqualTo("/agent/classify"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"results\":[{\"source_id\":1,\"label\":\"긍정\"}],\"processed\":1,\"model\":\"claude-sonnet-5\"}")));
  }

  @Test
  void classify_injectsOauthTokenAndModelIntoBody() {
    AiAgentClient client = newClient();
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("claude-sonnet-5"));
    when(settingsService.getAiCredentials())
        .thenReturn(new SettingsService.AiCredentials("sdk", "", "oauth-token-value"));
    stubOk();

    AiAgentClient.ClassifyResponse response = client.classify(REQUEST, 42L);

    assertThat(response.results()).hasSize(1);
    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/classify"))
            .withHeader("X-On-Behalf-Of", equalTo("42"))
            .withRequestBody(matchingJsonPath("$.oauthToken", equalTo("oauth-token-value")))
            .withRequestBody(matchingJsonPath("$.model", equalTo("claude-sonnet-5"))));
  }

  @Test
  void classify_injectsApiKeyWhenPresent() {
    AiAgentClient client = newClient();
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("claude-haiku-4-5"));
    when(settingsService.getAiCredentials())
        .thenReturn(new SettingsService.AiCredentials("sdk", "sk-test-key", ""));
    stubOk();

    client.classify(REQUEST, 7L);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/classify"))
            .withRequestBody(matchingJsonPath("$.apiKey", equalTo("sk-test-key"))));
  }

  @Test
  void classify_omitsBlankCredentials() {
    AiAgentClient client = newClient();
    when(settingsService.getValue("ai.model")).thenReturn(Optional.empty());
    // 공백 문자열은 "없음"으로 취급해 바디에 넣지 않는다 — ai-agent 가 잘못된 자격증명으로 시도하지 않도록.
    when(settingsService.getAiCredentials())
        .thenReturn(new SettingsService.AiCredentials("sdk", "   ", ""));
    stubOk();

    client.classify(REQUEST, 1L);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/classify"))
            .withRequestBody(notMatching(".*apiKey.*"))
            .withRequestBody(notMatching(".*oauthToken.*")));
  }

  /**
   * classify 는 채팅과 달리 opencode 로 라우팅되지 않는다 — ai-agent 의
   * ProviderFactory.createCompletionProvider(provider-factory.ts)는 agentType 분기 없이 SDK 경로
   * 하나로 고정되어 있다. 그래서 agentType=opencode 라도 자격증명은 그대로 바디에 실려야 한다. 예전에
   * AiAgentProxyService(채팅 경로)의 opencode 규칙을 여기로 잘못 옮겨와 opencode + 자격증명 보유 테넌트의
   * classify 요청이 자격증명 없이 나가고, ai-agent 가 컨테이너 자신의 ANTHROPIC_API_KEY 로 조용히 폴백하는
   * 회귀가 있었다(테넌트 청구가 플랫폼 계정으로 새는 사고). 이 테스트는 그 회귀를 고정 방지한다 — 다시
   * agentType 가드를 넣으면 이 테스트가 실패해야 한다.
   */
  @Test
  void classify_opencode_stillSendsCredentialsWhenPresent() {
    AiAgentClient client = newClient();
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("claude-sonnet-5"));
    when(settingsService.getAiCredentials())
        .thenReturn(new SettingsService.AiCredentials("opencode", "sk-test-key", "oauth-token-value"));
    stubOk();

    client.classify(REQUEST, 1L);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/classify"))
            .withRequestBody(matchingJsonPath("$.apiKey", equalTo("sk-test-key")))
            .withRequestBody(matchingJsonPath("$.oauthToken", equalTo("oauth-token-value"))));
  }
}
