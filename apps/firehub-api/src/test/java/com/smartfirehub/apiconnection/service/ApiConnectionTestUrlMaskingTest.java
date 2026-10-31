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
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.pipeline.service.executor.ApiCallErrorMessages;
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
 *
 * <p>「테스트」·전체 새로고침이 바꾼 상태도 끊김·복구 알림과 감사 STATUS_CHANGE 를 남기는지(WD-189)도 같은 이유로 여기서 본다. 알림과 감사는 {@link
 * ApiConnectionNotifier} 한 메서드에서 함께 나가므로 감사 행 수로 알림 횟수를 센다(알림 빈을 목으로 바꾸면 컨텍스트가 하나 더 뜬다).
 */
class ApiConnectionTestUrlMaskingTest extends IntegrationTestBase {

  @Autowired private ApiConnectionService apiConnectionService;
  @Autowired private ApiConnectionHealthCheckScheduler scheduler;
  @Autowired private AsyncJobService asyncJobService;
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

  /** {@link #SPECIAL_KEY} 를 공용 규칙(QueryApiKeyEncoding)으로 한 번 인코딩한 값 — 대상 서버가 받아야 할 바이트(WD-182). */
  private static final String SPECIAL_KEY_SENT = "ab%2Bc%2Fd%3D%3D%ED%82%A4%EA%B0%92%20Zz42";

  /** 공공데이터포털이 주는 같은 키의 두 형태(시험용 가짜 키) — 어느 쪽을 넣어도 인코딩형이 한 번 인코딩된 값으로 나가야 한다(WD-182). */
  private static final String PUBLIC_KEY_ENCODED = "rP1x%2FAbC%2Bd%3D%3D";

  private static final String PUBLIC_KEY_DECODED = "rP1x/AbC+d==";

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

