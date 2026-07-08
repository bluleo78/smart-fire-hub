package com.smartfirehub.ai.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.settings.service.SettingsService;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

  /**
   * sdk 모드 + OAuth 토큰 설정 시(API 키는 없음) ai-agent로 전송되는 요청 body에 {@code oauthToken}이 포함되고, 더 이상
   * 사용하지 않는 {@code cliOauthToken} 키는 포함되지 않아야 한다. (Task 1: ai-agent가 body oauthToken을 읽도록 변경됨에
   * 맞춰 firehub-api 프록시도 동일 키로 전달해야 함)
   */
  @Test
  void streamChat_sdkWithOauthToken_injectsOauthTokenIntoBody() {
    // given: agent_type=sdk, OAuth 토큰 설정, API 키는 없음
    when(settingsService.getAsMap("ai"))
        .thenReturn(Map.of("ai.agent_type", "sdk", "ai.model", "claude-sonnet-5"));
    when(settingsService.getDecryptedCliOauthToken()).thenReturn(Optional.of("oat-test"));
    when(settingsService.getDecryptedApiKey()).thenReturn(Optional.empty());
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
                        .withRequestBody(notMatching(".*cliOauthToken.*"))));
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
