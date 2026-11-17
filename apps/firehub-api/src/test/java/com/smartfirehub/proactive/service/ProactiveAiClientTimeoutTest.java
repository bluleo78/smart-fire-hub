package com.smartfirehub.proactive.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * WD-766 — 프로액티브 AI 호출의 제한 시간(설정 {@code app.proactive.agent-timeout}, 기본 15분)과 「시간 초과」 문구.
 *
 * <p>시간 초과는 별도 문구로 알리고 연결을 닫아 ai-agent 도 멈추게 한다. 그 밖의 실패(연결 거부·5xx)는 기존 일반 문구 그대로다.
 *
 * <p>Spring 컨텍스트 없이 클라이언트를 직접 만들고(운영과 같은 생성자에 짧은 제한 시간), WireMock 의 지연 응답으로 실제 시간 초과를 일으킨다.
 */
class ProactiveAiClientTimeoutTest {

  static WireMockServer wireMock =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

  @BeforeAll
  static void start() {
    wireMock.start();
  }

  @AfterAll
  static void stop() {
    wireMock.stop();
  }

  @BeforeEach
  void tenant() {
    TenantContext.set(5L);
  }

  @AfterEach
  void clear() {
    TenantContext.clear();
    wireMock.resetAll();
  }

  /**
   * 지정한 주소·제한 시간으로 클라이언트를 만든다(저장소 판정은 목). 10초 하한(WD-778)은 빈 초기화({@code @PostConstruct})에서만 보므로 생성자로
   * 짧은 제한 시간을 그대로 쓴다.
   */
  private static ProactiveAiClient clientFor(String baseUrl, Duration timeout) {
    DataStoreResolver resolver = mock(DataStoreResolver.class);
    when(resolver.backendFor(5L)).thenReturn(DataStoreBackend.POSTGRESQL);
    VectorStoreFeatures features = mock(VectorStoreFeatures.class);
    ProactiveAiClient client =
        new ProactiveAiClient(baseUrl, new ObjectMapper(), resolver, features, timeout);
    ReflectionTestUtils.setField(client, "internalToken", "t");
    return client;
  }

  private static ProactiveJobException callAndCatch(ProactiveAiClient client) {
    return catchThrowableOfType(
        ProactiveJobException.class,
        () ->
            client.execute(
                1L,
                "prompt",
                "{}",
                new AiCredential.Sdk("", "sk-x"),
                "claude-sonnet-5",
                null,
                Map.of(),
                null));
  }

  /** 응답이 제한 시간을 넘기면 「시간 초과」 문구로 끝난다 — 일반 호출 실패 문구가 아니다. */
  @Test
  void 응답이_제한_시간을_넘기면_시간_초과_문구로_실패한다() {
    wireMock.stubFor(
        post(urlEqualTo("/agent/proactive"))
            .willReturn(okJson("{\"sections\":[]}").withFixedDelay(1_000)));
    Duration limit = Duration.ofMillis(300);

    ProactiveJobException e = callAndCatch(clientFor("http://localhost:" + wireMock.port(), limit));

    assertThat(e).isNotNull();
    assertThat(e.getMessage()).isEqualTo(ProactiveAiClient.timeoutMessage(limit));
  }

  /**
   * 시간 초과로 구독이 취소되면 ai-agent 쪽 연결이 닫힌다 — ai-agent 는 이 close 를 보고 실행을 멈춘다(proactive.ts). 응답하지 않는 서버
   * 소켓이 요청을 다 읽은 뒤 EOF(또는 reset)를 받는지 본다.
   */
  @Test
  void 시간_초과면_ai_agent_쪽_연결이_닫힌다() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      CompletableFuture<Boolean> closedByClient =
          CompletableFuture.supplyAsync(
              () -> {
                try (Socket socket = server.accept()) {
                  socket.setSoTimeout(5_000);
                  InputStream in = socket.getInputStream();
                  byte[] buf = new byte[8192];
                  while (in.read(buf) >= 0) {
                    // 요청을 읽고 응답하지 않는다 — -1(EOF)이면 클라이언트가 닫은 것이다.
                  }
                  return true;
                } catch (SocketTimeoutException timedOut) {
                  return false; // 5초 동안 열린 채였다.
                } catch (IOException reset) {
                  return true; // reset 도 클라이언트가 닫은 것이다.
                }
              });
      Duration limit = Duration.ofMillis(300);

