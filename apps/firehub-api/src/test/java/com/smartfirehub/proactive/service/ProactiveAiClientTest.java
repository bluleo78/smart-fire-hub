package com.smartfirehub.proactive.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
 * ProactiveAiClient 단위 기능 테스트. WireMock으로 ai-agent 서비스를 스텁하여 execute() 호출 시 HTTP body에
 * OAuth 토큰이 올바른 키(oauthToken)로 전달되는지 검증한다 (구 cliOauthToken 키 리네임 회귀 방지).
 */
class ProactiveAiClientTest extends IntegrationTestBase {

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

  @Autowired private ProactiveAiClient client;

  @Test
  void execute_withOauthToken_putsOauthTokenInBody() {
    // given: ai-agent의 /agent/proactive 엔드포인트를 스텁
    wireMock.stubFor(
        post(urlEqualTo("/agent/proactive")).willReturn(okJson("{\"sections\":[]}")));

    // when: sdk 에이전트 타입으로 OAuth 토큰과 함께 호출
    client.execute(1L, "prompt", "{}", "sk-x", "sdk", "oat-test", null, Map.of());

    // then: 요청 body에 oauthToken 키로 토큰이 전달되어야 한다 (구 cliOauthToken 키는 더 이상 사용하지 않음)
    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/proactive"))
            .withRequestBody(matchingJsonPath("$.oauthToken", equalTo("oat-test"))));
  }
}
