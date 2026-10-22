package com.smartfirehub.apiconnection.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.TestConnectionResponse;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.support.IntegrationTestBase;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * API 연결 「테스트」 결과에 쿼리 인증 키 원문이 나가지 않는지 검증한다(WD-177).
 *
 * <p>API_KEY + placement=query 연결은 호출 URL 에 {@code serviceKey=<키>} 를 붙인다. 예전에는 그 URL 이 응답 {@code
 * requestUrl} 로 그대로 나가 화면·AI 챗에 키가 드러났다. 저장된 연결·저장 전 테스트 모두에서 응답·{@code lastErrorMessage} 에는 가린
 * 값({@code ****XXXX})만 있고, 외부 서버(WireMock)에는 원문이 실제로 도착하는지(호출은 그대로인지)를 함께 본다.
 */
class ApiConnectionTestUrlMaskingTest extends IntegrationTestBase {

  @Autowired private ApiConnectionService apiConnectionService;
  @Autowired private DSLContext dsl;

  /** RLS 가 걸린 api_connection 을 테스트가 직접 지울 때 쓰는 트랜잭션 경계. */
  @Autowired private TransactionTemplate tx;

  /** WireMock(localhost) 호출을 허용하려고 SSRF 검사를 끈다. */
  @MockitoBean private SsrfProtectionService ssrfProtectionService;

  static WireMockServer wireMock;

  /** 영숫자만 쓴 키 — 외부 서버에 원문 그대로 도착하는지 단언하기 위한 값. */
  private static final String KEY = "SecretKeyAbc0001239876";

  /** 쿼리에 그대로 쓸 수 없는 문자(=·한글·공백)와 +·/ 가 섞인 키. */
  private static final String SPECIAL_KEY = "ab+c/d==키값 Zz42";

  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Table<?> API_CONNECTION = table(name("api_connection"));
  private static final Field<Long> AC_CREATED_BY =
      field(name("api_connection", "created_by"), Long.class);

  private Long testUserId;

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
  void setUp() {
    wireMock.resetAll();
    testUserId =
        dsl.insertInto(USER_TABLE)
            .set(field(name("user", "username"), String.class), "wd177_" + System.nanoTime())
            .set(field(name("user", "password"), String.class), "password")
            .set(field(name("user", "name"), String.class), "WD-177 User")
            .set(
                field(name("user", "email"), String.class),
                "wd177_" + System.nanoTime() + "@example.com")
            .returning(U_ID)
            .fetchOne(r -> r.get(U_ID));
  }

