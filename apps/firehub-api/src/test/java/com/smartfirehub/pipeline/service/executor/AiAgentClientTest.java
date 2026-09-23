package com.smartfirehub.pipeline.service.executor;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.settings.model.AiBehaviorDefaults;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.AiCredentialService.ClassifyBinding;
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
  /** 실제 해석기 — mock 위에 세운다(#707). 기본은 분류 슬롯 없음 → UseChat. */
  private AiClassifyTargetResolver resolver;

  /** WireMock 을 ai-agent 로 바라보는 클라이언트를 만든다. */
  private AiAgentClient newClient() {
    settingsService = mock(SettingsService.class);
    aiCredentialService = mock(AiCredentialService.class);
    resolver = new AiClassifyTargetResolver(aiCredentialService);
    AiAgentClient client =
        new AiAgentClient(
            wireMock.baseUrl(), new ObjectMapper(), settingsService, aiCredentialService);
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

    AiAgentClient.ClassifyResponse response = client.classify(REQUEST, resolver.resolve(), 42L);

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

    client.classify(REQUEST, resolver.resolve(), 7L);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/classify"))
            .withRequestBody(matchingJsonPath("$.apiKey", equalTo("sk-test-key"))));
  }

  @Test
  void classify_omitsBlankCredentials() {
    AiAgentClient client = newClient();
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of(AiBehaviorDefaults.MODEL));
    // 공백 문자열은 "없음"으로 취급해 바디에 넣지 않는다 — ai-agent 가 잘못된 자격증명으로 시도하지 않도록.
    // oauthToken 은 채워 둔다 — 둘 다 비면 자격증명 자체가 불완전해 요청 전에 막힌다(아래 테스트).
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Sdk("oauth-x", "   "));
    stubOk();

    client.classify(REQUEST, resolver.resolve(), 1L);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/classify"))
            .withRequestBody(notMatching(".*apiKey.*"))
            .withRequestBody(matchingJsonPath("$.oauthToken", equalTo("oauth-x"))));
  }

  /**
   * #706 — AI 자격증명이 테넌트 전용이 되면서 미설정 테넌트의 {@code resolve()} 는 빈 sdk 를
   * 돌려준다. 분류는 채팅·프로액티브와 같은 문구({@code incompleteMessage()})로 <b>요청 전에</b>
   * 실패해야 한다 — 비밀 없는 요청이 ai-agent 에 닿으면 컨테이너 ambient 키로 떨어질 여지가 생긴다.
   * 네 유형 전부의 불완전 형태를 돌려 보고, 어느 경우에도 ai-agent 가 요청을 한 건도 받지 않았는지
   * 확인한다. opencode 는 ai.model 이 opencode 형식이 아닌데도 "모델" 문구가 아니라 자격증명 문구가
   * 나와야 한다(불완전 검사가 모델 검사보다 먼저 — 순서 고정).
   */
  @Test
  void classify_자격증명이_불완전하면_요청을_보내지_않고_안내_문구로_실패한다() {
    List<AiCredential> incomplete =
        List.of(
            new AiCredential.Sdk("", ""),
            new AiCredential.Cli(""),
            new AiCredential.CliApi(""),
            new AiCredential.Opencode("", "", "", "sk-x"));
    for (AiCredential credential : incomplete) {
      AiAgentClient client = newClient();
      when(settingsService.getValue("ai.model")).thenReturn(Optional.of("claude-sonnet-5"));
      when(aiCredentialService.resolve()).thenReturn(credential);
      stubOk();

      assertThatThrownBy(() -> client.classify(REQUEST, resolver.resolve(), 1L))
          .as(credential.agentType())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(credential.incompleteMessage());
    }

    wireMock.verify(0, anyRequestedFor(anyUrl()));
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

    client.classify(REQUEST, resolver.resolve(), 1L);

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

    assertThatThrownBy(() -> client.classify(REQUEST, resolver.resolve(), 1L))
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

    assertThatThrownBy(() -> client.classify(REQUEST, resolver.resolve(), 1L))
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

    assertThatThrownBy(() -> client.classify(REQUEST, resolver.resolve(), 1L))
        .isInstanceOf(com.smartfirehub.settings.model.UnknownAgentTypeException.class);

    wireMock.verify(0, postRequestedFor(urlEqualTo("/agent/classify")));
  }

  /**
   * #707 — 분류 전용이 설정되면 바디의 agentType/baseUrl/model/apiKey 가 전부 분류 슬롯 값이고,
   * 채팅 자격증명은 한 칸도 섞이지 않으며 읽히지도 않는다.
   */
  @Test
  void classify_분류_전용이면_바디가_분류_슬롯_값만_싣고_채팅은_읽지_않는다() {
    AiAgentClient client = newClient();
    when(aiCredentialService.resolveClassify())
        .thenReturn(
            Optional.of(
                new ClassifyBinding(
                    new AiCredential.Opencode("openai", "https://gw.example/v1", "", "sk-classify"),
                    "openai/gpt-4o-mini")));
    stubOk();

    client.classify(REQUEST, resolver.resolve(), 1L);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/classify"))
            .withRequestBody(matchingJsonPath("$.agentType", equalTo("opencode")))
            .withRequestBody(matchingJsonPath("$.baseUrl", equalTo("https://gw.example/v1")))
            .withRequestBody(matchingJsonPath("$.model", equalTo("openai/gpt-4o-mini")))
            .withRequestBody(matchingJsonPath("$.apiKey", equalTo("sk-classify"))));
    verify(aiCredentialService, never()).resolve();
    verify(settingsService, never()).getValue("ai.model");
  }

  /** 분류 슬롯의 opencode 모델 형식이 틀리면 HTTP 호출 전에 실패한다(채팅 슬롯과 같은 가드). */
  @Test
  void classify_분류_전용_opencode_모델_형식이_틀리면_HTTP_전에_실패한다() {
    AiAgentClient client = newClient();
    when(aiCredentialService.resolveClassify())
        .thenReturn(
            Optional.of(
                new ClassifyBinding(
                    new AiCredential.Opencode("openai", "https://gw.example/v1", "", "sk-classify"),
                    "gpt-4o-mini")));

    assertThatThrownBy(() -> client.classify(REQUEST, resolver.resolve(), 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("opencode 형식");
    wireMock.verify(0, postRequestedFor(urlEqualTo("/agent/classify")));
  }

  /**
   * UseChat 은 요청을 만드는 순간 채팅 자격증명·ai.model 을 해석한다 — 해석 결과가 옛
   * buildClassifyBody 와 같은 바디를 만든다(미설정 = 현행과 바이트 동일).
   */
  @Test
  void classify_UseChat_은_요청_시점에_채팅_설정으로_바디를_만든다() {
    AiAgentClient client = newClient();
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("claude-sonnet-5"));
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Sdk("chat-oauth", ""));

    Map<String, Object> body = client.buildClassifyBody(REQUEST, new AiClassifyTarget.UseChat());

    assertThat(body)
        .containsEntry("agentType", "sdk")
        .containsEntry("oauthToken", "chat-oauth")
        .containsEntry("model", "claude-sonnet-5")
        .doesNotContainKey("apiKey");
  }

  /**
   * 같은 UseChat 인스턴스(= 한 실행)로 바디를 여러 번 만들면 채팅 자격증명·ai.model 은 첫 성공 때만
   * 읽고, 이후 바디는 첫 바디와 같다(#707 후속 3).
   */
  @Test
  void buildClassifyBody_같은_UseChat_이면_채팅_설정을_한_번만_읽는다() {
    AiAgentClient client = newClient();
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("claude-sonnet-5"));
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Sdk("chat-oauth", ""));
    AiClassifyTarget run = resolver.resolve();

    Map<String, Object> first = client.buildClassifyBody(REQUEST, run);
    Map<String, Object> second = client.buildClassifyBody(REQUEST, run);

    assertThat(second).isEqualTo(first);
    verify(aiCredentialService, times(1)).resolve();
    verify(settingsService, times(1)).getValue("ai.model");
  }

  /** 해석이 던지면 기억하지 않는다 — 같은 실행의 다음 호출이 다시 읽어 성공한다(#707 후속 3). */
  @Test
  void buildClassifyBody_해석_실패는_기억하지_않는다() {
    AiAgentClient client = newClient();
    when(settingsService.getValue("ai.model")).thenReturn(Optional.of("claude-sonnet-5"));
    when(aiCredentialService.resolve())
        .thenThrow(new IllegalStateException("boom"))
        .thenReturn(new AiCredential.Sdk("chat-oauth", ""));
    AiClassifyTarget run = resolver.resolve();

    assertThatThrownBy(() -> client.buildClassifyBody(REQUEST, run))
        .isInstanceOf(IllegalStateException.class);
    assertThat(client.buildClassifyBody(REQUEST, run)).containsEntry("oauthToken", "chat-oauth");
    verify(aiCredentialService, times(2)).resolve();
  }
}
