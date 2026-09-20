package com.smartfirehub.ai.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.model.UnknownAgentTypeException;
import com.smartfirehub.settings.service.AiCredentialService;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.support.IntegrationTestBase;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AiAgentProxyService 단위 기능 테스트. 외부 AI 에이전트 호출 없이 검증 가능한 분기 (verifyCliToken, verifyApiKey) 를 커버하고,
 * WireMock으로 ai-agent 서비스를 스텁하여 streamChat의 sdk/cli OAuth 토큰 주입 분기를 검증한다.
 */
class AiAgentProxyServiceTest extends IntegrationTestBase {

  // WireMock 서버를 정적 필드에서 즉시 시작한다: @DynamicPropertySource는 Spring 컨텍스트 준비(빈 생성) 이전에
  // 호출되므로, 그 시점에 이미 포트가 결정되어 있어야 agent.url 프로퍼티를 WireMock 주소로 오버라이드할 수 있다.
  static WireMockServer wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

  @BeforeAll
  static void startWireMock() {
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

  /** agent.url을 WireMock 동적 포트로 오버라이드하여 실제 ai-agent 대신 스텁 서버로 요청이 전송되게 한다. */
  @DynamicPropertySource
  static void overrideAgentUrl(DynamicPropertyRegistry registry) {
    registry.add("agent.url", () -> "http://localhost:" + wireMock.port());
  }

  @Autowired private AiAgentProxyService aiAgentProxyService;

  /** SettingsService를 MockitoBean으로 교체하여 외부 AI 에이전트 호출 없이 모델/설정 조회 분기를 검증한다. */
  @MockitoBean private SettingsService settingsService;

  /** 자격증명 출처(타입형 전환, 2026-09) — 토큰/API키 미설정·유형별 분기를 목으로 재현한다. */
  @MockitoBean private AiCredentialService aiCredentialService;

  /**
   * 토큰/키는 이제 호출부(두 {@code getAuthStatus} 컨트롤러의 exhaustive switch)가 넘긴다 —
   * 이 메서드들은 "빈 값이면 네트워크 왕복 없이 즉시 invalid" 라는 자기 계약만 지키면 된다.
   *
   * <p>여기 있던 두 테스트(opencode 필드 무시 / 알 수 없는 유형에서 예외 전파)는 <b>이 메서드가
   * 스스로 {@code resolve()} 를 부르던 시절</b>의 계약을 고정한 것이라 더 이상 대상이 없다.
   * 두 성질 자체는 한 층 위에서 그대로 검증된다 — opencode 가 이 경로를 아예 타지 않는다는 것은
   * {@code AiControllerTest} 의 "opencode 는 verify* 를 부르지 않는다"가, 알 수 없는 유형에서
   * {@code UnknownAgentTypeException} 이 전파된다는 것은 같은 파일의 500 단언이 고정한다.
   */
  @Test
  void verifyCliToken_whenTokenEmpty_returnsInvalidJson() {
    // given/when: CLI OAuth 토큰이 설정되지 않은 상태
    String result = aiAgentProxyService.verifyCliToken("");

    // then: 외부 호출 없이 즉시 false 반환
    assertThat(result).isEqualTo("{\"valid\":false}");
  }

  @Test
  void verifyCliToken_whenTokenBlank_returnsInvalidJson() {
    // given: 공백뿐인 토큰
    String result = aiAgentProxyService.verifyCliToken(" ");

    assertThat(result).isEqualTo("{\"valid\":false}");
  }

  @Test
  void verifyApiKey_whenKeyEmpty_returnsInvalidJson() {
    // given: API 키가 설정되지 않은 상태
    String result = aiAgentProxyService.verifyApiKey("");

    assertThat(result).isEqualTo("{\"valid\":false}");
  }

  @Test
  void verifyApiKey_whenKeyBlank_returnsInvalidJson() {
    // given: 공백뿐인 API 키
    String result = aiAgentProxyService.verifyApiKey("  ");

    assertThat(result).isEqualTo("{\"valid\":false}");
  }

  /**
   * sdk 모드 + OAuth 토큰 설정 시(API 키는 없음) ai-agent로 전송되는 요청 body에 {@code oauthToken}이 포함되고, 더 이상
   * 사용하지 않는 {@code cliOauthToken} 키는 포함되지 않아야 한다. (Task 1: ai-agent가 body oauthToken을 읽도록 변경됨에
   * 맞춰 firehub-api 프록시도 동일 키로 전달해야 함)
   */
  @Test
  void streamChat_sdkWithOauthToken_injectsOauthTokenIntoBody() {
    // given: agent_type=sdk, OAuth 토큰 설정, API 키는 없음
    when(settingsService.getAsMap("ai")).thenReturn(Map.of("ai.model", "claude-sonnet-5"));
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Sdk("oat-test", ""));
    wireMock.stubFor(
        post(urlEqualTo("/agent/chat"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody("event: done\ndata: {}\n\n")));

    // when
    SseEmitter emitter = new SseEmitter();
    aiAgentProxyService.streamChat(emitter, "hi", null, List.of(), 1L, null, null);

    // then: ai-agent로 전송된 body에 oauthToken 포함, cliOauthToken 키는 더 이상 사용되지 않음
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () ->
                wireMock.verify(
                    postRequestedFor(urlEqualTo("/agent/chat"))
                        .withRequestBody(matchingJsonPath("$.oauthToken", equalTo("oat-test")))
                        .withRequestBody(matchingJsonPath("$.agentType", equalTo("sdk")))
                        // 테넌트가 body 에 실려야 ai-agent 가 디스크 경로를 테넌트별로 가른다.
                        .withRequestBody(
                            matchingJsonPath(
                                "$.tenantId", equalTo(String.valueOf(DEFAULT_TEST_TENANT_ID))))
                        .withRequestBody(notMatching(".*cliOauthToken.*"))));
  }

