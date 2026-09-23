package com.smartfirehub.ai.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.ai.service.AiAgentBatchClient.AgentChatException;
import com.smartfirehub.ai.service.AiAgentBatchClient.ChatReply;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Slack 인바운드용 ai-agent 클라이언트 테스트(이슈 #709).
 *
 * <p>WireMock 이 ai-agent 의 실제 계약({@code POST /agent/chat}, SSE 응답, {@code Internal} 인증)을
 * 흉내 낸다. 실제 빈 배선({@code agent.url} 을 읽는지 포함)으로 확인하려고 Spring 컨텍스트에서 돈다.
 */
class AiAgentBatchClientTest extends IntegrationTestBase {

  // @DynamicPropertySource 가 빈 생성 전에 불리므로 포트가 그 전에 정해져 있어야 한다.
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

  @DynamicPropertySource
  static void overrideAgentUrl(DynamicPropertyRegistry registry) {
    registry.add("agent.url", () -> "http://localhost:" + wireMock.port());
  }

  @Autowired private AiAgentBatchClient client;

  private static final Map<String, Object> BODY =
      Map.of(
          "message", "hi", "sessionId", "", "userId", 42, "tenantId", 7, "agentType", "sdk",
          "apiKey", "sk-test");

  /** ai-agent 의 SSE 형식 그대로 — 각 이벤트는 {@code data: {json}} 한 줄과 빈 줄. */
  private static String sse(String... dataLines) {
    StringBuilder sb = new StringBuilder();
    for (String d : dataLines) sb.append("data: ").append(d).append("\n\n");
    return sb.toString();
  }

  private void stubChat(String body) {
    wireMock.stubFor(
        post(urlEqualTo("/agent/chat"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody(body)));
  }

  @Test
  @DisplayName("SSE 를 done 까지 읽어 text 를 이어 붙이고 발급된 세션 ID 를 돌려준다")
  void chat_collectsTextUntilDone() {
    stubChat(
        sse(
            "{\"type\":\"init\",\"sessionId\":\"s-1\"}",
            "{\"type\":\"text\",\"content\":\"안녕\"}",
            "{\"type\":\"tool_use\",\"toolName\":\"x\"}",
            "{\"type\":\"text\",\"content\":\"하세요\"}",
            "{\"type\":\"done\",\"sessionId\":\"s-1\",\"inputTokens\":1,\"outputTokens\":2}"));

    ChatReply reply = client.chat(BODY);

    assertThat(reply.sessionId()).isEqualTo("s-1");
    // 웹 화면과 같이 구분자 없이 이어 붙인다
    assertThat(reply.text()).isEqualTo("안녕하세요");
    // 내부 인증 헤더와 바디(자격증명 포함)가 그대로 실린다
    wireMock.verify(
        postRequestedFor(urlEqualTo("/agent/chat"))
            .withHeader("Authorization", equalTo("Internal test-internal-token"))
            .withHeader("Accept", containing("text/event-stream"))
            .withRequestBody(matchingJsonPath("$.sessionId", equalTo("")))
            .withRequestBody(matchingJsonPath("$.tenantId", equalTo("7")))
            .withRequestBody(matchingJsonPath("$.agentType", equalTo("sdk")))
            .withRequestBody(matchingJsonPath("$.apiKey", equalTo("sk-test"))));
  }

  @Test
  @DisplayName("done 에서 구독을 끊는다 — 그 뒤 이벤트는 답에 섞이지 않는다")
  void chat_stopsAtDone() {
    stubChat(
        sse(
            "{\"type\":\"init\",\"sessionId\":\"s-1\"}",
            "{\"type\":\"text\",\"content\":\"답\"}",
            "{\"type\":\"done\"}",
            "{\"type\":\"text\",\"content\":\"꼬리\"}"));

    ChatReply reply = client.chat(BODY);

    assertThat(reply.text()).isEqualTo("답");
    // done 에 sessionId 가 없으면(CLI 경로) init 의 값을 쓴다
    assertThat(reply.sessionId()).isEqualTo("s-1");
  }

  @Test
  @DisplayName("error 이벤트면 그 문구로 AgentChatException")
  void chat_errorEvent_throwsWithAgentMessage() {
    stubChat(
        sse(
            "{\"type\":\"init\",\"sessionId\":\"s-1\"}",
            "{\"type\":\"error\",\"message\":\"AI 인증에 실패했습니다.\"}"));

    assertThatThrownBy(() -> client.chat(BODY))
        .isInstanceOf(AgentChatException.class)
        .hasMessage("AI 인증에 실패했습니다.");
  }

  @Test
  @DisplayName("done 없이 끝난 스트림은 일부 답을 성공으로 보지 않는다")
  void chat_streamWithoutDone_fails() {
    stubChat(sse("{\"type\":\"text\",\"content\":\"부분\"}"));

    assertThatThrownBy(() -> client.chat(BODY))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(AgentChatException.class);
  }

  @Test
  @DisplayName("비 2xx 응답(예: 내부 토큰 불일치 401)은 예외")
  void chat_non2xx_fails() {
    wireMock.stubFor(
        post(urlEqualTo("/agent/chat"))
            .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"Unauthorized\"}")));

    assertThatThrownBy(() -> client.chat(BODY))
        .isNotInstanceOf(AgentChatException.class)
        .hasMessageContaining("401");
  }
}