      ProactiveJobException e =
          callAndCatch(clientFor("http://localhost:" + server.getLocalPort(), limit));

      assertThat(e.getMessage()).isEqualTo(ProactiveAiClient.timeoutMessage(limit));
      assertThat(closedByClient.get(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** 문구의 시간은 실제 제한 시간(설정값)을 따른다 — 상수 「15분」 이 아니다. */
  @Test
  void 시간_초과_문구는_설정한_제한_시간을_따른다() {
    assertThat(ProactiveAiClient.timeoutMessage(Duration.ofMinutes(15))).contains("(15분)");
    assertThat(ProactiveAiClient.timeoutMessage(Duration.ofSeconds(30))).contains("(30초)");
  }

  /** 연결 거부는 시간 초과가 아니다 — 기존 일반 실패 문구 그대로. */
  @Test
  void 연결이_거부되면_기존_호출_실패_문구로_실패한다() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }

    ProactiveJobException e =
        callAndCatch(clientFor("http://localhost:" + closedPort, Duration.ofSeconds(5)));

    assertThat(e).isNotNull();
    assertThat(e.getMessage()).isEqualTo(ProactiveAiClient.AGENT_CALL_FAILED_MESSAGE);
  }

  /** 5xx 도 시간 초과가 아니다 — 기존 일반 실패 문구 그대로. */
  @Test
  void 에이전트가_5xx면_기존_호출_실패_문구로_실패한다() {
    wireMock.stubFor(
        post(urlEqualTo("/agent/proactive")).willReturn(aResponse().withStatus(503).withBody("x")));

    ProactiveJobException e =
        callAndCatch(clientFor("http://localhost:" + wireMock.port(), Duration.ofSeconds(5)));

    assertThat(e).isNotNull();
    assertThat(e.getMessage()).isEqualTo(ProactiveAiClient.AGENT_CALL_FAILED_MESSAGE);
  }

  /**
   * WD-778: {@code app.proactive.agent-timeout} 이 10초 미만(0·음수 포함)이면 기동이 실패한다. 0 이면 모든 리포트가 곧바로 「시간
   * 초과」로 끝나고 음수는 Reactor 가 호출 때마다 예외를 던진다 — 둘 다 기동은 성공한 채 스마트 작업 전체가 조용히 실패하므로, 설정 키와 받은 값을 문구에 담아
   * 기동 시점에 멈춘다.
   */
  @Test
  void 제한_시간이_10초_미만이면_기동이_실패한다() {
    for (String value : new String[] {"PT0S", "PT0.5S", "PT9.999S", "-PT1M"}) {
      Duration timeout = Duration.parse(value);
      // 받은 값은 Duration 표기 그대로 싣는다(음수 -PT1M 은 PT-1M 으로 찍힌다)
      assertThatThrownBy(() -> ProactiveAiClient.requireMinimumTimeout(timeout))
          .as(value)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("app.proactive.agent-timeout")
          .hasMessageContaining(timeout.toString());
    }
  }

  @Test
  void 제한_시간이_10초_이상이면_기동한다() {
    assertThatCode(() -> ProactiveAiClient.requireMinimumTimeout(Duration.ofSeconds(10)))
        .doesNotThrowAnyException();
    assertThatCode(() -> clientFor("http://localhost:1", Duration.parse("PT15M")).validateTimeout())
        .doesNotThrowAnyException();
  }
}
