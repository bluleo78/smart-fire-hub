package com.smartfirehub.pipeline.service.executor;

import static com.smartfirehub.pipeline.service.executor.FakeDnsResolverGroup.ips;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.netty.resolver.dns.DnsAddressResolverGroup;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

/**
 * 팩토리로 만든 WebClient 가 접속 시점에 막는지(WD-613) — 사전 검사(validateUrl)를 거치지 않은 상태에서, 가짜 DNS 가 「rebind.test」를
 * 루프백으로 풀면 로컬 MockWebServer 에 요청이 한 건도 닿지 않고, 오류가 사전 검사와 같은 차단 문구로 분류되는지 본다.
 */
class SsrfSafeHttpClientFactoryTest {

  private static final String LOOPBACK_BLOCKED = "내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1";

  private MockWebServer server;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    // 가짜 DNS 가 127.0.0.1 로 풀므로 IPv4 루프백에 띄운다(localhost 가 ::1 로 풀리는 환경 대비).
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  @AfterEach
  void stop() throws Exception {
    server.shutdown();
  }

  private WebClient client(SsrfSafeHttpClientFactory factory) {
    return WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(factory.httpClient().followRedirect(false)))
        .build();
  }

  private String rebindUrl() {
    return "http://rebind.test:" + server.getPort() + "/secret";
  }

  @Test
  void hostResolvingToLoopback_refusedAtConnectTime_sameUserFacingMessage() {
    server.enqueue(new MockResponse().setBody("internal"));
    FakeDnsResolverGroup dns = new FakeDnsResolverGroup().answer("rebind.test", ips("127.0.0.1"));
    SsrfSafeHttpClientFactory factory =
        new SsrfSafeHttpClientFactory(dns, new SsrfProtectionService()::validateResolvedAddress);

    Throwable error =
        catchThrowable(
            () ->
                client(factory)
                    .get()
                    .uri(rebindUrl())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10)));

    // WebClient 가 감싸서 올리지만, 원인 사슬에 사전 검사와 같은 SsrfException 이 있다.
    assertThat(error).isInstanceOf(WebClientRequestException.class);
    assertThat(SsrfException.findIn(error)).isNotNull().hasMessage(LOOPBACK_BLOCKED);
    // 실행·미리보기가 쓰는 분류기도 같은 차단 문구를 낸다(「연결하지 못했습니다」 같은 일반 문구가 아님).
    assertThat(ApiCallErrorMessages.describe(error, 10_000, "rebind.test"))
        .isEqualTo(LOOPBACK_BLOCKED);
    // 해석기를 실제로 거쳤고, 대상 서버에는 아무 요청도 닿지 않았다.
    assertThat(dns.queries()).isPositive();
    assertThat(server.getRequestCount()).isZero();
  }

  /**
   * 정책이 허용하면 가짜 DNS 가 푼 주소로 실제 접속한다 — 이 클라이언트가 정말 팩토리의 해석기로 접속 주소를 고른다는 증거(「rebind.test」는 실제 DNS 에
   * 없다). Host 헤더는 원래 호스트 이름 그대로다(IP 로 바꿔 접속하지 않는다).
   */
  @Test
  void permissivePolicy_connectsViaResolverAndKeepsHostHeader() throws Exception {
    server.enqueue(new MockResponse().setBody("ok"));
    FakeDnsResolverGroup dns = new FakeDnsResolverGroup().answer("rebind.test", ips("127.0.0.1"));
    SsrfSafeHttpClientFactory factory = new SsrfSafeHttpClientFactory(dns, address -> {});

    String body =
        client(factory)
            .get()
            .uri(rebindUrl())
            .retrieve()
            .bodyToMono(String.class)
            .block(Duration.ofSeconds(10));

    assertThat(body).isEqualTo("ok");
    RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
    assertThat(recorded).isNotNull();
    assertThat(recorded.getHeader("Host")).isEqualTo("rebind.test:" + server.getPort());
  }

  /** IP 리터럴 URL 도 접속 시점에 다시 검사한다 — 사전 검사가 빠진 경로에 대한 이중 방어. */
  @Test
  void loopbackLiteral_refusedEvenWithoutPreCheck() {
    server.enqueue(new MockResponse().setBody("internal"));
    SsrfSafeHttpClientFactory factory = new SsrfSafeHttpClientFactory(new SsrfProtectionService());

    Throwable error =
        catchThrowable(
            () ->
                client(factory)
                    .get()
                    .uri("http://127.0.0.1:" + server.getPort() + "/secret")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10)));

    assertThat(SsrfException.findIn(error)).isNotNull().hasMessage(LOOPBACK_BLOCKED);
    assertThat(server.getRequestCount()).isZero();
  }

  /**
   * 운영 팩토리의 실제 해석기는 비동기 Netty DNS 해석기다(WD-613 리뷰) — JDK 해석기(DefaultAddressResolverGroup)는 이벤트 루프에서
   * 동기로 조회해 느린 DNS 이름 하나가 공유 루프를 멈춘다. 그룹은 앱 전체에서 하나만 쓴다(요청·인스턴스마다 만들지 않는다).
   */
  @Test
  void defaultFactory_delegatesToSharedNonBlockingNettyDns() {
    SsrfSafeHttpClientFactory bean = new SsrfSafeHttpClientFactory(new SsrfProtectionService());
    SsrfSafeHttpClientFactory custom = new SsrfSafeHttpClientFactory(address -> {});

    assertThat(bean.dnsDelegate()).isInstanceOf(DnsAddressResolverGroup.class);
    assertThat(custom.dnsDelegate()).isSameAs(bean.dnsDelegate());
  }

  /**
   * 실제 해석기 경로(가짜 DNS 없음) — Netty DNS 해석기가 /etc/hosts 의 localhost 를 풀고, 감싼 접속 검사가 그 루프백 주소를 막는다. 기본
   * 해석기를 바꾼 뒤에도 hosts 파일을 따르고, 검사가 그 해석기를 감싼다는 증거다.
   */
  @Test
  void defaultFactory_resolvesLocalhostFromHostsFile_andBlocksLoopback() {
    server.enqueue(new MockResponse().setBody("internal"));
    SsrfSafeHttpClientFactory factory = new SsrfSafeHttpClientFactory(new SsrfProtectionService());

    Throwable error =
        catchThrowable(
            () ->
                client(factory)
                    .get()
                    .uri("http://localhost:" + server.getPort() + "/secret")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10)));

    assertThat(SsrfException.findIn(error))
        .isNotNull()
        .hasMessageStartingWith("내부망 주소는 호출할 수 없습니다(루프백): ");
    assertThat(server.getRequestCount()).isZero();
  }
}