  /**
   * agentType 의 출처가 {@code AiCredentialService.resolve()} 하나뿐인지 검증한다.
   *
   * <p>{@code getAsMap("ai")} 의 {@code ai.agent_type} 은 이제 아무도 읽지 않는 레거시 플랫폼
   * 기본값이다(타입형 전환, 2026-09) — 여기서는 일부러 <b>틀린</b> 값({@code cli-api})으로
   * 스텁하고, 실제 출처인 {@code resolve()} 는 {@code sdk}(+OAuth 토큰)를 준다. streamChat 이
   * 맵을 다시 읽지 않는지가 이 테스트의 단언이다. 맵을 다시 읽었다면 cli-api 분기로 떨어지고,
   * API 키가 없으니 ai-agent 호출 자체가 나가지 않는다. resolve() 만 봤다면 OAuth 토큰만으로
   * 인증이 성립해 호출이 나간다 — WireMock 이 그 요청을 받는 것으로 "출처가 하나"임을
   * 확인한다(오류 emit 여부보다 직접적이다).
   */
  @Test
  void streamChat_agentTypeComesOnlyFromAiCredentialService_notFromRawMap() {
    // given: 맵의 레거시 값은 cli-api(틀린 값), 실제 출처(resolve())는 sdk
    when(settingsService.getAsMap("ai"))
        .thenReturn(Map.of("ai.agent_type", "cli-api", "ai.model", "claude-sonnet-5"));
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Sdk("oat-test", ""));
    wireMock.stubFor(
        post(urlEqualTo("/agent/chat"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody("event: done\ndata: {}\n\n")));

    // when
    SseEmitter emitter = new SseEmitter();
    aiAgentProxyService.streamChat(emitter, "hi", null, List.of(), 1L, null, null);

    // then: 맵을 다시 읽어 cli-api 분기로 떨어졌다면 자격증명 부족으로 호출 자체가 나가지 않는다.
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(() -> wireMock.verify(postRequestedFor(urlEqualTo("/agent/chat"))));
  }

