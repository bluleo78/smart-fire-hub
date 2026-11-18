package com.smartfirehub.global.config;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 자동 구성 {@link WebClient.Builder} 의 리다이렉트 기본값 고정 시험(WD-791).
 *
 * <p>Boot 3.4.1 의 주입 빌더는 3xx 를 따라가지 않았다(Reactor Netty 기본값). Boot 3.5 부터는 리다이렉트 설정(Boot 4: {@code
 * spring.http.clients.redirects}, 3.5: {@code spring.http.reactiveclient.redirects})이 비면
 * FOLLOW_WHEN_POSSIBLE 로 채워 따라간다(WD-792 에서 Boot 4.1 에서도 같음을 이 시험으로 확인). 주입 빌더를 쓰는 임베딩 호출이 SSRF 가드가
 * 검사한 baseUrl 밖으로 리다이렉트를 따라 나가면 안 되므로 {@code application.yml} 이 {@code dont-follow} 로 되돌린다. 이 시험은
 * 실제 {@code application.yml} 을 읽어 그 설정이 살아 있는지, 그리고 설정이 없으면 Boot 가 정말 따라가는지(설정이 왜 필요한지)를 함께 확인한다.
 */
class WebClientRedirectDefaultsTest {

  private MockWebServer server;

  /** WebClient 자동 구성만 띄운다 — 빌더·커넥터가 Boot 가 만드는 그대로다. */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  HttpClientAutoConfiguration.class,
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

  /** /start 가 /target 으로 302 를 돌려주게 하고, 그 뒤 요청이 오면 200 을 준다. */
  private void enqueueRedirect() {
    server.enqueue(
        new MockResponse().setResponseCode(302).setHeader("Location", server.url("/target")));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("followed"));
  }

  /** 주입 빌더로 /start 를 부르고 받은 상태 코드를 돌려준다. */
  private static HttpStatusCode call(WebClient.Builder builder, String url) {
    return builder
        .build()
        .get()
        .uri(url)
        .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode()))
        .block();
  }

  @Test
  void applicationYml_설정으로_주입된_WebClient_빌더는_302를_따라가지_않는다() {
    enqueueRedirect();
    // ConfigDataApplicationContextInitializer 가 실제 application.yml 을 읽는다.
    runner
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            ctx -> {
              HttpStatusCode status =
                  call(ctx.getBean(WebClient.Builder.class), server.url("/start").toString());

              // 3xx 를 그대로 돌려받고, 리다이렉트 대상(/target)은 한 번도 요청되지 않는다.
              assertThat(status.value()).isEqualTo(302);
              assertThat(server.getRequestCount()).isEqualTo(1);
              RecordedRequest first = server.takeRequest(1, TimeUnit.SECONDS);
              assertThat(first).isNotNull();
              assertThat(first.getPath()).isEqualTo("/start");
            });
  }

  @Test
  void 설정이_없으면_Boot_기본값은_302를_따라간다() {
    // 설정 파일 없이 띄우면 Boot 기본값(3.5·4.1 모두 FOLLOW_WHEN_POSSIBLE)이 된다 — application.yml 설정이 필요한 이유.
    enqueueRedirect();
    runner.run(
        ctx -> {
          HttpStatusCode status =
              call(ctx.getBean(WebClient.Builder.class), server.url("/start").toString());

          assertThat(status.value()).isEqualTo(200);
          assertThat(server.getRequestCount()).isEqualTo(2);
        });
  }
}