  @AfterEach
  void tearDown() {
    tx.executeWithoutResult(
        s -> dsl.deleteFrom(API_CONNECTION).where(AC_CREATED_BY.eq(testUserId)).execute());
    dsl.deleteFrom(USER_TABLE).where(U_ID.eq(testUserId)).execute();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private String wireMockBase() {
    return "http://localhost:" + wireMock.port();
  }

  private CreateApiConnectionRequest queryKeyReq(String name, String baseUrl, String apiKey) {
    return new CreateApiConnectionRequest(
        name,
        null,
        "API_KEY",
        Map.of("placement", "query", "paramName", "serviceKey", "apiKey", apiKey),
        baseUrl,
        "/health");
  }

  /** 아무도 듣지 않는 로컬 포트 — 연결 거부 경로 재현용. */
  private static int closedPort() throws Exception {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  /** 키의 원문·form 인코딩형·%20 인코딩형이 모두 없는지. */
  private static void assertNoSecret(String text, String key) {
    if (text == null) return;
    String formEncoded = URLEncoder.encode(key, StandardCharsets.UTF_8);
    assertThat(text)
        .doesNotContain(key)
        .doesNotContain(formEncoded)
        .doesNotContain(formEncoded.replace("+", "%20"));
  }

  /** WireMock 이 실제로 받은 요청 URL(쿼리 포함, 디코딩하지 않은 원형). */
  private static String receivedUrl() {
    List<LoggedRequest> reqs = wireMock.findAll(getRequestedFor(urlPathEqualTo("/health")));
    assertThat(reqs).hasSize(1);
    return reqs.get(0).getUrl();
  }

  // ── 저장된 연결 테스트 ───────────────────────────────────────────────────────

  @Test
  void testConnection_queryKey_responseUrlMasked_butServerReceivesRawKey() {
    wireMock.stubFor(get(urlPathEqualTo("/health")).willReturn(aResponse().withStatus(200)));
    ApiConnectionResponse created =
        apiConnectionService.create(queryKeyReq("WD177 saved", wireMockBase(), KEY), testUserId);

    TestConnectionResponse result = apiConnectionService.testConnection(created.id());

    assertThat(result.ok()).isTrue();
    assertThat(result.requestUrl()).isEqualTo(wireMockBase() + "/health?serviceKey=****9876");
    assertNoSecret(result.requestUrl(), KEY);
    // 호출 자체는 원문 키로 나가야 한다(가리기는 표시용만).
    assertThat(receivedUrl()).isEqualTo("/health?serviceKey=" + KEY);
  }

  /** 4xx(WebClientResponseException) 경로도 표시 URL 을 쓴다. */
  @Test
  void testConnection_queryKey_http401_responseUrlMasked() {
    wireMock.stubFor(get(urlPathEqualTo("/health")).willReturn(aResponse().withStatus(401)));
    ApiConnectionResponse created =
        apiConnectionService.create(queryKeyReq("WD177 401", wireMockBase(), KEY), testUserId);

    TestConnectionResponse result = apiConnectionService.testConnection(created.id());

    assertThat(result.ok()).isFalse();
    assertThat(result.status()).isEqualTo(401);
    assertThat(result.requestUrl()).endsWith("serviceKey=****9876");
    assertNoSecret(result.requestUrl(), KEY);
    assertNoSecret(result.errorMessage(), KEY);
    assertThat(receivedUrl()).isEqualTo("/health?serviceKey=" + KEY);
  }

  /** 연결 거부(catch-all 경로): 응답 URL·오류 문구·DB lastErrorMessage 에 원문이 없다. */
  @Test
  void testConnection_queryKey_connectionRefused_noSecretInUrlOrError() throws Exception {
    String base = "http://localhost:" + closedPort();
    ApiConnectionResponse created =
        apiConnectionService.create(queryKeyReq("WD177 refused", base, KEY), testUserId);

    TestConnectionResponse result = apiConnectionService.testConnection(created.id());

    assertThat(result.ok()).isFalse();
    assertThat(result.requestUrl()).isEqualTo(base + "/health?serviceKey=****9876");
    assertNoSecret(result.errorMessage(), KEY);
    assertNoSecret(apiConnectionService.getById(created.id()).lastErrorMessage(), KEY);
  }

  /**
   * 쿼리에 그대로 쓸 수 없는 문자가 섞인 키: 예전에는 Spring 이 {@code Invalid character '=' for QUERY_PARAM in "<키>"} 를
   * 던져 400 응답 본문에 원문이 실렸다. 이제 예외 없이 DOWN 결과로 돌아오고, 응답·DB 어디에도 원문·인코딩형이 없다.
   */
  @Test
  void testConnection_specialCharKey_noExceptionAndNoSecretAnywhere() {
    ApiConnectionResponse created =
        apiConnectionService.create(
            queryKeyReq("WD177 special", wireMockBase(), SPECIAL_KEY), testUserId);

    TestConnectionResponse result = apiConnectionService.testConnection(created.id());

    assertThat(result.ok()).isFalse();
    assertThat(result.errorMessage()).contains("요청 URL 을 만들 수 없습니다");
    assertNoSecret(result.requestUrl(), SPECIAL_KEY);
    assertNoSecret(result.errorMessage(), SPECIAL_KEY);
    assertNoSecret(apiConnectionService.getById(created.id()).lastErrorMessage(), SPECIAL_KEY);
  }

  /**
   * 이미 URL 인코딩된 키: 응답엔 인코딩형·디코딩형 모두 없다.
   *
   * <p>호출 URL 은 손대지 않았다. 다만 「테스트」 경로는 예전부터 WebClient {@code .uri(String)} 이 문자열을 템플릿으로 다시 인코딩해
   * {@code %2B} 가 {@code %252B} 로 나간다(WD-177 이전부터의 동작, 실행·미리보기는 URI 를 넘겨 그대로 나감) — 이 테스트의 범위가 아니므로
   * 요청이 도착했는지만 본다.
   */
  @Test
  void testConnection_preEncodedKey_responseHasNoForm() {
    String encodedKey = "abc%2Bdef%2F%3D%3D9876";
    wireMock.stubFor(get(urlPathEqualTo("/health")).willReturn(aResponse().withStatus(200)));
    ApiConnectionResponse created =
        apiConnectionService.create(
            queryKeyReq("WD177 encoded", wireMockBase(), encodedKey), testUserId);

    TestConnectionResponse result = apiConnectionService.testConnection(created.id());

    assertThat(result.requestUrl())
        .endsWith("serviceKey=****9876")
        .doesNotContain(encodedKey)
        .doesNotContain("abc+def/==9876");
    assertThat(receivedUrl()).startsWith("/health?serviceKey=");
  }

  /**
   * 리다이렉트 응답: WebClient 는 리다이렉트를 따라가지 않아 3xx 의 {@code Location}(쿼리 포함)과 본문이 그대로 결과에 실린다. 흔한
   * http→https 리다이렉트가 쿼리를 그대로 옮기므로 헤더·본문 미리보기에서도 키를 가려야 한다.
   */
  @Test
  void testConnection_redirectEchoingKey_headersAndBodyMasked() {
    wireMock.stubFor(
        get(urlPathEqualTo("/health"))
            .willReturn(
                aResponse()
                    .withStatus(301)
                    .withHeader("Location", "https://secure.example.com/health?serviceKey=" + KEY)
                    .withBody("moved: serviceKey=" + KEY)));
    ApiConnectionResponse created =
        apiConnectionService.create(queryKeyReq("WD177 redirect", wireMockBase(), KEY), testUserId);

    TestConnectionResponse result = apiConnectionService.testConnection(created.id());

    assertThat(result.responseHeaders()).containsKey("Location");
    assertThat(result.responseHeaders().get("Location")).endsWith("serviceKey=****9876");
    result.responseHeaders().values().forEach(v -> assertNoSecret(v, KEY));
    assertThat(result.responseBodyPreview()).isEqualTo("moved: serviceKey=****9876");
    assertThat(receivedUrl()).isEqualTo("/health?serviceKey=" + KEY);
  }

  /** 4xx 오류 본문이 요청 파라미터를 되울리는 경우(WebClientResponseException 경로)도 본문 미리보기에서 키를 가린다. */
  @Test
  void testConnectionPayload_errorBodyEchoingKey_bodyMasked() {
    wireMock.stubFor(
        get(urlPathEqualTo("/health"))
            .willReturn(aResponse().withStatus(400).withBody("invalid serviceKey: " + KEY)));

    TestConnectionResponse result =
        apiConnectionService.testConnectionPayload(
            queryKeyReq("WD177 payload echo", wireMockBase(), KEY));

    assertThat(result.status()).isEqualTo(400);
    assertThat(result.responseBodyPreview()).isEqualTo("invalid serviceKey: ****9876");
    result.responseHeaders().values().forEach(v -> assertNoSecret(v, KEY));
  }

  // ── 저장 전 테스트(payload) ──────────────────────────────────────────────────

  @Test
  void testConnectionPayload_queryKey_responseUrlMasked_butServerReceivesRawKey() {
    wireMock.stubFor(get(urlPathEqualTo("/health")).willReturn(aResponse().withStatus(200)));

    TestConnectionResponse result =
        apiConnectionService.testConnectionPayload(
            queryKeyReq("WD177 payload", wireMockBase(), KEY));

    assertThat(result.ok()).isTrue();
    assertThat(result.requestUrl()).isEqualTo(wireMockBase() + "/health?serviceKey=****9876");
    assertNoSecret(result.requestUrl(), KEY);
    assertThat(receivedUrl()).isEqualTo("/health?serviceKey=" + KEY);
  }

  @Test
  void testConnectionPayload_queryKey_connectionRefused_noSecret() throws Exception {
    String base = "http://localhost:" + closedPort();

    TestConnectionResponse result =
        apiConnectionService.testConnectionPayload(queryKeyReq("WD177 payload refused", base, KEY));

    assertThat(result.ok()).isFalse();
    assertThat(result.requestUrl()).isEqualTo(base + "/health?serviceKey=****9876");
    assertNoSecret(result.errorMessage(), KEY);
  }

  @Test
  void testConnectionPayload_specialCharKey_noExceptionAndNoSecret() {
    TestConnectionResponse result =
        apiConnectionService.testConnectionPayload(
            queryKeyReq("WD177 payload special", wireMockBase(), SPECIAL_KEY));

    assertThat(result.ok()).isFalse();
    assertThat(result.errorMessage()).contains("요청 URL 을 만들 수 없습니다");
    assertNoSecret(result.requestUrl(), SPECIAL_KEY);
    assertNoSecret(result.errorMessage(), SPECIAL_KEY);
  }
}