  /**
   * fail-closed 가드: 알 수 없는 agentType 을 만나면 {@code resolve()} 가 던지는
   * {@code UnknownAgentTypeException} 이 {@code streamChat()} 밖으로 그대로 전파돼야 한다 —
   * resolve() 를 Flux 구독 전, 요청 스레드에서 동기적으로 부르기 때문이다. 누군가 이 예외를
   * 삼키고 "미설정" 배지로 조용히 이어가게 바꾸면(구독 콜백 안으로 옮기는 실수 포함) 이 테스트가
   * RED 가 된다 — ai-agent 로 요청 자체가 나가지 않았음도 함께 확인한다.
   */
  @Test
  void streamChat_알수없는_유형이면_예외가_그대로_전파되고_호출이_나가지_않는다() {
    when(settingsService.getAsMap("ai")).thenReturn(Map.of("ai.model", "claude-sonnet-5"));
    when(aiCredentialService.resolve()).thenThrow(new UnknownAgentTypeException("martian"));

    SseEmitter emitter = new SseEmitter();
    assertThatThrownBy(
            () -> aiAgentProxyService.streamChat(emitter, "hi", null, List.of(), 1L, null, null))
        .isInstanceOf(UnknownAgentTypeException.class);

    wireMock.verify(0, postRequestedFor(urlEqualTo("/agent/chat")));
  }

  /**
   * 옵션 3 폐기(2026-09-19, 이슈 #693) — 이 테스트는 그 결정을 고정하던 이전 핀 테스트
   * ({@code streamChat_opencode_자격증명이면_apiKey_필드가_실리지_않는다})를 뒤집는다. ai-agent 의
   * {@code buildOpenCodeConfig} 가 이제 provider 블록(baseURL/apiKey)을 요청 바디로 직접 받아
   * 조립하므로, {@code Opencode.apiKey}(OpenAI 호환 키)를 더 이상 숨기지 않고 {@code apiKey} 로
   * 싣는다 — Anthropic 용 필드가 아니라 이 요청 자체가 opencode 전용이기 때문이다.
   */
  @Test
  void streamChat_opencode_자격증명이면_providerId_baseUrl_apiKey_reasoningEffort가_모두_실린다() {
    when(settingsService.getAsMap("ai")).thenReturn(Map.of("ai.model", "openai/gpt-4o"));
    when(aiCredentialService.resolve())
        .thenReturn(
            new AiCredential.Opencode(
                "openai", "https://api.openai.com/v1", "medium", "sk-oai-secret"));
    wireMock.stubFor(
        post(urlEqualTo("/agent/chat"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody("event: done\ndata: {}\n\n")));

    SseEmitter emitter = new SseEmitter();
    aiAgentProxyService.streamChat(emitter, "hi", null, List.of(), 1L, null, null);

    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () ->
                wireMock.verify(
                    postRequestedFor(urlEqualTo("/agent/chat"))
                        .withRequestBody(matchingJsonPath("$.agentType", equalTo("opencode")))
                        .withRequestBody(matchingJsonPath("$.providerId", equalTo("openai")))
                        .withRequestBody(
                            matchingJsonPath("$.baseUrl", equalTo("https://api.openai.com/v1")))
                        .withRequestBody(matchingJsonPath("$.apiKey", equalTo("sk-oai-secret")))
                        .withRequestBody(
                            matchingJsonPath("$.reasoningEffort", equalTo("medium")))));
  }

