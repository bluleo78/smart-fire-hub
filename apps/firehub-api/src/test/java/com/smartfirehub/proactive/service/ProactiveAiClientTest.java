package com.smartfirehub.proactive.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.proactive.dto.ProactiveResult;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ProactiveAiClient 단위 기능 테스트. WireMock으로 ai-agent 서비스를 스텁하여 execute() 호출 시 HTTP body에 OAuth 토큰이
 * 올바른 키(oauthToken)로 전달되는지 검증한다 (구 cliOauthToken 키 리네임 회귀 방지).
 */
class ProactiveAiClientTest extends IntegrationTestBase {

  // WireMock 서버를 정적 필드에서 즉시 시작한다: @DynamicPropertySource는 Spring 컨텍스트 준비(빈 생성) 이전에
  // 호출되므로, 그 시점에 이미 포트가 결정되어 있어야 agent.url 프로퍼티를 WireMock 주소로 오버라이드할 수 있다.
  static WireMockServer wireMock =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

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

  @Autowired private ProactiveAiClient client;

  @Test
  void execute_withOauthToken_putsOauthTokenInBody() {
    // given: ai-agent의 /agent/proactive 엔드포인트를 스텁
    wireMock.stubFor(post(urlEqualTo("/agent/proactive")).willReturn(okJson("{\"sections\":[]}")));

    // when: sdk 에이전트 타입으로 OAuth 토큰과 함께 호출
    client.execute(
        1L, "prompt", "{}", new AiCredential.Sdk("oat-test", "sk-x"), null, null, Map.of(), null);

    // then: 요청 body에 oauthToken 키로 토큰이 전달되어야 한다 (구 cliOauthToken 키는 더 이상 사용하지 않음)
    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/proactive"))
            .withRequestBody(matchingJsonPath("$.oauthToken", equalTo("oat-test"))));
  }

  /**
   * 스펙 §4.3 Proactive 행: 리포트는 발송(공유)되므로 바디에 aiPurpose=share 를 싣는다 — ai-agent 가 이 값을 읽어 실행 중 MCP 호출에
   * X-AI-Purpose: share 를 붙여야 API 가 SHARE 정책까지 건다. 빠지면 공유 금지 등급 데이터가 리포트로 나간다.
   */
  @Test
  void execute_바디에_공유목적_aiPurpose_share를_싣는다() {
    wireMock.stubFor(post(urlEqualTo("/agent/proactive")).willReturn(okJson("{\"sections\":[]}")));

    client.execute(
        1L, "prompt", "{}", new AiCredential.Sdk("oat-test", "sk-x"), null, null, Map.of(), null);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/proactive"))
            .withRequestBody(matchingJsonPath("$.aiPurpose", equalTo("share"))));
  }

  /**
   * WD-198: 실행 id 를 바디에 싣는다 — ai-agent 가 이 값을 MCP 호출의 X-Proactive-Execution-Id 로 되돌려 보내야 api 가 실행 중
   * 읽은 데이터셋 등급을 리포트에 남긴다. 실행 id 가 없으면(null) 키 자체를 싣지 않는다.
   */
  @Test
  void execute_실행id를_바디에_proactiveExecutionId로_싣는다() {
    wireMock.stubFor(post(urlEqualTo("/agent/proactive")).willReturn(okJson("{\"sections\":[]}")));

    client.execute(
        1L, "prompt", "{}", new AiCredential.Sdk("oat-test", "sk-x"), null, null, Map.of(), 77L);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/proactive"))
            .withRequestBody(matchingJsonPath("$.proactiveExecutionId", equalTo("77"))));
  }

  /**
   * 옵션 3 폐기(2026-09-19, 이슈 #693, Ruling #32) — proactive 바디에도 채팅과 동일하게
   * providerId/baseUrl/model/reasoningEffort 가 실려야 ai-agent 의 buildOpenCodeConfig 가 provider 블록을
   * 조립할 수 있다(model 은 top-level 필수 필드 — execute() 의 model 파라미터 javadoc 참고).
   */
  @Test
  void execute_opencode면_providerId_baseUrl_model_reasoningEffort가_바디에_실린다() {
    wireMock.stubFor(post(urlEqualTo("/agent/proactive")).willReturn(okJson("{\"sections\":[]}")));

    client.execute(
        1L,
        "prompt",
        "{}",
        new AiCredential.Opencode("openai", "https://api.openai.com/v1", "medium", "sk-oai-secret"),
        "openai/gpt-4o",
        null,
        Map.of(),
        null);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/proactive"))
            .withRequestBody(matchingJsonPath("$.agentType", equalTo("opencode")))
            .withRequestBody(matchingJsonPath("$.apiKey", equalTo("sk-oai-secret")))
            .withRequestBody(matchingJsonPath("$.providerId", equalTo("openai")))
            .withRequestBody(matchingJsonPath("$.baseUrl", equalTo("https://api.openai.com/v1")))
            .withRequestBody(matchingJsonPath("$.model", equalTo("openai/gpt-4o")))
            .withRequestBody(matchingJsonPath("$.reasoningEffort", equalTo("medium"))));
  }

  /** 추론 강도가 빈 값이면 "설정 안 함"이므로 필드 자체를 생략한다(그대로 보내면 opencode 가 400). */
  @Test
  void execute_추론강도가_비어있으면_reasoningEffort_필드를_생략한다() {
    wireMock.stubFor(post(urlEqualTo("/agent/proactive")).willReturn(okJson("{\"sections\":[]}")));

    client.execute(
        1L,
        "prompt",
        "{}",
        new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk-oai"),
        "openai/gpt-4o",
        null,
        Map.of(),
        null);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/proactive"))
            .withRequestBody(notMatching(".*reasoningEffort.*")));
  }

  /** model 이 빈 값이면 필드 자체를 생략한다 — ai-agent 라우트의 폴백이 그대로 살아 있게 둔다. */
  @Test
  void execute_model이_비어있으면_model_필드를_생략한다() {
    wireMock.stubFor(post(urlEqualTo("/agent/proactive")).willReturn(okJson("{\"sections\":[]}")));

    client.execute(
        1L,
        "prompt",
        "{}",
        new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk-oai"),
        null,
        null,
        Map.of(),
        null);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/proactive")).withRequestBody(notMatching(".*model.*")));
  }

  /** opencode 가 아닌 유형이면 provider 전용 네 필드를 모두 생략한다 — sdk/cli/cli-api 호출부. */
  @Test
  void execute_opencode가_아니면_providerId_baseUrl_model_reasoningEffort를_생략한다() {
    wireMock.stubFor(post(urlEqualTo("/agent/proactive")).willReturn(okJson("{\"sections\":[]}")));

    client.execute(
        1L, "prompt", "{}", new AiCredential.Sdk("oat-test", "sk-x"), null, null, Map.of(), null);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/proactive"))
            .withRequestBody(notMatching(".*providerId.*"))
            .withRequestBody(notMatching(".*baseUrl.*"))
            .withRequestBody(notMatching(".*reasoningEffort.*")));
  }

  /**
   * WD-765: 위임 경로에서 리포트 파일이 저장되지 않아 ai-agent 가 502 + AGENT_REPORT_NOT_SAVED 로 답하면, 「AI 에이전트 호출 실패」가
   * 아니라 저장 실패 문구로 안내한다 — 진짜 원인을 말해야 사용자가 인증 정보를 헛되이 고치지 않는다.
   */
  @Test
  void execute_502_리포트_미저장_코드면_저장_실패_문구로_안내한다() {
    wireMock.stubFor(
        post(urlEqualTo("/agent/proactive"))
            .willReturn(
                aResponse()
                    .withStatus(502)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"error\":\"Agent produced no usable report\",\"code\":\"AGENT_REPORT_NOT_SAVED\"}")));

    assertThatThrownBy(
            () ->
                client.execute(
                    1L,
                    "prompt",
                    "{}",
                    new AiCredential.Sdk("oat-test", "sk-x"),
                    null,
                    null,
                    Map.of(),
                    null))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessage(ProactiveResultValidator.REPORT_NOT_SAVED_MESSAGE);
  }

  /** WD-765 회귀 방지: 진짜 인증·한도 실패(AGENT_AUTH_OR_QUOTA_FAILURE)는 기존 「AI 인증 정보 확인」 문구 그대로다. */
  @Test
  void execute_502_인증_한도_코드면_기존_인증_문구로_안내한다() {
    wireMock.stubFor(
        post(urlEqualTo("/agent/proactive"))
            .willReturn(
                aResponse()
                    .withStatus(502)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"error\":\"Agent produced no usable report\",\"code\":\"AGENT_AUTH_OR_QUOTA_FAILURE\"}")));

    assertThatThrownBy(
            () ->
                client.execute(
                    1L,
                    "prompt",
                    "{}",
                    new AiCredential.Sdk("oat-test", "sk-x"),
                    null,
                    null,
                    Map.of(),
                    null))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessage(ProactiveResultValidator.USER_FACING_FAILURE_MESSAGE);
  }

  /** WD-767: 응답의 numberCheck 가 ProactiveResult 로 그대로 매핑된다. */
  @Test
  void execute_응답의_numberCheck를_결과에_매핑한다() {
    wireMock.stubFor(
        post(urlEqualTo("/agent/proactive"))
            .willReturn(
                okJson(
                    "{\"sections\":[],\"numberCheck\":{\"checked\":true,"
                        + "\"unverified\":[{\"text\":\"3.5억\",\"context\":\"매출 3.5억\"}],"
                        + "\"unverifiedTotal\":3}}")));

    ProactiveResult result =
        client.execute(
            1L,
            "prompt",
            "{}",
            new AiCredential.Sdk("oat-test", "sk-x"),
            null,
            null,
            Map.of(),
            null);

    assertThat(result.numberCheck()).isNotNull();
    assertThat(result.numberCheck().checked()).isTrue();
    assertThat(result.numberCheck().unverifiedTotal()).isEqualTo(3);
    assertThat(result.numberCheck().unverified())
        .containsExactly(new ProactiveResult.UnverifiedNumber("3.5억", "매출 3.5억"));
  }

  /** 이전 응답(필드 없음)이나 형이 다른 값은 null 로 받아 실행이 깨지지 않는다. */
  @Test
  void execute_numberCheck가_없거나_형이_다르면_null() {
    wireMock.stubFor(post(urlEqualTo("/agent/proactive")).willReturn(okJson("{\"sections\":[]}")));
    ProactiveResult none =
        client.execute(
            1L,
            "prompt",
            "{}",
            new AiCredential.Sdk("oat-test", "sk-x"),
            null,
            null,
            Map.of(),
            null);
    assertThat(none.numberCheck()).isNull();

    wireMock.stubFor(
        post(urlEqualTo("/agent/proactive"))
            .willReturn(okJson("{\"sections\":[],\"numberCheck\":\"bad\"}")));
    ProactiveResult bad =
        client.execute(
            1L,
            "prompt",
            "{}",
            new AiCredential.Sdk("oat-test", "sk-x"),
            null,
            null,
            Map.of(),
            null);
    assertThat(bad.numberCheck()).isNull();
  }
}
