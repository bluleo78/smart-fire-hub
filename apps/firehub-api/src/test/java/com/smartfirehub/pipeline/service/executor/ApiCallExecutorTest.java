package com.smartfirehub.pipeline.service.executor;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

@ExtendWith(MockitoExtension.class)
class ApiCallExecutorTest {

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

  @Mock DataTableService dataTableService;
  @Mock DataTableRowService dataTableRowService;

  SsrfProtectionService ssrfProtectionService;
  JsonResponseParser jsonParser;
  OffsetPaginationHandler paginationHandler;
  ApiCallExecutor executor;

  @BeforeEach
  void setUp() {
    // SsrfProtectionService performs DNS resolution — use a no-op subclass
    // so localhost (WireMock) passes SSRF checks in tests.
    ssrfProtectionService =
        new SsrfProtectionService() {
          @Override
          public void validateUrl(String url) {
            // bypass in tests — we control the WireMock server
          }
        };
    jsonParser = new JsonResponseParser();
    paginationHandler = new OffsetPaginationHandler();
    executor =
        new ApiCallExecutor(
            ssrfProtectionService,
            jsonParser,
            paginationHandler,
            dataTableService,
            dataTableRowService,
            WebClient.builder());
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private String baseUrl() {
    return "http://localhost:" + wireMock.port();
  }

  private ApiCallConfig simpleGetConfig(String path) {
    return new ApiCallConfig(
        baseUrl() + path,
        "GET",
        null,
        null,
        null,
        "JSON",
        "$.items",
        List.of(
            new ApiCallConfig.FieldMapping("name", "name", "TEXT", null, null, null),
            new ApiCallConfig.FieldMapping("age", "age", "INTEGER", null, null, null)),
        "UTC",
        null,
        new ApiCallConfig.RetryConfig(0, 100, 1000),
        5000,
        60000,
        100,
        null,
        null,
        null,
        null);
  }

  // -------------------------------------------------------------------------
  // Test 1: simple GET — parses and inserts data
  // -------------------------------------------------------------------------

  @Test
  void execute_simpleGet_parsesAndInsertsData() {
    wireMock.stubFor(
        get(urlEqualTo("/api/items"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"items":[{"name":"Alice","age":30},{"name":"Bob","age":25}]}
                        """)));

    ApiCallExecutor.ApiCallResult result =
        executor.execute(simpleGetConfig("/api/items"), "test_table", null, "APPEND", null);

    assertThat(result.totalRows()).isEqualTo(2);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Map<String, Object>>> rowsCaptor = ArgumentCaptor.forClass(List.class);
    verify(dataTableRowService).insertBatch(eq("test_table"), anyList(), rowsCaptor.capture());

    List<Map<String, Object>> rows = rowsCaptor.getValue();
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("name", "Alice");
    assertThat(rows.get(0)).containsEntry("age", 30L);
    assertThat(rows.get(1)).containsEntry("name", "Bob");
  }

  // -------------------------------------------------------------------------
  // Test 2: POST with body
  // -------------------------------------------------------------------------

  @Test
  void execute_postWithBody_success() {
    wireMock.stubFor(
        post(urlEqualTo("/api/search"))
            .withRequestBody(equalToJson("{\"query\":\"fire\"}"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"items":[{"name":"Station A","age":5}]}
                        """)));

    ApiCallConfig config =
        new ApiCallConfig(
            baseUrl() + "/api/search",
            "POST",
            null,
            null,
            "{\"query\":\"fire\"}",
            "JSON",
            "$.items",
            List.of(new ApiCallConfig.FieldMapping("name", "name", "TEXT", null, null, null)),
            "UTC",
            null,
            new ApiCallConfig.RetryConfig(0, 100, 1000),
            5000,
            60000,
            100,
            null,
            null,
            null,
            null);

    ApiCallExecutor.ApiCallResult result =
        executor.execute(config, "out_table", null, "APPEND", null);

    assertThat(result.totalRows()).isEqualTo(1);
    verify(dataTableRowService).insertBatch(eq("out_table"), anyList(), anyList());
  }

  // -------------------------------------------------------------------------
  // Test 3: API_KEY header auth
  // -------------------------------------------------------------------------

  @Test
  void execute_apiKeyHeaderAuth_sendsHeader() {
    wireMock.stubFor(
        get(urlEqualTo("/api/secure"))
            .withHeader("X-Api-Key", equalTo("secret-key"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"items\":[]}")));

    ApiCallConfig config = simpleGetConfig("/api/secure");
    Map<String, String> auth =
        Map.of(
            "authType", "API_KEY",
            "placement", "header",
            "headerName", "X-Api-Key",
            "apiKey", "secret-key");

    ApiCallExecutor.ApiCallResult result = executor.execute(config, "t", auth, "APPEND", null);

    assertThat(result.totalRows()).isEqualTo(0);
    wireMock.verify(
        getRequestedFor(urlEqualTo("/api/secure")).withHeader("X-Api-Key", equalTo("secret-key")));
  }

