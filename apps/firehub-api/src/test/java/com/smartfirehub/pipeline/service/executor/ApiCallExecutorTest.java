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
            null);

    assertThatThrownBy(() -> executor.execute(config, "t", null, "APPEND", null))
        .isInstanceOf(ApiCallException.class)
        .hasMessageContaining("retries");

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
            null);

    assertThatThrownBy(() -> executor.execute(config, "t", null, "APPEND", null))
        .isInstanceOf(ApiCallException.class)
        .hasMessageContaining("Data path not found");
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
            null);

    ApiCallExecutor.ApiCallResult result =
        executor.execute(config, "rep_table", null, "REPLACE", null);

    assertThat(result.totalRows()).isEqualTo(2);

    // Must create the temp table first
    verify(dataTableService).createTempTable("rep_table");

    // Rows must be inserted into the _tmp table, not the original
    verify(dataTableRowService).insertBatch(eq("rep_table_tmp"), anyList(), anyList());
    verify(dataTableRowService, never()).insertBatch(eq("rep_table"), anyList(), anyList());

    // On success, swap must be called
    verify(dataTableService).swapTable("rep_table");

    // dropTempTable must NOT be called on success
    verify(dataTableService, never()).dropTempTable(any());

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
            null);

    assertThatThrownBy(() -> executor.execute(config, "rep_fail_table", null, "REPLACE", null))
        .isInstanceOf(ApiCallException.class);

    // Temp table must be created and then dropped on failure
    verify(dataTableService).createTempTable("rep_fail_table");
    verify(dataTableService).dropTempTable("rep_fail_table");

    // swap must NOT be called
    verify(dataTableService, never()).swapTable(any());
  }
}
