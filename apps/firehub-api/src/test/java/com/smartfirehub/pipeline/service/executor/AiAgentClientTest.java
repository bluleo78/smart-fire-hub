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
    when(settingsService.getDecryptedApiKey()).thenReturn(Optional.empty());
    when(settingsService.getDecryptedCliOauthToken()).thenReturn(Optional.of("oauth-token-value"));
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
    when(settingsService.getDecryptedApiKey()).thenReturn(Optional.of("sk-test-key"));
    when(settingsService.getDecryptedCliOauthToken()).thenReturn(Optional.empty());
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
    when(settingsService.getDecryptedApiKey()).thenReturn(Optional.of("   "));
    when(settingsService.getDecryptedCliOauthToken()).thenReturn(Optional.of(""));
    stubOk();

    client.classify(REQUEST, 1L);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/classify"))
            .withRequestBody(notMatching(".*apiKey.*"))
            .withRequestBody(notMatching(".*oauthToken.*")));
  }
}
