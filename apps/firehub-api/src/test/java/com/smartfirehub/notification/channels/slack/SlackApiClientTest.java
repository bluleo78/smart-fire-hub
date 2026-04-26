package com.smartfirehub.notification.channels.slack;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * SlackApiClient 단위 테스트.
 *
 * <p>WireMock으로 Slack REST API를 모킹하여 신규 메서드(reactionsAdd, postEphemeral, chatPostMessageInThread)의
 * 요청 형식과 응답 파싱을 검증한다.
 */
class SlackApiClientTest {

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

  private SlackApiClient client() {
    // 테스트용 생성자: WireMock baseUrl 주입
    WebClient wc = WebClient.builder().baseUrl("http://localhost:" + wireMock.port()).build();
    return new SlackApiClient(wc);
  }

  // -----------------------------------------------------------------------
  // reactionsAdd
  // -----------------------------------------------------------------------

  @Test
  void reactionsAdd_callsPostWithAuthorizationAndBody() {
    wireMock.stubFor(
        post(urlEqualTo("/reactions.add"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true}")));

    JsonNode result = client().reactionsAdd("bot-token", "C123", "1234567890.000100", "eyes");

    assertThat(result.get("ok").asBoolean()).isTrue();

    // Authorization 헤더 검증
    wireMock.verify(
        postRequestedFor(urlEqualTo("/reactions.add"))
            .withHeader("Authorization", equalTo("Bearer bot-token"))
            .withRequestBody(matchingJsonPath("$.channel", equalTo("C123")))
            .withRequestBody(matchingJsonPath("$.timestamp", equalTo("1234567890.000100")))
            .withRequestBody(matchingJsonPath("$.name", equalTo("eyes"))));
  }

  // -----------------------------------------------------------------------
  // postEphemeral
  // -----------------------------------------------------------------------

  @Test
  void postEphemeral_sendsUserAndChannel() {
    wireMock.stubFor(
        post(urlEqualTo("/chat.postEphemeral"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true,\"message_ts\":\"1234\"}")));

    JsonNode result = client().postEphemeral("bot-token", "C456", "U789", "안내 메시지");

    assertThat(result.get("ok").asBoolean()).isTrue();

    wireMock.verify(
        postRequestedFor(urlEqualTo("/chat.postEphemeral"))
            .withHeader("Authorization", equalTo("Bearer bot-token"))
            .withRequestBody(matchingJsonPath("$.channel", equalTo("C456")))
            .withRequestBody(matchingJsonPath("$.user", equalTo("U789")))
            .withRequestBody(matchingJsonPath("$.text", equalTo("안내 메시지"))));
  }

  // -----------------------------------------------------------------------
  // chatPostMessageInThread
  // -----------------------------------------------------------------------

  @Test
  void chatPostMessageInThread_includesThreadTs() {
    wireMock.stubFor(
        post(urlEqualTo("/chat.postMessage"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true,\"ts\":\"9999\"}")));

    JsonNode result =
        client()
            .chatPostMessageInThread(
                "bot-token", "C111", "1234567890.000200", null, "fallback text");

    assertThat(result.get("ok").asBoolean()).isTrue();

    // thread_ts가 body에 포함됐는지 검증
    wireMock.verify(
        postRequestedFor(urlEqualTo("/chat.postMessage"))
            .withHeader("Authorization", equalTo("Bearer bot-token"))
            .withRequestBody(matchingJsonPath("$.thread_ts", equalTo("1234567890.000200")))
            .withRequestBody(matchingJsonPath("$.text", equalTo("fallback text"))));
  }

  @Test
  void chatPostMessageInThread_withBlocksJson_includesBlocksNode() {
    wireMock.stubFor(
        post(urlEqualTo("/chat.postMessage"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true}")));

    String blocksJson = "[{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"hi\"}}]";

    JsonNode result =
        client().chatPostMessageInThread("bot-token", "C222", "111.222", blocksJson, "hi");

    assertThat(result.get("ok").asBoolean()).isTrue();

    // blocks 배열이 body에 포함됐는지 검증
    wireMock.verify(
        postRequestedFor(urlEqualTo("/chat.postMessage"))
            .withRequestBody(matchingJsonPath("$.blocks[0].type", equalTo("section"))));
  }

  @Test
  void chatPostMessageInThread_invalidBlocksJson_throwsIllegalArgument() {
    // 잘못된 JSON blocks → IllegalArgumentException 발생해야 함
    assertThatThrownBy(
            () -> client().chatPostMessageInThread("tok", "C", "ts", "NOT_JSON", "fallback"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blocksJson 파싱 실패");
  }
}
