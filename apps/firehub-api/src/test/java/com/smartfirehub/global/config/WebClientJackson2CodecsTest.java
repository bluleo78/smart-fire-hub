package com.smartfirehub.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfirehub.global.util.Jackson2WebClientCodecs;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.reactive.ReactiveHttpClientAutoConfiguration;
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient JSON 코덱이 Jackson 2 로 남는지 고정하는 시험(WD-792).
 *
 * <p>Boot 4·Spring 7 은 클래스패스에 Jackson 3 이 있으면 WebClient 코덱에 Jackson 3 을 먼저 쓴다. 이 앱은 Jackson 2 를
 * 유지하므로 (Jackson 3 전환은 WD-793) 두 경로를 본다 — 자동 구성 빌더는 {@code
 * spring.http.codecs.preferred-json-mapper: jackson2} (application.yml), 직접 만드는 {@code
 * WebClient.builder()} 는 {@link Jackson2WebClientCodecs}. Jackson 3 코덱이면 {@code com.fasterxml}
 * {@link JsonNode} 로 디코딩하지 못하고, 요청 본문의 {@link LocalDateTime} 이 Jackson 2 기본(배열 아님 — Spring 의
 * Jackson2ObjectMapperBuilder 가 WRITE_DATES_AS_TIMESTAMPS 를 끈다)과 다르게 나갈 수 있어 둘 다 확인한다.
 */
class WebClientJackson2CodecsTest {

  private MockWebServer server;

  /** WebClient·코덱·Jackson(2·3) 자동 구성만 띄운다 — 운영과 같은 조합으로 빌더가 만들어진다. */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  HttpClientAutoConfiguration.class,
                  JacksonAutoConfiguration.class,
                  Jackson2AutoConfiguration.class,
                  CodecsAutoConfiguration.class,
                  ReactiveHttpClientAutoConfiguration.class,
                  WebClientAutoConfiguration.class));

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  /** {"a":{"b":1}} 를 돌려주게 하고, 빌더로 날짜가 든 본문을 POST 해 Jackson 2 JsonNode 로 받는다. */
  private JsonNode roundTrip(WebClient.Builder builder) {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"a\":{\"b\":1}}"));
    return builder
        .build()
        .post()
        .uri(server.url("/echo").toString())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("at", LocalDateTime.of(2026, 11, 18, 9, 30)))
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block();
  }

  @Test
  void applicationYml_설정으로_주입된_WebClient_빌더는_Jackson2_로_읽고_쓴다() throws Exception {
    runner
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            ctx -> {
              JsonNode body = roundTrip(ctx.getBean(WebClient.Builder.class));

              assertThat(body.path("a").path("b").asInt()).isEqualTo(1);
              RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
              assertThat(request).isNotNull();
              assertThat(request.getBody().readUtf8())
                  .isEqualTo("{\"at\":\"2026-11-18T09:30:00\"}");
            });
  }

  @Test
  void 직접_만든_WebClient_빌더도_Jackson2WebClientCodecs_로_Jackson2_를_쓴다() throws Exception {
    JsonNode body = roundTrip(Jackson2WebClientCodecs.webClientBuilder());

    assertThat(body.path("a").path("b").asInt()).isEqualTo(1);
    RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(request).isNotNull();
    // Boot 3 때 정적 빌더의 기본 Jackson 2 인코더(Jackson2ObjectMapperBuilder.json())는
    // WRITE_DATES_AS_TIMESTAMPS 를 끄지 않아
    // 날짜를 배열로 썼다. 같은 모양이 나와야 Boot 3 와 같다 — Jackson 3 코덱이면 ISO 문자열로 바뀐다.
    assertThat(request.getBody().readUtf8()).isEqualTo("{\"at\":[2026,11,18,9,30]}");
  }

  @Test
  void 코덱을_정하지_않은_정적_빌더는_Jackson3_이라_Jackson2_JsonNode_를_읽지_못한다() {
    // Jackson2WebClientCodecs 가 왜 필요한지 보여 준다 — Spring 7 기본 코덱은 Jackson 3 을 먼저 고른다.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"a\":{\"b\":1}}"));
    Throwable failure =
        catchThrowable(
            () ->
                WebClient.builder()
                    .build()
                    .get()
                    .uri(server.url("/plain").toString())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block());

    assertThat(failure).isNotNull();
  }
}