  // -------------------------------------------------------------------------
  // WD-177: 쿼리 인증 키 원문이 실행 기록·오류 문구에 남지 않는다
  // -------------------------------------------------------------------------

  /** 정상 호출: 원문 키로 호출하되 실행 로그(DB 에 저장)엔 키가 없다. */
  @Test
  void execute_apiKeyQueryAuth_sendsRawKey_executionLogHasNoKey() {
    wireMock.stubFor(
        get(urlPathEqualTo("/api/q"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"items\":[]}")));
    String key = "ExecSecret000005555";
    Map<String, String> auth =
        Map.of(
            "authType", "API_KEY", "placement", "query", "paramName", "serviceKey", "apiKey", key);

    ApiCallExecutor.ApiCallResult result =
        executor.execute(simpleGetConfig("/api/q"), "t", auth, "APPEND", null);

    assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo("/api/q"))).get(0).getUrl())
        .isEqualTo("/api/q?serviceKey=" + key);
    assertThat(result.log()).doesNotContain(key);
  }

  /**
   * 쿼리에 그대로 쓸 수 없는 문자가 섞인 디코딩 키(WD-182): 예전에는 「URL 인코딩된 값을 넣으세요」로 즉시 실패했다(그 전에는 키 원문이 실행 기록에 실렸다,
   * WD-177). 이제 공용 규칙으로 한 번 인코딩해 호출하고, 실행 로그엔 원문·보낸 형태가 없다.
   */
  @Test
  void execute_apiKeyQueryAuth_specialCharKey_encodedOnce_logHasNoKey() {
    stubEmptyItems("/api/q");
    String key = "ex+k/y==비밀 Ww11";
    String sent = "ex%2Bk%2Fy%3D%3D%EB%B9%84%EB%B0%80%20Ww11";

    ApiCallExecutor.ApiCallResult result =
        executor.execute(simpleGetConfig("/api/q"), "t", queryAuth(key), "APPEND", null);

    assertThat(receivedUrls("/api/q")).containsExactly("/api/q?serviceKey=" + sent);
    assertThat(result.log())
        .doesNotContain(key)
        .doesNotContain(sent)
        .doesNotContain(java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * 공공데이터포털이 주는 같은 키의 인코딩 키·디코딩 키(시험용 가짜 키)가 Java 실행기에서도 「테스트」·미리보기와 같은 바이트로 나간다(WD-182). 페이지네이션
   * 파라미터가 붙어도 키 인코딩은 같다.
   */
  @Test
  void execute_apiKeyQueryAuth_encodedAndDecodedKeys_sameBytes() {
    stubEmptyItems("/api/q");

    executor.execute(
        simpleGetConfig("/api/q"), "t", queryAuth("rP1x%2FAbC%2Bd%3D%3D"), "APPEND", null);
    executor.execute(simpleGetConfig("/api/q"), "t", queryAuth("rP1x/AbC+d=="), "APPEND", null);

    assertThat(receivedUrls("/api/q"))
        .containsExactly(
            "/api/q?serviceKey=rP1x%2FAbC%2Bd%3D%3D", "/api/q?serviceKey=rP1x%2FAbC%2Bd%3D%3D");
  }

  /** config 의 정적 쿼리 파라미터만 바꾼 사본. */
  private static ApiCallConfig withQueryParams(
      ApiCallConfig base, Map<String, String> queryParams) {
    return new ApiCallConfig(
        base.url(),
        base.method(),
        base.headers(),
        queryParams,
        base.body(),
        base.responseFormat(),
        base.dataPath(),
        base.fieldMappings(),
        base.sourceTimezone(),
        base.pagination(),
        base.retry(),
        base.timeoutMs(),
        base.maxDurationMs(),
        base.maxResponseSizeMb(),
        base.inlineAuth(),
        base.apiConnectionId(),
        base.path(),
        base.customUrl());
  }

  /**
   * 정적 쿼리 값에 {@code = 공백 한글} 이 있어도 이제 공용 규칙으로 한 번 인코딩해 호출한다(WD-200) — 예전에는 「URL 인코딩해 넣으세요」로 즉시
   * 실패했다. 함께 붙는 키 원문·보낸 형태는 실행 로그에 없다.
   */
  @Test
  void execute_unencodedStaticParam_encodedOnce_logHasNoKey() {
    stubEmptyItems("/api/q");
    String key = "ex+k/y==비밀 Ww11";
    ApiCallConfig config = withQueryParams(simpleGetConfig("/api/q"), Map.of("q", "a=b 한글"));

    ApiCallExecutor.ApiCallResult result =
        executor.execute(config, "t", queryAuth(key), "APPEND", null);

    assertThat(receivedUrls("/api/q"))
        .containsExactly(
            "/api/q?q=a%3Db%20%ED%95%9C%EA%B8%80"
                + "&serviceKey=ex%2Bk%2Fy%3D%3D%EB%B9%84%EB%B0%80%20Ww11");
    assertThat(result.log()).doesNotContain(key).doesNotContain("ex%2Bk%2Fy%3D%3D");
  }

  /**
   * 같은 정적 토큰의 인코딩된 형태·인코딩 안 한 형태가 Java 실행기에서도 미리보기·Python 실행기와 같은 바이트로 나간다(WD-200, 공용 사례 표와 같은
   * 기대값).
   */
  @Test
  void execute_staticParam_encodedAndUnencoded_sameBytes() {
    stubEmptyItems("/api/q");

    executor.execute(
        withQueryParams(simpleGetConfig("/api/q"), Map.of("token", "abc%2Bd%3D%3D")),
        "t",
        null,
        "APPEND",
        null);
    executor.execute(
        withQueryParams(simpleGetConfig("/api/q"), Map.of("token", "abc+d==")),
        "t",
        null,
        "APPEND",
        null);

    assertThat(receivedUrls("/api/q"))
        .containsExactly("/api/q?token=abc%2Bd%3D%3D", "/api/q?token=abc%2Bd%3D%3D");
  }

  /**
   * 주소(기준 URL)에 직접 쓴 쿼리에 쓸 수 없는 문자가 있으면 여전히 URL 을 만들 수 없다 — 원문 없는 고정 문구로, 재시도 없이 즉시 실패한다(WD-177 처리
   * 유지). 함께 붙는 키 원문·보낸 형태도 문구에 없다.
   */
  @Test
  void execute_unbuildableBaseUrl_failsWithoutKeyAndWithoutRetry() {
    String key = "ex+k/y==비밀 Ww11";
    ApiCallConfig config = simpleGetConfig("/api/q?q=a b 한글");

    assertThatThrownBy(() -> executor.execute(config, "t", queryAuth(key), "APPEND", null))
        .isInstanceOf(ApiCallException.class)
        // 재시도 소진 문구(「API 호출에 실패했습니다: …」)로 감싸이지 않은 고정 문구 그대로여야 한다 — 재시도 경로를 타지 않았다는 뜻.
        .hasMessageStartingWith("요청 URL 을 만들 수 없습니다")
        .satisfies(
            e ->
                assertThat(e.getMessage())
                    .doesNotContain("쿼리 파라미터 값은 URL 인코딩해 넣으세요")
                    .doesNotContain(key)
                    .doesNotContain("ex%2Bk%2Fy%3D%3D")
                    .doesNotContain(
                        java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  private static Map<String, String> queryAuth(String key) {
    return Map.of(
        "authType", "API_KEY", "placement", "query", "paramName", "serviceKey", "apiKey", key);
  }

  private static void stubEmptyItems(String path) {
    wireMock.stubFor(
        get(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"items\":[]}")));
  }

  /** WireMock 이 path 로 받은 요청 URL(디코딩하지 않은 원형). */
  private static List<String> receivedUrls(String path) {
    return wireMock.findAll(getRequestedFor(urlPathEqualTo(path))).stream()
        .map(com.github.tomakehurst.wiremock.verification.LoggedRequest::getUrl)
        .toList();
  }

  // -------------------------------------------------------------------------
  // Test 4: BEARER auth
  // -------------------------------------------------------------------------

  @Test
  void execute_bearerAuth_sendsAuthorizationHeader() {
    wireMock.stubFor(
        get(urlEqualTo("/api/bearer"))
            .withHeader("Authorization", equalTo("Bearer my-token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"items\":[]}")));

    ApiCallConfig config = simpleGetConfig("/api/bearer");
    Map<String, String> auth =
        Map.of(
            "authType", "BEARER",
            "token", "my-token");

    executor.execute(config, "t", auth, "APPEND", null);

    wireMock.verify(
        getRequestedFor(urlEqualTo("/api/bearer"))
            .withHeader("Authorization", equalTo("Bearer my-token")));
  }

  // -------------------------------------------------------------------------
  // Test 5: OFFSET pagination — fetches multiple pages
  // -------------------------------------------------------------------------

  @Test
  void execute_offsetPagination_fetchesMultiplePages() {
    // 3 pages of 2 rows each, total=6 declared
    wireMock.stubFor(
        get(urlPathEqualTo("/api/paged"))
            .withQueryParam("offset", equalTo("0"))
            .withQueryParam("limit", equalTo("2"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"total\":6,\"items\":[{\"name\":\"A\",\"age\":1},{\"name\":\"B\",\"age\":2}]}")));

    wireMock.stubFor(
        get(urlPathEqualTo("/api/paged"))
            .withQueryParam("offset", equalTo("2"))
            .withQueryParam("limit", equalTo("2"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"total\":6,\"items\":[{\"name\":\"C\",\"age\":3},{\"name\":\"D\",\"age\":4}]}")));

    wireMock.stubFor(
        get(urlPathEqualTo("/api/paged"))
            .withQueryParam("offset", equalTo("4"))
            .withQueryParam("limit", equalTo("2"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"total\":6,\"items\":[{\"name\":\"E\",\"age\":5},{\"name\":\"F\",\"age\":6}]}")));

    ApiCallConfig config =
        new ApiCallConfig(
            baseUrl() + "/api/paged",
            "GET",
            null,
            null,
            null,
            "JSON",
            "$.items",
            List.of(
                new ApiCallConfig.FieldMapping("name", "name", "TEXT", null, null, null),
                new ApiCallConfig.FieldMapping("age", "age", "INTEGER", null, null, null)),
            "UTC",
            new ApiCallConfig.PaginationConfig("OFFSET", 2, "offset", "limit", "$.total"),
            new ApiCallConfig.RetryConfig(0, 100, 1000),
            5000,
            60000,
            100,
            null,
            null,
            null,
            null);

    ApiCallExecutor.ApiCallResult result =
        executor.execute(config, "paged_table", null, "APPEND", null);

    assertThat(result.totalRows()).isEqualTo(6);
    verify(dataTableRowService, times(3)).insertBatch(eq("paged_table"), anyList(), anyList());
  }

  // -------------------------------------------------------------------------
  // Test 6: partial last page stops correctly
  // -------------------------------------------------------------------------

  @Test
  void execute_offsetPagination_stopsOnEmptyPage() {
    // First page: full (2 rows), second page: partial (1 row) → should stop
    wireMock.stubFor(
        get(urlPathEqualTo("/api/partial"))
            .withQueryParam("offset", equalTo("0"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"name\":\"A\",\"age\":1},{\"name\":\"B\",\"age\":2}]}")));

    wireMock.stubFor(
        get(urlPathEqualTo("/api/partial"))
            .withQueryParam("offset", equalTo("2"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"items\":[{\"name\":\"C\",\"age\":3}]}")));

    ApiCallConfig config =
        new ApiCallConfig(
            baseUrl() + "/api/partial",
            "GET",
            null,
            null,
            null,
            "JSON",
            "$.items",
            List.of(
                new ApiCallConfig.FieldMapping("name", "name", "TEXT", null, null, null),
                new ApiCallConfig.FieldMapping("age", "age", "INTEGER", null, null, null)),
            "UTC",
            new ApiCallConfig.PaginationConfig("OFFSET", 2, "offset", "limit", null),
            new ApiCallConfig.RetryConfig(0, 100, 1000),
            5000,
            60000,
            100,
            null,
            null,
            null,
            null);

    ApiCallExecutor.ApiCallResult result =
        executor.execute(config, "partial_table", null, "APPEND", null);

    assertThat(result.totalRows()).isEqualTo(3);
    verify(dataTableRowService, times(2)).insertBatch(eq("partial_table"), anyList(), anyList());
  }

  // -------------------------------------------------------------------------
  // Test 7: 401 fails immediately without retry
  // -------------------------------------------------------------------------

  @Test
  void execute_401_failsImmediately() {
    wireMock.stubFor(
        get(urlEqualTo("/api/auth-fail"))
            .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));

    ApiCallConfig config = simpleGetConfig("/api/auth-fail");

    assertThatThrownBy(() -> executor.execute(config, "t", null, "APPEND", null))
        .isInstanceOf(ApiCallException.class)
        .hasMessageContaining("401");

    // Only one request should have been made (no retries)
    wireMock.verify(1, getRequestedFor(urlEqualTo("/api/auth-fail")));
  }

  // -------------------------------------------------------------------------
  // Test 8: 5xx — retries then succeeds
  // -------------------------------------------------------------------------

  @Test
  void execute_500_retriesWithBackoff() {
    // First call: 500, second call: 200
    wireMock.stubFor(
        get(urlEqualTo("/api/flaky"))
            .inScenario("flaky")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(500).withBody("Server Error"))
            .willSetStateTo("ok"));

    wireMock.stubFor(
        get(urlEqualTo("/api/flaky"))
            .inScenario("flaky")
            .whenScenarioStateIs("ok")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"items\":[{\"name\":\"X\",\"age\":9}]}")));

    ApiCallConfig config =
        new ApiCallConfig(
            baseUrl() + "/api/flaky",
            "GET",
            null,
            null,
            null,
            "JSON",
            "$.items",
            List.of(
                new ApiCallConfig.FieldMapping("name", "name", "TEXT", null, null, null),
                new ApiCallConfig.FieldMapping("age", "age", "INTEGER", null, null, null)),
            "UTC",
            null,
            new ApiCallConfig.RetryConfig(3, 50, 200),
            5000,
            60000,
            100,
            null,
            null,
            null,
            null);

    ApiCallExecutor.ApiCallResult result =
        executor.execute(config, "flaky_table", null, "APPEND", null);

    assertThat(result.totalRows()).isEqualTo(1);
    // 2 total requests: 1 failure + 1 success
    wireMock.verify(2, getRequestedFor(urlEqualTo("/api/flaky")));
  }

  // -------------------------------------------------------------------------
  // Test 9: all retries exhausted → throws
  // -------------------------------------------------------------------------

  @Test
  void execute_allRetriesFail_throwsAfterMaxRetries() {
    wireMock.stubFor(
        get(urlEqualTo("/api/always-fail"))
            .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

    ApiCallConfig config =
        new ApiCallConfig(
            baseUrl() + "/api/always-fail",
            "GET",
            null,
            null,
            null,
            "JSON",
            "$.items",
            List.of(),
            "UTC",
            null,
            new ApiCallConfig.RetryConfig(2, 50, 200),
            5000,
            60000,
            100,
            null,
            null,
            null,
            null);

    // WD-150: 재시도 소진 문구에 마지막 원인(대상 서버 오류 503)이 담긴다
    assertThatThrownBy(() -> executor.execute(config, "t", null, "APPEND", null))
        .isInstanceOf(ApiCallException.class)
        .hasMessage("API 호출이 2회 재시도 후에도 실패했습니다 — 마지막 오류: 대상 서버 오류(HTTP 503)");

    // 1 initial + 2 retries = 3 total
    wireMock.verify(3, getRequestedFor(urlEqualTo("/api/always-fail")));
  }

  // -------------------------------------------------------------------------
  // Test 10: dataPath not found → throws ApiCallException
  // -------------------------------------------------------------------------

  @Test
  void execute_jsonPathNotFound_throwsException() {
    wireMock.stubFor(
        get(urlEqualTo("/api/wrong-path"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"data\":{\"records\":[]}}")));

    ApiCallConfig config =
        new ApiCallConfig(
            baseUrl() + "/api/wrong-path",
            "GET",
            null,
            null,
            null,
            "JSON",
            "$.nonexistent.items", // <-- wrong path
            List.of(),
            "UTC",
            null,
            new ApiCallConfig.RetryConfig(0, 100, 1000),
            5000,
            60000,
            100,
            null,
            null,
            null,
            null);

    assertThatThrownBy(() -> executor.execute(config, "t", null, "APPEND", null))
        .isInstanceOf(ApiCallException.class)
        .hasMessage("응답에서 데이터 경로를 찾을 수 없습니다: $.nonexistent.items");
  }

  // -------------------------------------------------------------------------
  // Test 11: null dataType preserves native Jackson types (Integer, Boolean, String)
  // -------------------------------------------------------------------------

  @Test
  void execute_nullDataType_preservesNativeJacksonTypes() {
    wireMock.stubFor(
        get(urlEqualTo("/api/native-types"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"items":[{"user_id":12345,"name":"Alice","active":true,"score":3.14}]}
                        """)));

    ApiCallConfig config =
        new ApiCallConfig(
            baseUrl() + "/api/native-types",
            "GET",
            null,
            null,
            null,
            "JSON",
            "$.items",
            List.of(
                new ApiCallConfig.FieldMapping("user_id", "user_id", null, null, null, null),
                new ApiCallConfig.FieldMapping("name", "name", null, null, null, null),
                new ApiCallConfig.FieldMapping("active", "active", null, null, null, null),
                new ApiCallConfig.FieldMapping("score", "score", null, null, null, null)),
            "UTC",
            null,
            new ApiCallConfig.RetryConfig(0, 100, 1000),
            5000,
            60000,
            100,
            null,
            null,
            null,
            null);

    executor.execute(config, "native_table", null, "APPEND", null);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Map<String, Object>>> rowsCaptor = ArgumentCaptor.forClass(List.class);
    verify(dataTableRowService).insertBatch(eq("native_table"), anyList(), rowsCaptor.capture());

    Map<String, Object> row = rowsCaptor.getValue().get(0);
    // Integer values must remain Integer (not String "12345")
    assertThat(row.get("user_id")).isInstanceOf(Integer.class);
    assertThat(row.get("user_id")).isEqualTo(12345);
    // String values stay as String
    assertThat(row.get("name")).isEqualTo("Alice");
    // Boolean values must remain Boolean (not String "true")
    assertThat(row.get("active")).isInstanceOf(Boolean.class);
    assertThat(row.get("active")).isEqualTo(true);
    // Double values must remain Double (not String "3.14")
    assertThat(row.get("score")).isInstanceOf(Double.class);
    assertThat(row.get("score")).isEqualTo(3.14);
  }

  // -------------------------------------------------------------------------
  // Test 11b: columnTypeMap overrides mapping.dataType — metadata-based conversion
  // -------------------------------------------------------------------------

  @Test
  void execute_columnTypeMap_convertsBasedOnDatasetMetadata() {
    wireMock.stubFor(
        get(urlEqualTo("/api/typed"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"items":[{"user_id":"999","name":"Bob","active":"true","score":"2.5"}]}
                        """)));

    // Field mappings have no dataType (simulating new frontend behavior)
    ApiCallConfig config =
        new ApiCallConfig(
            baseUrl() + "/api/typed",
            "GET",
            null,
            null,
            null,
            "JSON",
            "$.items",
            List.of(
                new ApiCallConfig.FieldMapping("user_id", "user_id", null, null, null, null),
                new ApiCallConfig.FieldMapping("name", "name", null, null, null, null),
                new ApiCallConfig.FieldMapping("active", "active", null, null, null, null),
                new ApiCallConfig.FieldMapping("score", "score", null, null, null, null)),
            "UTC",
            null,
            new ApiCallConfig.RetryConfig(0, 100, 1000),
            5000,
            60000,
            100,
            null,
            null,
            null,
            null);

    // Column type map from dataset metadata
    Map<String, String> columnTypeMap =
        Map.of(
            "user_id", "INTEGER",
            "name", "TEXT",
            "active", "BOOLEAN",
            "score", "DECIMAL");

    executor.execute(config, "typed_table", null, "APPEND", columnTypeMap);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Map<String, Object>>> rowsCaptor = ArgumentCaptor.forClass(List.class);
    verify(dataTableRowService).insertBatch(eq("typed_table"), anyList(), rowsCaptor.capture());

    Map<String, Object> row = rowsCaptor.getValue().get(0);
    // String "999" must be converted to Long via INTEGER type from metadata
    assertThat(row.get("user_id")).isEqualTo(999L);
    assertThat(row.get("name")).isEqualTo("Bob");
    assertThat(row.get("active")).isEqualTo(true);
    assertThat(row.get("score")).isEqualTo(new java.math.BigDecimal("2.5"));
  }

  // -------------------------------------------------------------------------
  // Test 12: REPLACE strategy — uses temp-table swap, not truncate
  // -------------------------------------------------------------------------

  @Test
  void execute_replaceStrategy_insertsTempTableAndSwaps() {
    wireMock.stubFor(
        get(urlEqualTo("/api/replace"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"items":[{"name":"NewA","age":10},{"name":"NewB","age":20}]}
                        """)));

    ApiCallConfig config =
        new ApiCallConfig(
            baseUrl() + "/api/replace",
            "GET",
            null,
            null,
            null,
            "JSON",
            "$.items",
            List.of(
                new ApiCallConfig.FieldMapping("name", "name", "TEXT", null, null, null),
                new ApiCallConfig.FieldMapping("age", "age", "INTEGER", null, null, null)),
            "UTC",
            null,
            new ApiCallConfig.RetryConfig(0, 100, 1000),
            5000,
            60000,
            100,
            null,
            null,
            null,
            null);

    // 이번 실행 전용 임시 테이블 이름(WD-151) — 실행기는 이 이름을 적재·마무리에 그대로 써야 한다(다시 계산하면 다른 실행의 것과 섞인다).
    String runTemp = DataTableService.newTempTableName("rep_table");
    when(dataTableService.createTempTable("rep_table")).thenReturn(runTemp);

    ApiCallExecutor.ApiCallResult result =
        executor.execute(config, "rep_table", null, "REPLACE", null);

    assertThat(result.totalRows()).isEqualTo(2);

    // Must create the temp table first
    verify(dataTableService).createTempTable("rep_table");

    // Rows must be inserted into this run's temp table, not the original
    verify(dataTableRowService).insertBatch(eq(runTemp), anyList(), anyList());
    verify(dataTableRowService, never()).insertBatch(eq("rep_table"), anyList(), anyList());

    // On success, swap must be called
    // 스왑 여부의 판단은 DataTableService.finishReplace 가 단독으로 갖는다(#685) — 여기서는
    // 적재된 행 수가 그대로 전달되는지만 본다. API 가 0행을 돌려줘도 원본이 지워지지 않는 것은
    // finishReplace 의 책임이고 DataTableServiceTest 가 본다.
    verify(dataTableService).finishReplace("rep_table", runTemp, 2L);

    // dropTempTable must NOT be called on success
    verify(dataTableService, never()).dropTempTable(any(), any());

    // truncateTable must never be called (old behaviour is gone)
    verify(dataTableRowService, never()).truncateTable(any());
  }

  // -------------------------------------------------------------------------
  // Test 12: REPLACE strategy — drops temp table on API failure
  // -------------------------------------------------------------------------

  @Test
  void execute_replaceStrategy_dropsTempTableOnFailure() {
    // API returns 503 — non-retryable after 0 retries configured
    wireMock.stubFor(
        get(urlEqualTo("/api/replace-fail"))
            .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

    ApiCallConfig config =
        new ApiCallConfig(
            baseUrl() + "/api/replace-fail",
            "GET",
            null,
            null,
            null,
            "JSON",
            "$.items",
            List.of(),
            "UTC",
            null,
            new ApiCallConfig.RetryConfig(0, 100, 1000),
            5000,
            60000,
            100,
            null,
            null,
            null,
            null);

    // 실패 정리는 이번 실행이 만든 임시 테이블만 지운다(WD-151) — 같은 출력에 적재 중인 다른 실행의 것은 이름이 달라 닿지 않는다.
    String runTemp = DataTableService.newTempTableName("rep_fail_table");
    when(dataTableService.createTempTable("rep_fail_table")).thenReturn(runTemp);

    assertThatThrownBy(() -> executor.execute(config, "rep_fail_table", null, "REPLACE", null))
        .isInstanceOf(ApiCallException.class);

    // Temp table must be created and then dropped on failure
    verify(dataTableService).createTempTable("rep_fail_table");
    verify(dataTableService).dropTempTable("rep_fail_table", runTemp);

    // swap must NOT be called
    verify(dataTableService, never()).swapTable(any(), any());
  }

  // -------------------------------------------------------------------------
  // WD-150: 재시도 판정·한국어 원인 문구·쿼리 키 비노출
  // -------------------------------------------------------------------------

  /** 재시도·시간 제한만 바꾼 단순 GET 설정. */
  private ApiCallConfig retryConfig(String url, int maxRetries, int timeoutMs) {
    return new ApiCallConfig(
        url,
        "GET",
        null,
        null,
        null,
        "JSON",
        "$.items",
        List.of(new ApiCallConfig.FieldMapping("name", "name", "TEXT", null, null, null)),
        "UTC",
        null,
        new ApiCallConfig.RetryConfig(maxRetries, 10, 20),
        timeoutMs,
        60000,
        100,
        null,
        null,
        null,
        null);
  }

  /** 405 처럼 고쳐지지 않는 4xx 는 재시도하지 않는다 — 예전에는 400/401/403/404 만 즉시 실패라 405 를 N회 재시도했다. */
  @Test
  void execute_405_failsImmediatelyWithoutRetry() {
    wireMock.stubFor(get(urlEqualTo("/api/method")).willReturn(aResponse().withStatus(405)));

    assertThatThrownBy(
            () ->
                executor.execute(
                    retryConfig(baseUrl() + "/api/method", 3, 5000), "t", null, "APPEND", null))
        .isInstanceOf(ApiCallException.class)
        .hasMessage("허용되지 않는 요청 방식입니다(HTTP 405) — GET/POST 설정을 확인하세요");

    wireMock.verify(1, getRequestedFor(urlEqualTo("/api/method")));
  }

  /** 429(Too Many Requests)는 재시도한다. */
  @Test
  void execute_429_retriesThenSucceeds() {
    wireMock.stubFor(
        get(urlEqualTo("/api/rate"))
            .inScenario("rate")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(429))
            .willSetStateTo("ok"));
    wireMock.stubFor(
        get(urlEqualTo("/api/rate"))
            .inScenario("rate")
            .whenScenarioStateIs("ok")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"items\":[{\"name\":\"A\"}]}")));

    ApiCallExecutor.ApiCallResult result =
        executor.execute(retryConfig(baseUrl() + "/api/rate", 2, 5000), "t", null, "APPEND", null);

    assertThat(result.totalRows()).isEqualTo(1);
    wireMock.verify(2, getRequestedFor(urlEqualTo("/api/rate")));
  }

  /** 429 가 계속되면 재시도 소진 문구에 429 안내가 원인으로 담긴다. */
  @Test
  void execute_429_exhaustedMessageIncludesCause() {
    wireMock.stubFor(get(urlEqualTo("/api/rate-always")).willReturn(aResponse().withStatus(429)));

    assertThatThrownBy(
            () ->
                executor.execute(
                    retryConfig(baseUrl() + "/api/rate-always", 1, 5000),
                    "t",
                    null,
                    "APPEND",
                    null))
        .isInstanceOf(ApiCallException.class)
        .hasMessageContaining("1회 재시도 후에도 실패했습니다 — 마지막 오류: ")
        .hasMessageContaining("(HTTP 429)");
    wireMock.verify(2, getRequestedFor(urlEqualTo("/api/rate-always")));
  }

  /** 타임아웃은 재시도하고, 소진 문구에 「응답 시간 초과(N초)」 원인이 담긴다(예전: 「API call failed after N retries」만). */
  @Test
  void execute_timeout_retriesAndMessageIncludesCause() {
    wireMock.stubFor(
        get(urlEqualTo("/api/slow"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withFixedDelay(1500)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"items\":[]}")));

    assertThatThrownBy(
            () ->
                executor.execute(
                    retryConfig(baseUrl() + "/api/slow", 1, 300), "t", null, "APPEND", null))
        .isInstanceOf(ApiCallException.class)
        .hasMessage("API 호출이 1회 재시도 후에도 실패했습니다 — 마지막 오류: 응답 시간 초과(0.3초) — 대상 서버 상태나 시간 제한을 확인하세요");
    wireMock.verify(2, getRequestedFor(urlEqualTo("/api/slow")));
  }

  /** 연결 거부도 재시도하고, 소진 문구에 원인이 담긴다. */
  @Test
  void execute_connectionRefused_retriesAndMessageIncludesCause() throws Exception {
    int closedPort;
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }

    assertThatThrownBy(
            () ->
                executor.execute(
                    retryConfig("http://localhost:" + closedPort + "/api", 1, 5000),
                    "t",
                    null,
                    "APPEND",
                    null))
        .isInstanceOf(ApiCallException.class)
        .hasMessage("API 호출이 1회 재시도 후에도 실패했습니다 — 마지막 오류: 연결이 거부되었습니다 — 주소·포트와 서버 실행 여부를 확인하세요");
  }

  /** 401 문구에 query 로 붙인 API 키가 남지 않는다 — 예전 「Authentication failed (401): <requestUri>」는 키를 담았다. */
  @Test
  void execute_401_withQueryApiKey_messageHasNoSecret() {
    wireMock.stubFor(
        get(urlPathEqualTo("/api/keyed")).willReturn(aResponse().withStatus(401).withBody("no")));

    Map<String, String> auth =
        Map.of(
            "authType", "API_KEY",
            "placement", "query",
            "paramName", "serviceKey",
            "apiKey", "SECRET123");

    assertThatThrownBy(
            () ->
                executor.execute(
                    retryConfig(baseUrl() + "/api/keyed", 2, 5000), "t", auth, "APPEND", null))
        .isInstanceOf(ApiCallException.class)
        .hasMessage("인증에 실패했습니다(HTTP 401) — API 키·토큰 설정을 확인하세요")
        .message()
        .doesNotContain("SECRET123")
        .doesNotContain("?");

    // 키는 실제로 쿼리로 보냈다(문구에서만 빠진 것) + 401 은 재시도하지 않는다
    wireMock.verify(
        1,
        getRequestedFor(urlPathEqualTo("/api/keyed"))
            .withQueryParam("serviceKey", equalTo("SECRET123")));
  }

  /** 리다이렉트 대상이 내부망이면 재시도하지 않고 한국어 차단 문구로 즉시 실패한다(예전: SsrfException 을 재시도). */
  @Test
  void execute_redirectToLoopback_failsImmediatelyWithKoreanMessage() {
    // 최초 URL(WireMock)만 검증을 건너뛰고 리다이렉트 대상은 실제 가드로 검증한다.
    SsrfProtectionService realGuardForRedirects =
        new SsrfProtectionService() {
          @Override
          public void validateUrl(String url) {
            if (!url.startsWith(baseUrl())) {
              super.validateUrl(url);
            }
          }
        };
    ApiCallExecutor redirectingExecutor =
        new ApiCallExecutor(
            realGuardForRedirects,
            jsonParser,
            paginationHandler,
            dataTableService,
            dataTableRowService,
            WebClient.builder());
    wireMock.stubFor(
        get(urlEqualTo("/api/redirect"))
            .willReturn(
                aResponse().withStatus(302).withHeader("Location", "http://127.0.0.1:1/meta")));

    assertThatThrownBy(
            () ->
                redirectingExecutor.execute(
                    retryConfig(baseUrl() + "/api/redirect", 2, 5000), "t", null, "APPEND", null))
        .isInstanceOf(SsrfException.class)
        .hasMessage("내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1");
    wireMock.verify(1, getRequestedFor(urlEqualTo("/api/redirect")));
  }

  /** 응답 크기 초과 문구도 한국어이고, URL(쿼리 포함 가능)을 담지 않는다. */
  @Test
  void execute_responseTooLarge_koreanMessage() {
    String big = "{\"items\":[{\"name\":\"" + "x".repeat(1_200_000) + "\"}]}";
    wireMock.stubFor(
        get(urlPathEqualTo("/api/big"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(big)));
    ApiCallConfig base = retryConfig(baseUrl() + "/api/big?serviceKey=SECRET123", 2, 5000);
    ApiCallConfig oneMb =
        new ApiCallConfig(
            base.url(),
            base.method(),
            null,
            null,
            null,
            base.responseFormat(),
            base.dataPath(),
            base.fieldMappings(),
            base.sourceTimezone(),
            null,
            base.retry(),
            base.timeoutMs(),
            base.maxDurationMs(),
            1,
            null,
            null,
            null,
            null);

    assertThatThrownBy(() -> executor.execute(oneMb, "t", null, "APPEND", null))
        .isInstanceOf(ApiCallException.class)
        .hasMessage("응답 크기가 제한(1MB)을 넘었습니다 — 응답 크기 제한을 늘리거나 페이지 크기를 줄이세요");
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/api/big")));
  }

  /** JSON 이 아닌 200 응답(HTML 오류 페이지 등) — Python 실행기와 같은 안내, 재시도하지 않는다. */
  @Test
  void execute_nonJsonResponse_koreanMessage() {
    wireMock.stubFor(
        get(urlEqualTo("/api/html"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html")
                    .withBody("<html><body>Service temporarily unavailable</body></html>")));

    assertThatThrownBy(
            () ->
                executor.execute(
                    retryConfig(baseUrl() + "/api/html", 2, 5000), "t", null, "APPEND", null))
        .isInstanceOf(ApiCallException.class)
        .hasMessage(ApiCallErrorMessages.notJson());
    wireMock.verify(1, getRequestedFor(urlEqualTo("/api/html")));
  }
}
