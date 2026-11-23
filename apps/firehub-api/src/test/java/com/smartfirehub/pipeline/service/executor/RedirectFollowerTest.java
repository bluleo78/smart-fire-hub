package com.smartfirehub.pipeline.service.executor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;

/** RedirectFollower 단위 테스트(WD-693·WD-969). */
class RedirectFollowerTest {

  /**
   * 자동 리다이렉트를 끈 빌더는 받은 빌더의 복제본이다 — 주입받은 공유 빌더를 바꾸면 응답 크기 제한이 다른 파이프라인이 동시에 돌 때 서로의 설정(커넥터·코덱)을
   * 덮어쓴다. 호출자가 clone() 을 잊어도 안전하게 follower 안에서 복제한다.
   */
  @Test
  void withoutAutoRedirect_clonesAndNeverMutatesGivenBuilder() {
    WebClient.Builder shared = mock(WebClient.Builder.class);
    WebClient.Builder copy = mock(WebClient.Builder.class);
    when(shared.clone()).thenReturn(copy);
    when(copy.clientConnector(any())).thenReturn(copy);

    WebClient.Builder result =
        RedirectFollower.withoutAutoRedirect(shared, new SsrfSafeHttpClientFactory(address -> {}));

    assertThat(result).isSameAs(copy);
    verify(shared, never()).clientConnector(any());
    verify(copy).clientConnector(any());
  }

  /**
   * 메서드 값은 이 한 곳에서 정한다(WD-969 리뷰 후속) — 내부 실행기·미리보기 게이트·외부(Python) 실행기 전달이 같은 값을 본다. 비어 있으면 GET 으로
   * 보낸다 — 빈 값은 {@code HttpMethod.valueOf} 가 그대로 통과시켜 빈 이름 요청을 만들고, 그 오류는 재시도 대상이라 같은 잘못을 반복한다.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void resolveMethod_blankBecomesGet(String blank) {
    assertThat(RedirectFollower.resolveMethod(blank)).isEqualTo(HttpMethod.GET);
  }

  /** 표준 메서드는 표기만 다를 뿐 그대로 쓴다 — 소문자·앞뒤 공백을 받으면 표준 표기로 바꾼다. */
  @Test
  void resolveMethod_normalizesCaseAndPadding() {
    assertThat(RedirectFollower.resolveMethod(" put ")).isEqualTo(HttpMethod.PUT);
    assertThat(RedirectFollower.resolveMethod("delete")).isEqualTo(HttpMethod.DELETE);
  }

  /** 8인자 bodilessGet 은 넘긴 홉 검사를 그대로 싣고, 7인자는 null 이다(WD-998). */
  @Test
  void bodilessGet_withHopCheck_passesCheckToHops() {
    WebClient client = WebClient.builder().build();
    SsrfProtectionService ssrf = mock(SsrfProtectionService.class);
    URI uri = URI.create("http://example.com/x");
    java.util.function.BiConsumer<String, URI> check = (m, u) -> {};

    RedirectFollower.Request with =
        RedirectFollower.Request.bodilessGet(client, ssrf, uri, null, 1000, false, false, check);
    RedirectFollower.Request without =
        RedirectFollower.Request.bodilessGet(client, ssrf, uri, null, 1000, false, false);

    assertThat(with.hopCheck()).isSameAs(check);
    assertThat(with.method()).isEqualTo("GET");
    assertThat(without.hopCheck()).isNull();
  }

  /**
   * 홉 검사(hopCheck)가 예외를 던지면 그 홉은 따라가지 않는다(WD-970) — 대상 주소로 요청이 한 번도 나가지 않고, 예외가 그대로 나온다. 검사는 정해진 다음
   * 메서드와 해석된 절대 주소를 받는다.
   */
  @Test
  void follow_hopCheckThrows_doesNotSendNextHop() {
    WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    try {
      server.stubFor(
          get(urlPathEqualTo("/r"))
              .willReturn(aResponse().withStatus(302).withHeader("Location", "/next?x=1")));
      List<String> seen = new ArrayList<>();
      RedirectFollower.Request req =
          new RedirectFollower.Request(
              RedirectFollower.withoutAutoRedirect(
                      WebClient.builder(), new SsrfSafeHttpClientFactory(address -> {}))
                  .build(),
              mock(SsrfProtectionService.class),
              "GET",
              URI.create("http://localhost:" + server.port() + "/r"),
              null,
              null,
              ApiCallAuth.resolve(Map.of("authType", "NONE")),
              5000,
              false,
              true,
              (nextMethod, target) -> {
                seen.add(nextMethod + " " + target.getPath());
                throw new ApiCallException("blocked-by-hop-check");
              });

      assertThatThrownBy(() -> RedirectFollower.follow(req))
          .isInstanceOf(ApiCallException.class)
          .hasMessage("blocked-by-hop-check");
      assertThat(seen).containsExactly("GET /next");
      server.verify(0, anyRequestedFor(urlPathEqualTo("/next")));
    } finally {
      server.stop();
    }
  }

  /** 표준이 아닌 메서드 값은 재시도하지 않는 안내 오류(ApiCallException)로 낸다 — 전송 오류로 재시도되지 않는다. */
  @Test
  void resolveMethod_unknownMethodThrowsNonRetryableApiCallException() {
    assertThatThrownBy(() -> RedirectFollower.resolveMethod("FETCH-IT"))
        .isInstanceOf(ApiCallException.class)
        .hasMessage(ApiCallErrorMessages.unsupportedHttpMethod("FETCH-IT"));
  }

  /** WD-986 ④: 표준이 아닌 값은 저장한(입력한) 원문을 문구에 보인다 — 대문자 정규화값이 아니다. */
  @Test
  void resolveMethod_unknownMethod_reportsStoredValueVerbatim() {
    assertThatThrownBy(() -> RedirectFollower.resolveMethod("fetch-it"))
        .isInstanceOf(ApiCallException.class)
        .hasMessage(ApiCallErrorMessages.unsupportedHttpMethod("fetch-it"))
        .hasMessageContaining("fetch-it")
        .hasMessageNotContaining("FETCH-IT");
  }

  /** 권장 목록에서 빠졌지만 허용 집합은 바꾸지 않는다는 비목표를 고정한다 — TRACE 를 막는 쪽으로 되돌리지 않게. */
  @Test
  void resolveMethod_traceStillAcceptedDespiteOmittedFromGuidance() {
    assertThat(RedirectFollower.resolveMethod("TRACE")).isEqualTo(HttpMethod.TRACE);
  }
}
