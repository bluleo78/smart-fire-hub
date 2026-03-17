package com.smartfirehub.pipeline.service.executor;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class ExecutorClientTest {

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

  private ExecutorClient executorClient() {
    return new ExecutorClient(
        WebClient.builder(), "http://localhost:" + wireMock.port(), "test-token");
  }

  // -------------------------------------------------------------------------
  // executeSql
  // -------------------------------------------------------------------------

  @Test
  void executeSql_success() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/sql"))
            .withHeader("Authorization", equalTo("Internal test-token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                            "success": true,
                            "rows": [{"id": 1, "name": "Alice"}],
                            "columns": ["id", "name"],
                            "row_count": 1,
                            "execution_log": "1 row(s) returned",
                            "error": null
                        }
                        """)));

    var result = executorClient().executeSql("SELECT * FROM test");

    assertThat(result.success()).isTrue();
    assertThat(result.rowCount()).isEqualTo(1);
    assertThat(result.columns()).containsExactly("id", "name");
    assertThat(result.rows()).hasSize(1);
    assertThat(result.executionLog()).isEqualTo("1 row(s) returned");
    assertThat(result.error()).isNull();
  }

  @Test
  void executeSql_errorResponse() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/sql"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                            "success": false,
                            "rows": null,
                            "columns": null,
                            "row_count": 0,
                            "execution_log": "",
                            "error": "SQL 스크립트에 차단된 키워드가 포함되어 있습니다: DROP"
                        }
                        """)));

    var result = executorClient().executeSql("DROP TABLE users");

    assertThat(result.success()).isFalse();
    assertThat(result.error()).contains("DROP");
  }

  @Test
  void executeSql_serverError_throwsException() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/sql"))
            .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

    assertThatThrownBy(() -> executorClient().executeSql("SELECT 1")).isInstanceOf(Exception.class);
  }

  // -------------------------------------------------------------------------
  // executePython
  // -------------------------------------------------------------------------

  @Test
  void executePython_success() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/python"))
            .withHeader("Authorization", equalTo("Internal test-token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                            "success": true,
                            "output": "hello\\n",
                            "exit_code": 0,
                            "error": null,
                            "execution_time_ms": 150,
                            "rows_loaded": 0
                        }
                        """)));

    var result = executorClient().executePython(Map.of("script", "print('hello')"));

    assertThat(result.success()).isTrue();
    assertThat(result.output()).isEqualTo("hello\n");
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.executionTimeMs()).isEqualTo(150);
    assertThat(result.error()).isNull();
    assertThat(result.rowsLoaded()).isEqualTo(0);
  }

  @Test
  void executePython_withMapRequest_sendsCorrectBody() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/python"))
            .withHeader("Authorization", equalTo("Internal test-token"))
            .withRequestBody(matchingJsonPath("$.script"))
            .withRequestBody(matchingJsonPath("$.output_table"))
            .withRequestBody(matchingJsonPath("$.column_type_map"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                            "success": true,
                            "output": "",
                            "exit_code": 0,
                            "error": null,
                            "execution_time_ms": 200,
                            "rows_loaded": 5
                        }
                        """)));

    var result =
        executorClient()
            .executePython(
                Map.of(
                    "script", "print('done')",
                    "output_table", "my_table_tmp",
                    "column_type_map", Map.of("col1", "TEXT", "col2", "INTEGER")));

    assertThat(result.success()).isTrue();
    assertThat(result.rowsLoaded()).isEqualTo(5);
  }

  @Test
  void executePython_returnsRowsLoaded() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/python"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                            "success": true,
                            "output": "loaded 42 rows",
                            "exit_code": 0,
                            "error": null,
                            "execution_time_ms": 300,
                            "rows_loaded": 42
                        }
                        """)));

    var result =
        executorClient().executePython(Map.of("script", "import json; print(json.dumps([]))"));

    assertThat(result.success()).isTrue();
    assertThat(result.rowsLoaded()).isEqualTo(42);
  }

  @Test
  void executePython_errorResponse() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/python"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                            "success": false,
                            "output": "",
                            "exit_code": 1,
                            "error": "SyntaxError: invalid syntax",
                            "execution_time_ms": 50,
                            "rows_loaded": 0
                        }
                        """)));

    var result = executorClient().executePython(Map.of("script", "invalid python !!!@#"));

    assertThat(result.success()).isFalse();
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.error()).contains("SyntaxError");
  }

  @Test
  void executePython_serverError_throwsException() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/python"))
            .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

    assertThatThrownBy(() -> executorClient().executePython(Map.of("script", "print('hello')")))
        .isInstanceOf(Exception.class);
  }

  // -------------------------------------------------------------------------
  // executeQuery
  // -------------------------------------------------------------------------

  @Test
  void executeQuery_success() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/query"))
            .withHeader("Authorization", equalTo("Internal test-token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                            "success": true,
                            "query_type": "SELECT",
                            "columns": ["count"],
                            "rows": [{"count": 42}],
                            "row_count": 1,
                            "affected_rows": 0,
                            "execution_time_ms": 25,
                            "truncated": false,
                            "error": null
                        }
                        """)));

    var result = executorClient().executeQuery("SELECT count(*) FROM test", 1000, true);

    assertThat(result.success()).isTrue();
    assertThat(result.queryType()).isEqualTo("SELECT");
    assertThat(result.columns()).containsExactly("count");
    assertThat(result.rowCount()).isEqualTo(1);
    assertThat(result.affectedRows()).isEqualTo(0);
    assertThat(result.truncated()).isFalse();
    assertThat(result.error()).isNull();
  }

  @Test
  void executeQuery_errorResponse() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/query"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                            "success": false,
                            "query_type": null,
                            "columns": null,
                            "rows": null,
                            "row_count": 0,
                            "affected_rows": 0,
                            "execution_time_ms": 10,
                            "truncated": false,
                            "error": "statement timeout exceeded"
                        }
                        """)));

    var result = executorClient().executeQuery("SELECT pg_sleep(60)", 1000, true);

    assertThat(result.success()).isFalse();
    assertThat(result.error()).contains("timeout");
  }

  @Test
  void executeQuery_serverError_throwsException() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/query"))
            .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

    assertThatThrownBy(() -> executorClient().executeQuery("SELECT count(*) FROM test", 1000, true))
        .isInstanceOf(Exception.class);
  }

  // -------------------------------------------------------------------------
  // executeApiCall
  // -------------------------------------------------------------------------

  @Test
  void executeApiCall_success() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/api-call"))
            .withHeader("Authorization", equalTo("Internal test-token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                            "success": true,
                            "rows_loaded": 100,
                            "total_pages": 1,
                            "execution_log": "url=https://api.example.com method=GET pages=1 rows=100 duration=500ms",
                            "error": null,
                            "execution_time_ms": 500
                        }
                        """)));

    var result =
        executorClient()
            .executeApiCall(
                Map.of(
                    "url", "https://api.example.com",
                    "data_path", "$.data",
                    "output_table", "test_table",
                    "field_mappings", List.of()));

    assertThat(result.success()).isTrue();
    assertThat(result.rowsLoaded()).isEqualTo(100);
    assertThat(result.totalPages()).isEqualTo(1);
    assertThat(result.executionTimeMs()).isEqualTo(500);
    assertThat(result.error()).isNull();
  }

  @Test
  void executeApiCall_ssrfError() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/api-call"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                            "success": false,
                            "rows_loaded": 0,
                            "total_pages": 0,
                            "execution_log": "",
                            "error": "SSRF: Requests to loopback addresses are not allowed",
                            "execution_time_ms": 5
                        }
                        """)));

    var result =
        executorClient()
            .executeApiCall(
                Map.of(
                    "url", "http://127.0.0.1/secret",
                    "data_path", "$.data",
                    "output_table", "test_table",
                    "field_mappings", List.of()));

    assertThat(result.success()).isFalse();
    assertThat(result.error()).contains("SSRF");
    assertThat(result.rowsLoaded()).isEqualTo(0);
  }

  @Test
  void executeApiCall_serverError_throwsException() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/api-call"))
            .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

    assertThatThrownBy(
            () ->
                executorClient()
                    .executeApiCall(
                        Map.of(
                            "url", "https://api.example.com",
                            "data_path", "$.data",
                            "output_table", "test_table",
                            "field_mappings", List.of())))
        .isInstanceOf(Exception.class);
  }
}
