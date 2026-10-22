package com.smartfirehub.apiconnection.service;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.TestConnectionResponse;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.support.IntegrationTestBase;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
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
 *
 * <p>「테스트」·10분 상태 점검이 호출 직전에 주소를 다시 검사하는지(WD-178)도 이 클래스에서 본다. 같은 컨텍스트 구성({@code @MockitoBean
 * SsrfProtectionService} 하나)을 쓰는 테스트 클래스를 따로 더하면 전체 스위트가 테스트 JVM 메모리 한계를 넘어 멈추므로(실측), 컨텍스트를 공유하는 이
 * 클래스에 둔다. 목은 기본적으로 아무것도 하지 않고, 차단을 증명하는 TC 에서만 {@link #useRealSsrfJudgement()} 로 실제 판정을 건다.
 */
class ApiConnectionTestUrlMaskingTest extends IntegrationTestBase {

  @Autowired private ApiConnectionService apiConnectionService;
  @Autowired private ApiConnectionHealthCheckScheduler scheduler;
  @Autowired private DSLContext dsl;

  /** RLS 가 걸린 api_connection 을 테스트가 직접 지울 때 쓰는 트랜잭션 경계. */
  @Autowired private TransactionTemplate tx;

  /**
   * WireMock(localhost) 호출을 허용하려고 SSRF 검사를 끈다. 테스트마다 초기화되므로(기본 reset=AFTER) 기본은 아무것도 하지 않으며, WD-178
   * 차단 TC 만 {@link #useRealSsrfJudgement()} 로 실제 판정을 건다.
   */
  @MockitoBean private SsrfProtectionService ssrfProtectionService;

  static WireMockServer wireMock;

  /** 영숫자만 쓴 키 — 외부 서버에 원문 그대로 도착하는지 단언하기 위한 값. */
  private static final String KEY = "SecretKeyAbc0001239876";

  /** 쿼리에 그대로 쓸 수 없는 문자(=·한글·공백)와 +·/ 가 섞인 키. */
  private static final String SPECIAL_KEY = "ab+c/d==키값 Zz42";

  private static final Table<?> USER_TABLE = table(name("user"));
  private static final Field<Long> U_ID = field(name("user", "id"), Long.class);
  private static final Table<?> API_CONNECTION = table(name("api_connection"));
  private static final Field<Long> AC_ID = field(name("api_connection", "id"), Long.class);
  private static final Field<Long> AC_CREATED_BY =
      field(name("api_connection", "created_by"), Long.class);
  private static final Field<String> AC_BASE_URL =
      field(name("api_connection", "base_url"), String.class);
  private static final Field<String> AC_LAST_STATUS =
      field(name("api_connection", "last_status"), String.class);
  private static final Field<String> AC_LAST_ERROR =
      field(name("api_connection", "last_error_message"), String.class);

  /** SsrfProtectionService 의 루프백 차단 문구 앞부분(WD-150) — API_CALL 실행·미리보기와 같은 문장이어야 한다. */
  private static final String LOOPBACK_BLOCKED = "내부망 주소는 호출할 수 없습니다(루프백)";

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

  /**
   * 목이 운영 코드와 같은 판정(DNS 해석 후 IP 검사)을 하게 한다(WD-178). SsrfProtectionService 는 상태가 없어 실제 메서드를 그대로 불러도
   * 된다.
   */
  private void useRealSsrfJudgement() {
    reset(ssrfProtectionService);
    doCallRealMethod().when(ssrfProtectionService).validateUrl(anyString());
    doCallRealMethod().when(ssrfProtectionService).validateResolvedAddress(any());
  }

  /** 판정이 꺼진 기본 목 상태에서 저장 시 검사를 통과시켜 연결을 만든 뒤, 실제 판정을 건다(WD-178). */
  private Long createBypassingSaveCheck(String name) {
    ApiConnectionResponse created =
        apiConnectionService.create(queryKeyReq(name, wireMockBase(), KEY), testUserId);
    useRealSsrfJudgement();
    return created.id();
  }

  /**
   * 저장 후 base_url 이 내부 주소로 바뀐 상황(규칙 강화 전 저장분·DB 직접 변경)을 DB 에서 직접 만든다. 0건 갱신이면 TC 가 공허하므로 1건을 단언한다.
   */
  private void repointToLoopback(Long id, String lastStatus) {
    int updated =
        inTenantFixture(
            () ->
                dsl.update(API_CONNECTION)
                    .set(AC_BASE_URL, "http://127.0.0.1:" + wireMock.port())
                    .set(AC_LAST_STATUS, lastStatus)
                    .where(AC_ID.eq(id))
                    .execute());
    assertThat(updated).as("base_url 을 내부 주소로 바꾼 행이 1건이어야 한다").isEqualTo(1);
  }

  /** 연결의 마지막 상태·오류 문구를 DB 에서 직접 읽는다. */
  private Record readStatus(Long id) {
    return inTenantFixture(
        () ->
            dsl.select(AC_LAST_STATUS, AC_LAST_ERROR)
                .from(API_CONNECTION)
                .where(AC_ID.eq(id))
                .fetchOne());
  }

  /** 검사가 빠지면 ok=true 로 깨지도록 /health 에 200 을 건다(404 면 검사 없이도 ok=false 라 차단 TC 가 공허해진다). */
  private static void stubHealthOk() {
    wireMock.stubFor(get(urlPathEqualTo("/health")).willReturn(aResponse().withStatus(200)));
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

  // ── WD-178: 호출 직전 주소 재검사 ──

  /** 저장 뒤 내부 주소로 바뀐 연결의 「테스트」는 호출하지 않고 DOWN·한국어 사유를 돌려주고 저장한다. */
  @Test
  void testConnection_savedConnectionRepointedToLoopback_blockedWithoutCall() {
    stubHealthOk();
    Long id = createBypassingSaveCheck("WD178 saved");
    repointToLoopback(id, null);

    TestConnectionResponse result = apiConnectionService.testConnection(id);

    assertThat(result.ok()).isFalse();
    assertThat(result.status()).isNull();
    assertThat(result.errorMessage()).startsWith(LOOPBACK_BLOCKED).contains("127.0.0.1");
    assertThat(result.errorMessage()).doesNotContain(KEY);
    assertThat(result.responseBodyPreview()).isNull();
    assertThat(result.responseHeaders()).isEmpty();
    assertThat(result.responseContentType()).isNull();
    // 인증 헤더·키를 실은 요청이 내부 주소로 한 건도 나가지 않아야 한다.
    wireMock.verify(0, anyRequestedFor(anyUrl()));

    Record row = readStatus(id);
    assertThat(row.get(AC_LAST_STATUS)).isEqualTo("DOWN");
    assertThat(row.get(AC_LAST_ERROR)).isEqualTo(result.errorMessage());
  }

  /** 저장 전 테스트는 입력 검사에서 이미 막힌다(회귀) — 같은 한국어 사유로 거부되고 호출은 없다. */
  @Test
  void testConnectionPayload_loopback_rejectedWithoutCall() {
    stubHealthOk();
    useRealSsrfJudgement();

    assertThatThrownBy(
            () ->
                apiConnectionService.testConnectionPayload(
                    queryKeyReq("WD178 payload", "http://127.0.0.1:" + wireMock.port(), KEY)))
        .isInstanceOf(ApiConnectionException.class)
        .hasMessageStartingWith(LOOPBACK_BLOCKED);
    wireMock.verify(0, anyRequestedFor(anyUrl()));
  }

  /** 상태 점검 스케줄러도 같은 검사를 거친다 — 호출 없이 DOWN 저장, UP→DOWN 전환 알림에 한국어 사유가 실린다. */
  @Test
  void scheduler_connectionRepointedToLoopback_storesDownAndNotifiesWithoutCall() {
    stubHealthOk();
    Long id = createBypassingSaveCheck("WD178 scheduler");
    repointToLoopback(id, "UP");

    scheduler.runOnce();

    wireMock.verify(0, anyRequestedFor(anyUrl()));
    Record row = readStatus(id);
    assertThat(row.get(AC_LAST_STATUS)).isEqualTo("DOWN");
    assertThat(row.get(AC_LAST_ERROR)).startsWith(LOOPBACK_BLOCKED).contains("127.0.0.1");
    // 상태 전환 알림은 감사 로그(STATUS_CHANGE)로 확인한다 — 알림 빈을 목으로 바꾸면 컨텍스트가 하나 더 뜬다(클래스 설명 참고).
    String auditDescription =
        inTenantFixture(
            () ->
                dsl.select(field(name("audit_log", "description"), String.class))
                    .from(table(name("audit_log")))
                    .where(field(name("audit_log", "resource"), String.class).eq("api_connection"))
                    .and(
                        field(name("audit_log", "resource_id"), String.class)
                            .eq(String.valueOf(id)))
                    .and(field(name("audit_log", "action_type"), String.class).eq("STATUS_CHANGE"))
                    .orderBy(field(name("audit_log", "id")).desc())
                    .limit(1)
                    .fetchOne(0, String.class));
    assertThat(auditDescription).as("UP→DOWN 전환 알림에 한국어 사유가 실린다").contains(LOOPBACK_BLOCKED);
  }

  /** 공개 주소로 판정되면 예전처럼 호출된다. 검사 대상은 키를 붙이기 전 URL(기준 URL + 헬스체크 경로)이다. */
  @Test
  void testConnection_publicJudged_stillCalls_andChecksUrlWithoutKey() {
    stubHealthOk();
    // 기본 목(판정 통과)으로 WireMock(localhost)을 공개 주소처럼 보이게 하는 기존 관례를 그대로 쓴다.
    ApiConnectionResponse created =
        apiConnectionService.create(queryKeyReq("WD178 public", wireMockBase(), KEY), testUserId);
    clearInvocations(ssrfProtectionService);

    TestConnectionResponse result = apiConnectionService.testConnection(created.id());

    assertThat(result.ok()).isTrue();
    assertThat(result.status()).isEqualTo(200);
    wireMock.verify(1, getRequestedFor(urlEqualTo("/health?serviceKey=" + KEY)));
    verify(ssrfProtectionService, times(1)).validateUrl(wireMockBase() + "/health");
    verify(ssrfProtectionService, never()).validateUrl(argThat(u -> u != null && u.contains(KEY)));
  }

  /** 리다이렉트는 따라가지 않는다 — 3xx 를 결과로 돌려줄 뿐 Location 주소로 다시 요청하지 않는다(검사를 우회하는 두 번째 요청이 없다). */
  @Test
  void testConnection_redirect_notFollowed() {
    wireMock.stubFor(
        get(urlPathEqualTo("/health"))
            .willReturn(
                aResponse().withStatus(302).withHeader("Location", wireMockBase() + "/internal")));
    wireMock.stubFor(get(urlPathEqualTo("/internal")).willReturn(aResponse().withStatus(200)));
    ApiConnectionResponse created =
        apiConnectionService.create(queryKeyReq("WD178 redirect", wireMockBase(), KEY), testUserId);

    TestConnectionResponse result = apiConnectionService.testConnection(created.id());

    assertThat(result.ok()).isFalse();
    assertThat(result.status()).isEqualTo(302);
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/health")));
    wireMock.verify(0, getRequestedFor(urlPathEqualTo("/internal")));
  }
}