  /**
   * reasoningEffort 가 빈 문자열이면 "설정 안 함" 이므로 필드 자체를 생략해야 한다 — 그대로 실어
   * 보내면 ai-agent 의 buildOpenCodeConfig 가 opencode 에 빈 문자열을 그대로 전달해 400 이 된다.
   */
  @Test
  void streamChat_opencode_추론강도가_비어있으면_reasoningEffort_필드를_생략한다() {
    when(settingsService.getAsMap("ai")).thenReturn(Map.of("ai.model", "openai/gpt-4o"));
    when(aiCredentialService.resolve())
        .thenReturn(
            new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk-oai"));
    wireMock.stubFor(
        post(urlEqualTo("/agent/chat"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody("event: done\ndata: {}\n\n")));

    SseEmitter emitter = new SseEmitter();
    aiAgentProxyService.streamChat(emitter, "hi", null, List.of(), 1L, null, null);

    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(
            () ->
                wireMock.verify(
                    postRequestedFor(urlEqualTo("/agent/chat"))
                        .withRequestBody(notMatching(".*reasoningEffort.*"))));
  }

  /**
   * providerId/baseUrl 이 비어 있으면(손상된 행, 마이그레이션 이전 등) 옵션 3 처럼 조용히 통과시켜
   * ai-agent 로 보내지 않는다 — SSE 로 사용자에게 보이는 오류로 끝내야 한다(다른 세 유형과 같은
   * fail-closed, missingCredential 가드).
   */
  @Test
  void streamChat_opencode_provider설정이_불완전하면_에러이벤트로_끝내고_호출이_나가지_않는다() {
    when(settingsService.getAsMap("ai")).thenReturn(Map.of("ai.model", "openai/gpt-4o"));
    when(aiCredentialService.resolve())
        .thenReturn(new AiCredential.Opencode("", "", "", ""));

    SseEmitter emitter = new SseEmitter();
    aiAgentProxyService.streamChat(emitter, "hi", null, List.of(), 1L, null, null);

    wireMock.verify(0, postRequestedFor(urlEqualTo("/agent/chat")));
  }

  /**
   * (리뷰 라운드 1 지적, 항목 3) opencode 인데 ai.model 이 opencode 형식(providerId/modelId)이
   * 아니면(예: sdk 시절 값 "claude-sonnet-5" 가 그대로 남아 opencode 로 전환한 경우 —
   * OpencodeCredentialValidation.checkProviderConsistency 가 저장 시 이런 값을 순환 잠금 회피
   * 목적으로 허용한다) ai-agent 의 buildOpenCodeConfig 가 "providerId/modelId 형식이어야
   * 합니다" 로 throw 한다 — 그 시점은 SSE 헤더가 이미 나간 뒤라 사용자는 구체적 원인 없는
   * "Agent 처리 중 오류가 발생했습니다" 만 본다. 이 테스트는 그 요청 자체가 ai-agent 로 나가지
   * 않고 여기서 먼저 명확한 오류로 끝나는지 확인한다(proactive 경로의 model 배선과 같은 이유로
   * chat 경로도 닫는다).
   */
  @Test
  void streamChat_opencode_모델형식이_슬래시가_없으면_에러이벤트로_끝내고_호출이_나가지_않는다()
      throws IOException {
    when(settingsService.getAsMap("ai")).thenReturn(Map.of("ai.model", "claude-sonnet-5"));
    when(aiCredentialService.resolve())
        .thenReturn(
            new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk-oai"));
    // 이 요청이 실제로 나가면 받아줄 스텁 — 가드가 사라진 뮤턴트에서도 무관한 응답을 받게 해,
    // 아래 단언이 "가드가 막았는가"만 보게 한다(스텁 부재로 인한 별개의 실패와 섞이지 않도록).
    wireMock.stubFor(
        post(urlEqualTo("/agent/chat"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody("event: done\ndata: {}\n\n")));

    // (리뷰 라운드 2 지적) "호출이 안 나갔다"를 폴링 지연으로 추측하는 대신, 가드 분기가
    // emitter 에 동기적으로 보내는 신호(에러 데이터 + complete())를 직접 검증한다. 가드는
    // emitter.send(comment("connected")) 와 webClient.post() 보다 먼저 return 하므로, 이
    // 신호가 관측된다는 것 자체가 "그 이후 코드가 실행되지 않았다"는 happens-before 증거다 —
    // 별도 지연 없이도 결정적이다(가드가 실제로 비동기 디스패치를 막았으므로 경쟁이 성립하지
    // 않는다).
    SseEmitter emitter = spy(new SseEmitter());
    aiAgentProxyService.streamChat(emitter, "hi", null, List.of(), 1L, null, null);

    ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
        ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    verify(emitter).send(eventCaptor.capture());
    verify(emitter).complete();
    // SseEventBuilder.build() 는 "data:" 접두어와 실제 페이로드를 서로 다른
    // DataWithMediaType 항목으로 나눠 담을 수 있어(SseEmitter 내부 구현, 문자열에 개행이
    // 없으면 접두어가 먼저 플러시됨), 첫 항목만 보면 "data:" 리터럴만 잡힌다 — 모든 항목을
    // 이어붙여 실제 메시지가 어디 있든 포함 여부를 확인한다.
    String sentData =
        eventCaptor.getValue().build().stream()
            .map(d -> d.getData().toString())
            .reduce("", String::concat);
    assertThat(sentData).contains("claude-sonnet-5").contains("opencode 형식");
    wireMock.verify(0, postRequestedFor(urlEqualTo("/agent/chat")));
  }

  /** 슬래시는 있지만 providerId 접두사가 저장된 opencode 공급자와 다르면 마찬가지로 막는다. */
  @Test
  void streamChat_opencode_모델의_provider접두사가_다르면_에러이벤트로_끝내고_호출이_나가지_않는다()
      throws IOException {
    when(settingsService.getAsMap("ai")).thenReturn(Map.of("ai.model", "anthropic/claude-sonnet-5"));
    when(aiCredentialService.resolve())
        .thenReturn(
            new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk-oai"));
    wireMock.stubFor(
        post(urlEqualTo("/agent/chat"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody("event: done\ndata: {}\n\n")));

    // 위 테스트와 동일한 이유로 폴링 대신 동기 신호(에러 데이터 + complete())를 직접 검증한다.
    SseEmitter emitter = spy(new SseEmitter());
    aiAgentProxyService.streamChat(emitter, "hi", null, List.of(), 1L, null, null);

    ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
        ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    verify(emitter).send(eventCaptor.capture());
    verify(emitter).complete();
    // SseEventBuilder.build() 는 "data:" 접두어와 실제 페이로드를 서로 다른
    // DataWithMediaType 항목으로 나눠 담을 수 있어(SseEmitter 내부 구현, 문자열에 개행이
    // 없으면 접두어가 먼저 플러시됨), 첫 항목만 보면 "data:" 리터럴만 잡힌다 — 모든 항목을
    // 이어붙여 실제 메시지가 어디 있든 포함 여부를 확인한다.
    String sentData =
        eventCaptor.getValue().build().stream()
            .map(d -> d.getData().toString())
            .reduce("", String::concat);
    assertThat(sentData).contains("anthropic/claude-sonnet-5").contains("opencode 형식");
    wireMock.verify(0, postRequestedFor(urlEqualTo("/agent/chat")));
  }

  /**
   * 세션 이력 조회가 테넌트를 쿼리 파라미터로 실어 보내는지 확인한다.
   *
   * <p>ai-agent 는 트랜스크립트를 테넌트별 디렉터리에 저장하므로 이 값이 없으면 400 이고, 값이
   * 틀리면 남의 테넌트 디렉터리를 뒤진다 — 경로 파생 입력이라 URL 에 실렸는지 자체가 계약이다.
   */
  @Test
  void getSessionHistory_sendsTenantIdAsQueryParam() {
    wireMock.stubFor(
        get(urlPathEqualTo("/agent/history/sess-1"))
            .willReturn(aResponse().withStatus(200).withBody("[]")));

    String body = aiAgentProxyService.getSessionHistory("sess-1");

    assertThat(body).isEqualTo("[]");
    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/agent/history/sess-1"))
            .withQueryParam("tenantId", equalTo(String.valueOf(DEFAULT_TEST_TENANT_ID))));
  }

  /**
   * 테넌트 컨텍스트가 없으면 이력 조회 자체가 실패해야 한다(fail-closed).
   *
   * <p>여기서 조용히 진행하면 ai-agent 가 어느 테넌트의 디렉터리를 볼지 알 수 없는 상태로 호출을
   * 받게 된다. {@code IntegrationTestBase} 가 매 테스트마다 기본 테넌트를 심으므로, 이 테스트는
   * 그것을 명시적으로 비워 운영의 "필터를 안 거친 경로" 상태를 재현한다.
   */
  @Test
  void getSessionHistory_withoutTenantContext_failsClosed() {
    TenantContext.clear();

    assertThatThrownBy(() -> aiAgentProxyService.getSessionHistory("sess-1"))
        .isInstanceOf(MissingTenantScopeException.class);
    // ai-agent 로 요청이 나가지 않았음을 함께 확인한다 — 던지기만 하고 이미 호출했다면 의미가 없다.
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/agent/history/sess-1")));
  }

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