  private static final Table<?> AUDIT_LOG = table(name("audit_log"));
  private static final Field<Long> AL_ID = field(name("audit_log", "id"), Long.class);
  private static final Field<Long> AL_USER_ID = field(name("audit_log", "user_id"), Long.class);
  private static final Field<String> AL_USERNAME =
      field(name("audit_log", "username"), String.class);
  private static final Field<String> AL_RESOURCE =
      field(name("audit_log", "resource"), String.class);
  private static final Field<String> AL_RESOURCE_ID =
      field(name("audit_log", "resource_id"), String.class);
  private static final Field<String> AL_ACTION_TYPE =
      field(name("audit_log", "action_type"), String.class);
  private static final Field<String> AL_RESULT = field(name("audit_log", "result"), String.class);
  private static final Field<String> AL_METADATA =
      field("{0}::text", String.class, field(name("audit_log", "metadata")));

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
        s -> {
          // 주기 점검의 상태 전환 감사는 행위자가 없어(user_id NULL) 아래 user_id 삭제로 지워지지 않는다 — 이 사용자가 만든 연결의 감사를 먼저
          // 지운다(WD-189).
          dsl.deleteFrom(AUDIT_LOG)
              .where(AL_RESOURCE.eq("api_connection"))
              .and(
                  AL_RESOURCE_ID.in(
                      dsl.select(AC_ID.cast(String.class))
                          .from(API_CONNECTION)
                          .where(AC_CREATED_BY.eq(testUserId))))
              .execute();
          dsl.deleteFrom(API_CONNECTION).where(AC_CREATED_BY.eq(testUserId)).execute();
          // 생성·수정·삭제 감사(WD-181)가 이 사용자를 행위자로 남긴다 — audit_log.user_id 는 "user" FK 라 먼저 지운다(RLS 대상이라
          // 테넌트 트랜잭션 안에서).
          dsl.execute("delete from audit_log where user_id = ?", testUserId);
          // 전체 새로고침 TC(WD-189)가 이 사용자 소유의 비동기 작업을 남긴다 — async_job.user_id 도 "user" FK 다.
          dsl.execute("delete from async_job where user_id = ?", testUserId);
        });
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

  /** WireMock 이 /health 로 받은 모든 요청 URL(받은 순서, 디코딩하지 않은 원형). */
  private static List<String> receivedUrls() {
    return wireMock.findAll(getRequestedFor(urlPathEqualTo("/health"))).stream()
        .map(LoggedRequest::getUrl)
        .toList();
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
    // WD-180: 영어 원문(「Connection refused: localhost/127.0.0.1:N」) 대신 API_CALL 실행·미리보기와 같은 한국어 문장이다 —
    // 응답과
    // 저장되는 lastErrorMessage 둘 다.
    assertKoreanConnectionRefused(result.errorMessage());
    assertKoreanConnectionRefused(apiConnectionService.getById(created.id()).lastErrorMessage());
  }

  /** 연결 거부 사유가 API_CALL 쪽 한국어 문장(WD-150)과 같고 영어 원문이 없는지 단언한다(WD-180). */
  private static void assertKoreanConnectionRefused(String message) {
    assertThat(message)
        .isEqualTo(ApiCallErrorMessages.connectionRefused())
        .startsWith("연결이 거부되었습니다")
        .doesNotContainIgnoringCase("refused")
        .doesNotContain("127.0.0.1");
  }

  /**
   * 쿼리에 그대로 쓸 수 없는 문자가 섞인 디코딩 키(WD-182): 예전에는 URL 을 만들지 못해 「URL 인코딩된 값을 넣으세요」 DOWN 으로 끝났다(그 전에는
   * Spring 이 키 원문을 담은 400 을 냈다, WD-177). 이제 공용 규칙으로 한 번 인코딩해 호출하고, 응답·DB 어디에도 원문·인코딩형이 없다.
   */
  @Test
  void testConnection_specialCharKey_encodedOnceAndNoSecretAnywhere() {
    stubHealthOk();
    ApiConnectionResponse created =
        apiConnectionService.create(
            queryKeyReq("WD177 special", wireMockBase(), SPECIAL_KEY), testUserId);

    TestConnectionResponse result = apiConnectionService.testConnection(created.id());

    assertThat(result.ok()).isTrue();
    assertThat(receivedUrl()).isEqualTo("/health?serviceKey=" + SPECIAL_KEY_SENT);
    assertNoSecret(result.requestUrl(), SPECIAL_KEY);
    assertThat(result.requestUrl()).doesNotContain(SPECIAL_KEY_SENT);
    assertNoSecret(result.errorMessage(), SPECIAL_KEY);
    assertNoSecret(apiConnectionService.getById(created.id()).lastErrorMessage(), SPECIAL_KEY);
  }

  /**
   * 이미 URL 인코딩된 키: 대상 서버엔 받은 그대로(한 번 인코딩된 값) 도착하고, 응답엔 인코딩형·디코딩형 모두 없다.
   *
   * <p>WD-182 이전에는 「테스트」 경로만 WebClient {@code .uri(String)} 이 문자열을 템플릿으로 다시 인코딩해 {@code %2B} 가
   * {@code %252B} 로 나가 테스트가 실패했다(미리보기·실행은 그대로 나감). 이 결함의 핵심 회귀 TC 다.
   */
  @Test
  void testConnection_preEncodedKey_sentAsIs_responseHasNoForm() {
    String encodedKey = "abc%2Bdef%2F%3D%3D9876";
    wireMock.stubFor(get(urlPathEqualTo("/health")).willReturn(aResponse().withStatus(200)));
    ApiConnectionResponse created =
        apiConnectionService.create(
            queryKeyReq("WD177 encoded", wireMockBase(), encodedKey), testUserId);

    TestConnectionResponse result = apiConnectionService.testConnection(created.id());

    assertThat(result.ok()).isTrue();
    assertThat(result.requestUrl())
        .endsWith("serviceKey=****9876")
        .doesNotContain(encodedKey)
        .doesNotContain("abc+def/==9876");
    assertThat(receivedUrl()).isEqualTo("/health?serviceKey=" + encodedKey);
  }

  /**
   * 공공데이터포털처럼 같은 키를 인코딩 키·디코딩 키 두 형태로 넣어도 「테스트」·저장 전 테스트·10분 상태 점검이 대상 서버에 같은 바이트를 보낸다(WD-182).
   * 미리보기·Java 실행기는 같은 기대값을 각 테스트 클래스에서 단언한다.
   */
  @Test
  void encodedAndDecodedKeys_sameBytesOnTestPayloadAndScheduler() {
    stubHealthOk();
    String expected = "/health?serviceKey=" + PUBLIC_KEY_ENCODED;

    // 저장된 연결 「테스트」
    Long encId =
        apiConnectionService
            .create(queryKeyReq("WD182 enc", wireMockBase(), PUBLIC_KEY_ENCODED), testUserId)
            .id();
    Long decId =
        apiConnectionService
            .create(queryKeyReq("WD182 dec", wireMockBase(), PUBLIC_KEY_DECODED), testUserId)
            .id();
    assertThat(apiConnectionService.testConnection(encId).ok()).isTrue();
    assertThat(apiConnectionService.testConnection(decId).ok()).isTrue();
    assertThat(receivedUrls()).hasSizeGreaterThanOrEqualTo(2).allMatch(expected::equals);

    // 저장 전 테스트
    wireMock.resetRequests();
    assertThat(
            apiConnectionService
                .testConnectionPayload(
                    queryKeyReq("WD182 enc p", wireMockBase(), PUBLIC_KEY_ENCODED))
                .ok())
        .isTrue();
    assertThat(
            apiConnectionService
                .testConnectionPayload(
                    queryKeyReq("WD182 dec p", wireMockBase(), PUBLIC_KEY_DECODED))
                .ok())
        .isTrue();
    assertThat(receivedUrls()).hasSizeGreaterThanOrEqualTo(2).allMatch(expected::equals);

    // 10분 상태 점검 — testConnection 과 같은 경로다. 이 WireMock 포트를 가리키는 연결은 이 TC 가 만든 두 개뿐이다. 개수는 「두 개
    // 이상」으로 본다 — 테스트 프로필은 백그라운드 주기 실행을 끄지만(WD-451), 이 단언의 요점은 개수가 아니라 보내는 값이 모두 같다는 것이다.
    wireMock.resetRequests();
    scheduler.runOnce();
    assertThat(receivedUrls()).hasSizeGreaterThanOrEqualTo(2).allMatch(expected::equals);
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
    // WD-180: 저장 전 테스트도 같은 한국어 문장
    assertKoreanConnectionRefused(result.errorMessage());
  }

  /** 저장 전 테스트도 디코딩 키를 한 번 인코딩해 호출한다(WD-182). */
  @Test
  void testConnectionPayload_specialCharKey_encodedOnceAndNoSecret() {
    stubHealthOk();
    TestConnectionResponse result =
        apiConnectionService.testConnectionPayload(
            queryKeyReq("WD177 payload special", wireMockBase(), SPECIAL_KEY));

    assertThat(result.ok()).isTrue();
    assertThat(receivedUrl()).isEqualTo("/health?serviceKey=" + SPECIAL_KEY_SENT);
    assertNoSecret(result.requestUrl(), SPECIAL_KEY);
    assertThat(result.requestUrl()).doesNotContain(SPECIAL_KEY_SENT);
    assertNoSecret(result.errorMessage(), SPECIAL_KEY);
  }

  /**
   * 정말로 URL 을 만들 수 없는 경우(헬스체크 경로 쿼리에 생 공백·한글)는 여전히 고정 문구의 DOWN 이고 호출하지 않는다 — 함께 붙는 키 원문·인코딩형은 응답
   * 어디에도 없다(WD-177 처리 유지).
   */
  @Test
  void testConnectionPayload_unbuildableUrl_downWithoutCallOrSecret() {
    stubHealthOk();
    CreateApiConnectionRequest req =
        new CreateApiConnectionRequest(
            "WD182 unbuildable",
            null,
            "API_KEY",
            Map.of("placement", "query", "paramName", "serviceKey", "apiKey", SPECIAL_KEY),
            wireMockBase(),
            "/health?q=a b 한글");

    TestConnectionResponse result = apiConnectionService.testConnectionPayload(req);

    assertThat(result.ok()).isFalse();
    assertThat(result.errorMessage())
        .startsWith("요청 URL 을 만들 수 없습니다")
        .doesNotContain("URL 인코딩된 값을 넣으세요");
    assertNoSecret(result.errorMessage(), SPECIAL_KEY);
    assertNoSecret(result.requestUrl(), SPECIAL_KEY);
    wireMock.verify(0, anyRequestedFor(anyUrl()));
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

  // ── 상태 전환 알림·감사: 「테스트」·새로고침·주기 점검이 한 곳을 거친다(WD-189) ─────────────

  /** 연결을 만들고 직전 상태를 DB 에서 직접 맞춘다(주기 점검이 이미 한 번 본 연결). 0건 갱신이면 TC 가 공허하므로 1건을 단언한다. */
  private Long createWithStatus(String name, String lastStatus) {
    Long id = apiConnectionService.create(queryKeyReq(name, wireMockBase(), KEY), testUserId).id();
    int updated =
        inTenantFixture(
            () ->
                dsl.update(API_CONNECTION)
                    .set(AC_LAST_STATUS, lastStatus)
                    .where(AC_ID.eq(id))
                    .execute());
    assertThat(updated).isEqualTo(1);
    return id;
  }

  /** 이 연결의 상태 전환 감사(STATUS_CHANGE) 행 — 오래된 것부터. 알림과 같은 메서드에서 남으므로 행 수 = 알림 횟수다. */
  private List<Record> statusChanges(Long id) {
    return inTenantFixture(
        () ->
            dsl
                .select(AL_USER_ID, AL_USERNAME, AL_RESULT, AL_METADATA)
                .from(AUDIT_LOG)
                .where(AL_RESOURCE.eq("api_connection"))
                .and(AL_RESOURCE_ID.eq(String.valueOf(id)))
                .and(AL_ACTION_TYPE.eq("STATUS_CHANGE"))
                .orderBy(AL_ID.asc())
                .fetch()
                .stream()
                .map(r -> (Record) r)
                .toList());
  }

  private String testUsername() {
    return dsl.select(field(name("user", "username"), String.class))
        .from(USER_TABLE)
        .where(U_ID.eq(testUserId))
        .fetchOne(0, String.class);
  }

  private static void stubHealth(int status) {
    wireMock.stubFor(get(urlPathEqualTo("/health")).willReturn(aResponse().withStatus(status)));
  }

  /**
   * 끊긴 뒤 주기 점검 전에 「테스트」를 누르면 그 테스트가 끊김 알림·감사를 1회 남기고(행위자 = 누른 사용자), 이어진 주기 점검은 직전 상태가 이미 DOWN 이라 다시
   * 알리지 않는다. 다시 「테스트」해도 그대로면 알림이 없다. 예전에는 테스트가 상태만 DOWN 으로 저장해 주기 점검도 변화 없음으로 넘겨, 끊김이 알림·기록 없이
   * 사라졌다.
   */
  @Test
  void testConnection_upToDown_notifiesOnceAsUser_thenSchedulerAndRetestDoNotRepeat() {
    Long id = createWithStatus("WD189 down", "UP");
    stubHealth(503);

    TestConnectionResponse result = apiConnectionService.testConnection(id, testUserId);

    assertThat(result.ok()).isFalse();
    assertThat(readStatus(id).get(AC_LAST_STATUS)).isEqualTo("DOWN");
    List<Record> changes = statusChanges(id);
    assertThat(changes).as("테스트가 끊김을 처음 본 호출이므로 알림·감사 1회").hasSize(1);
    Record change = changes.get(0);
    assertThat(change.get(AL_USER_ID)).as("행위자는 테스트를 누른 사용자").isEqualTo(testUserId);
    assertThat(change.get(AL_USERNAME)).isEqualTo(testUsername());
    assertThat(change.get(AL_RESULT)).isEqualTo("FAILURE");
    assertThat(change.get(AL_METADATA)).contains("\"from\": \"UP\"").contains("\"to\": \"DOWN\"");

    // 이어진 주기 점검 — 같은 DOWN 이라 중복 알림이 없다.
    scheduler.runOnce();
    // 다시 「테스트」해도 상태 그대로면 알림이 없다.
    apiConnectionService.testConnection(id, testUserId);

    assertThat(statusChanges(id)).as("같은 끊김을 두 번 알리지 않는다").hasSize(1);
  }

  /** 끊긴 연결을 「테스트」로 복구 확인하면 복구 알림·감사가 1회 남는다(SUCCESS, DOWN→UP). */
  @Test
  void testConnection_downToUp_notifiesRecoveryAsUser() {
    Long id = createWithStatus("WD189 up", "DOWN");
    stubHealth(200);

    apiConnectionService.testConnection(id, testUserId);

    List<Record> changes = statusChanges(id);
    assertThat(changes).hasSize(1);
    assertThat(changes.get(0).get(AL_USER_ID)).isEqualTo(testUserId);
    assertThat(changes.get(0).get(AL_RESULT)).isEqualTo("SUCCESS");
    assertThat(changes.get(0).get(AL_METADATA))
        .contains("\"from\": \"DOWN\"")
        .contains("\"to\": \"UP\"");
  }

  /** 상태가 그대로(UP→UP)거나 첫 점검(null→UP)이면 알림·감사가 없다 — 기존 주기 점검 규칙과 같다. */
  @Test
  void testConnection_unchangedOrFirstCheck_noNotification() {
    stubHealth(200);
    Long same = createWithStatus("WD189 same", "UP");
    Long first =
        apiConnectionService
            .create(queryKeyReq("WD189 first", wireMockBase(), KEY), testUserId)
            .id();

    apiConnectionService.testConnection(same, testUserId);
    apiConnectionService.testConnection(first, testUserId);

    assertThat(statusChanges(same)).isEmpty();
    assertThat(statusChanges(first)).isEmpty();
    // 부하 중 가끔 DOWN 으로 저장돼 실패했다(WD-477) — 원인(헬스 체크 5초 시간 초과인지 다른 오류인지)을 다시 났을 때 로그로 가를 수 있게
    // 저장된 last_error 를 단언 설명에 싣는다(행은 한 번만 읽는다).
    Record firstRow = readStatus(first);
    assertThat(firstRow.get(AC_LAST_STATUS))
        .as("첫 점검도 상태는 저장된다 — last_error=%s", firstRow.get(AC_LAST_ERROR))
        .isEqualTo("UP");
  }

  /** 주기 점검이 먼저 전환을 보면 행위자는 시스템이다(user_id 없음, username=system). */
  @Test
  void scheduler_upToDown_notifiesOnceAsSystem() {
    Long id = createWithStatus("WD189 scheduler", "UP");
    stubHealth(503);

    scheduler.runOnce();

    List<Record> changes = statusChanges(id);
    assertThat(changes).hasSize(1);
    assertThat(changes.get(0).get(AL_USER_ID)).isNull();
    assertThat(changes.get(0).get(AL_USERNAME)).isEqualTo("system");
  }

  /** 「전체 새로고침」도 같은 경로 — 끊김을 처음 보면 알림·감사 1회, 행위자는 새로고침을 누른 사용자. */
  @Test
  void refreshAll_upToDown_notifiesOnceAsUser() throws Exception {
    Long id = createWithStatus("WD189 refresh", "UP");
    stubHealth(503);

    String jobId = apiConnectionService.refreshAllAsync(testUserId);
    awaitJobCompleted(jobId);

    assertThat(readStatus(id).get(AC_LAST_STATUS)).isEqualTo("DOWN");
    List<Record> changes = statusChanges(id);
    assertThat(changes).hasSize(1);
    assertThat(changes.get(0).get(AL_USER_ID)).isEqualTo(testUserId);
    assertThat(changes.get(0).get(AL_USERNAME)).isEqualTo(testUsername());
  }

  /** 새로고침 비동기 작업이 끝날 때까지 기다린다(다른 테스트가 남긴 느린 대상이 있어도 넉넉하게 60초). */
  private void awaitJobCompleted(String jobId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline) {
      String stage = asyncJobService.getJobStatus(jobId, testUserId).stage();
      if ("COMPLETED".equals(stage) || "FAILED".equals(stage)) {
        assertThat(stage).isEqualTo("COMPLETED");
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("새로고침 작업이 60초 안에 끝나지 않았습니다: " + jobId);
  }
}
