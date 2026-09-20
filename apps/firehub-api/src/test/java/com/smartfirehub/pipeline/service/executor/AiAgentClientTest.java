package com.smartfirehub.pipeline.service.executor;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
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
 * 구조를 걷어내고, 채팅 프록시와 동일하게 firehub-api 가 복호화한 자격증명을 요청 바디로 주입한다.
 *
 * <p><b>타입형 전환(2026-09) 이후</b> 자격증명 소스가 {@code SettingsService.getAiCredentials()}
 * (3키 번들)에서 {@code AiCredentialService.resolve()}(단일 JSON 문서 → 타입 있는
 * {@link AiCredential})로 바뀌었다. classify() 는 buildClassifyBody() 로 바디 조립을 위임하므로
 * (Ruling #4) 이 클래스는 WireMock 을 실제로 거치는 classify() 통합 동작만 검증하고, 유형별
 * 바디 조립 규칙 자체는 {@code com.smartfirehub.ai.AmbientKeyNeverUsedTest} 가 HTTP 없이
 * {@code buildClassifyBody()} 를 직접 불러 검증한다.
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
  private AiCredentialService aiCredentialService;

  /** WireMock 을 ai-agent 로 바라보는 클라이언트를 만든다. */
  private AiAgentClient newClient() {
    settingsService = mock(SettingsService.class);
    aiCredentialService = mock(AiCredentialService.class);
    AiAgentClient client =
        new AiAgentClient(wireMock.baseUrl(), new ObjectMapper(), settingsService, aiCredentialService);
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
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Sdk("oauth-token-value", ""));
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
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Sdk("", "sk-test-key"));
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
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Sdk("", "   "));
    stubOk();

    client.classify(REQUEST, 1L);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/classify"))
            .withRequestBody(notMatching(".*apiKey.*"))
            .withRequestBody(notMatching(".*oauthToken.*")));
  }

  /**
   * opencode 자격증명이면 agentType/providerId/baseUrl 이 바디에 실린다 — classify 가 이 유형을
   * 아직 실제로 라우팅하지 않더라도(ai-agent 완성 provider 배선은 별도 태스크), 조립 규칙
   * 자체는 유형별로 정확해야 한다.
   */
  @Test
  void classify_opencode_자격증명이면_provider_설정이_실린다() {
    AiAgentClient client = newClient();
    // opencode 형식(providerId/modelId) — 형식 가드를 통과해야 이 테스트가 검증하려는 바디
    // 조립 규칙까지 도달한다.
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("openai/gpt-4o"));
    when(aiCredentialService.resolve())
        .thenReturn(
            new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk-oai"));
    stubOk();

    client.classify(REQUEST, 1L);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/classify"))
            .withRequestBody(matchingJsonPath("$.agentType", equalTo("opencode")))
            .withRequestBody(matchingJsonPath("$.providerId", equalTo("openai")))
            .withRequestBody(matchingJsonPath("$.baseUrl", equalTo("https://api.openai.com/v1")))
            .withRequestBody(matchingJsonPath("$.apiKey", equalTo("sk-oai"))));
  }

  /**
   * (전체 브랜치 리뷰 I3) 분류 경로에만 opencode 모델 형식 가드가 없었다 — chat
   * (AiAgentProxyService:274-292) 에는 이미 있다. ai.model 기본값 "claude-sonnet-5" 는 슬래시가
   * 없어 opencode 형식이 아니고, 가드 없이 그대로 보내면 OpenAI 호환 호스트가 원인을 알 수 없는
   * 상류 오류로만 실패한다 — buildClassifyBody() 가 먼저 막아야 한다.
   */
  @Test
  void classify_opencode_모델에_슬래시가_없으면_명시적으로_실패한다() {
    AiAgentClient client = newClient();
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("claude-sonnet-5"));
    when(aiCredentialService.resolve())
        .thenReturn(
            new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk-oai"));

    assertThatThrownBy(() -> client.classify(REQUEST, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("opencode 형식");

    // 형식이 틀렸으니 ai-agent 를 호출해서도 안 된다 — 잘못된 모델로 상류에 요청을 보내는 낭비를 막는다.
    wireMock.verify(0, postRequestedFor(urlEqualTo("/agent/classify")));
  }

  /**
   * 슬래시는 있어도 접두사가 해석된 providerId 와 다르면(예: sdk 시절 값이 남았거나 공급자를
   * 바꾼 뒤 모델을 안 바꾼 경우) 같은 이유로 막아야 한다 — chat 의 검사와 동일하게 두 조건을
   * 모두 본다.
   */
  @Test
  void classify_opencode_모델의_providerId가_불일치하면_명시적으로_실패한다() {
    AiAgentClient client = newClient();
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("anthropic/claude-sonnet-5"));
    when(aiCredentialService.resolve())
        .thenReturn(
            new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk-oai"));

    assertThatThrownBy(() -> client.classify(REQUEST, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("opencode 형식");

    wireMock.verify(0, postRequestedFor(urlEqualTo("/agent/classify")));
  }

  /**
   * 알 수 없는 agentType(손으로 고친 행 등)을 만나면 resolve() 가 던지는
   * {@code UnknownAgentTypeException} 이 classify() 의 {@code catch(Exception e)} 에 삼켜지지
   * 않고 그대로 전파돼야 한다 — buildClassifyBody() 가 try 밖에서 불려야 하는 이유(Ruling #4
   * dispatch 노트)를 classify() 수준에서 고정한다.
   */
  @Test
  void classify_알수없는_유형이면_예외가_그대로_전파된다() {
    AiAgentClient client = newClient();
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("claude-sonnet-5"));
    when(aiCredentialService.resolve())
        .thenThrow(new com.smartfirehub.settings.model.UnknownAgentTypeException("martian"));

    assertThatThrownBy(() -> client.classify(REQUEST, 1L))
        .isInstanceOf(com.smartfirehub.settings.model.UnknownAgentTypeException.class);

    wireMock.verify(0, postRequestedFor(urlEqualTo("/agent/classify")));
  }
}
