package com.smartfirehub.pipeline.service.executor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.global.tenant.MissingTenantScopeException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * {@link DbExecutorClient} 단위 테스트(WD-77) — db-executor HTTP 계약(인증 헤더·camelCase 요청·snake_case 응답)과
 * fail-closed 전송 실패 문구를 고정한다.
 */
class DbExecutorClientTest {

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
  void reset() {
    wireMock.resetAll();
    // 실행 스레드는 운영에서 TenantContextTaskDecorator 로 테넌트를 승계한다 — 순수 단위 테스트라 직접 세운다.
    TenantContext.set(5L);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private DbExecutorClient client() {
    return client("http://localhost:" + wireMock.port(), Duration.ofSeconds(10));
  }

  private static DbExecutorClient client(String baseUrl, Duration timeout) {
    return new DbExecutorClient(
        WebClient.builder(), baseUrl, "test-token", timeout, timeout, timeout);
  }

  private static void stubJson(String path, String body) {
    wireMock.stubFor(
        post(urlEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  /** 요청은 내부 토큰 헤더 + 본문 tenantId·query·preStatements(camelCase) 다. */
  @Test
  void executeSql_sendsInternalTokenTenantAndPreStatements() {
    stubJson(
        "/execute/sql",
        """
        {"success": true, "rows": [], "columns": [], "row_count": 3, "execution_log": "3 rows", "error": null, "db_error": null}
        """);

    ExecutorClient.SqlExecuteResult result =
        client().executeSql("INSERT INTO x SELECT 1 FROM dual", List.of("DELETE FROM \"S\".\"T\""));

    assertThat(result.success()).isTrue();
    assertThat(result.rowCount()).isEqualTo(3);
    assertThat(result.executionLog()).isEqualTo("3 rows");
    assertThat(result.dbError()).isNull();
    wireMock.verify(
        postRequestedFor(urlEqualTo("/execute/sql"))
            .withHeader("Authorization", equalTo("Internal test-token"))
            .withRequestBody(
                equalToJson(
                    """
                    {"tenantId": 5, "query": "INSERT INTO x SELECT 1 FROM dual",
                     "preStatements": ["DELETE FROM \\"S\\".\\"T\\""]}
                    """)));
  }

  /** 실행 오류는 HTTP 200 + success=false — 예외가 아니라 결과로 돌려줘 러너가 db_error 코드로 번역한다. */
  @Test
  void executeSql_failureResponse_returnsDbError() {
    stubJson(
        "/execute/sql",
        """
        {"success": false, "rows": null, "columns": null, "row_count": 0, "execution_log": "",
         "error": "JDBC-10007:UNIQUE constraint violation",
         "db_error": {"code": -10007, "message": "JDBC-10007:UNIQUE constraint violation"}}
        """);

    ExecutorClient.SqlExecuteResult result =
        client().executeSql("INSERT INTO x SELECT 1", List.of());

    assertThat(result.success()).isFalse();
    assertThat(result.dbError())
        .isEqualTo(new ExecutorClient.DbError(-10007, "JDBC-10007:UNIQUE constraint violation"));
    assertThat(result.pgError()).isNull();
  }

  @Test
  void probe_sendsTenantAndQuery_parsesSnakeCaseColumns() {
    stubJson(
        "/execute/probe",
        """
        {"success": true, "error": null, "db_error": null, "columns": [
          {"name": "amount", "jdbc_type": 2, "type_name": "NUMBER", "precision": 18, "scale": 6},
          {"name": "Mixed", "jdbc_type": 12, "type_name": "VARCHAR", "precision": null, "scale": null}
        ]}
        """);

    DbExecutorClient.ProbeResult result = client().probe("SELECT amount FROM genia_t5.t");

    assertThat(result.success()).isTrue();
    assertThat(result.columns())
        .containsExactly(
            new DbExecutorClient.ProbeColumn("amount", Types.NUMERIC, "NUMBER", 18, 6),
            new DbExecutorClient.ProbeColumn("Mixed", Types.VARCHAR, "VARCHAR", null, null));
    wireMock.verify(
        postRequestedFor(urlEqualTo("/execute/probe"))
            .withHeader("Authorization", equalTo("Internal test-token"))
            .withRequestBody(
                equalToJson(
                    """
                    {"tenantId": 5, "query": "SELECT amount FROM genia_t5.t"}
                    """)));
  }

  @Test
  void probe_failureResponse_returnsDbError() {
    stubJson(
        "/execute/probe",
        """
        {"success": false, "columns": null, "error": "JDBC-8033:not found",
         "db_error": {"code": -8033, "message": "JDBC-8033:not found"}}
        """);

    DbExecutorClient.ProbeResult result = client().probe("SELECT 1 FROM genia_t5.missing");

    assertThat(result.success()).isFalse();
    assertThat(result.dbError().code()).isEqualTo(-8033);
  }

  /** 토큰 불일치(401)는 실행 실패가 아니라 연결 실패 — 운영 정보 없이 한 문구로 막는다. */
  @Test
  void unauthorized_throwsConnectFailure() {
    wireMock.stubFor(post(urlEqualTo("/execute/sql")).willReturn(aResponse().withStatus(401)));

    assertThatThrownBy(() -> client().executeSql("SELECT 1 FROM dual", List.of()))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage("Tibero 실행기에 연결하지 못했습니다.");
  }

  @Test
  void serverError_throwsConnectFailure() {
    wireMock.stubFor(post(urlEqualTo("/execute/probe")).willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() -> client().probe("SELECT 1 FROM dual"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage("Tibero 실행기에 연결하지 못했습니다.");
  }

  @Test
  void connectionRefused_throwsConnectFailure() throws IOException {
    int closedPort;
    try (ServerSocket s = new ServerSocket(0)) {
      closedPort = s.getLocalPort();
    }

    assertThatThrownBy(
            () ->
                client("http://localhost:" + closedPort, Duration.ofSeconds(5))
                    .executeSql("SELECT 1 FROM dual", List.of()))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage("Tibero 실행기에 연결하지 못했습니다.");
  }

  @Test
  void responseTimeout_throwsConnectFailure() {
    wireMock.stubFor(
        post(urlEqualTo("/execute/sql"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withFixedDelay(2000)
                    .withBody("{\"success\": true}")));

    assertThatThrownBy(
            () ->
                client("http://localhost:" + wireMock.port(), Duration.ofMillis(300))
                    .executeSql("SELECT 1 FROM dual", List.of()))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage("Tibero 실행기에 연결하지 못했습니다.");
  }

  /**
   * 응답 본문이 버퍼 한도(32MB)를 넘으면 연결 실패가 아니라 PG 실행기와 같은 "결과가 너무 큽니다" 안내다(#761). 32MB 넘는 응답을 실제로 만들면 전체
   * 스위트에서 메모리 압박으로 모의 서버가 500 을 내 불안정하므로, WebClient 가 한도 초과 때 던지는 {@link DataBufferLimitException}
   * 을 교환 함수에서 바로 낸다.
   */
  @Test
  void oversizedResponse_throwsTooLargeNotConnectFailure() {
    WebClient.Builder overflowing =
        WebClient.builder()
            .exchangeFunction(
                request -> Mono.error(new DataBufferLimitException("Exceeded limit on max bytes")));
    DbExecutorClient client =
        new DbExecutorClient(
            overflowing,
            "http://localhost:1",
            "test-token",
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5));

    assertThatThrownBy(() -> client.executeSql("SELECT 1 FROM dual", List.of()))
        .isInstanceOf(ExecutorResponseTooLargeException.class)
        .hasMessageContaining("결과가 너무 큽니다");
  }

  /** 주소가 없으면 요청을 보내지 않고 실패한다 — 기동은 막지 않는다(PG 전용 배포). */
  @Test
  void blankBaseUrl_failsClosedWithoutRequest() {
    DbExecutorClient client = client("  ", Duration.ofSeconds(5));

    assertThatThrownBy(() -> client.executeSql("SELECT 1 FROM dual", List.of()))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage("Tibero 실행기에 연결하지 못했습니다.");
    assertThatThrownBy(() -> client.probe("SELECT 1 FROM dual"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage("Tibero 실행기에 연결하지 못했습니다.");
    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  /** 테넌트가 없으면 배선 결함 — 연결 실패 문구로 숨기지 않고 그대로 드러내며 요청도 보내지 않는다. */
  @Test
  void missingTenant_isNotDisguisedAsConnectFailure() {
    TenantContext.clear();

    assertThatThrownBy(() -> client().executeSql("SELECT 1 FROM dual", List.of()))
        .isInstanceOf(MissingTenantScopeException.class);
    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  /** 조회 요청은 tenantId·query·max_rows·read_only 를 싣고, 응답 snake_case 필드가 레코드로 파싱된다. */
  @Test
  void executeQuery_postsTenantQueryMaxRows() {
    stubJson(
        "/execute/query",
        """
        {"success": true, "columns": ["A"], "rows": [{"A": 1}], "row_count": 1, "truncated": false, "error": null, "db_error": null}
        """);

    DbExecutorClient.QueryResult result = client().executeQuery("SELECT 1 FROM DUAL", 100);

    assertThat(result.success()).isTrue();
    assertThat(result.columns()).containsExactly("A");
    assertThat(result.rows()).hasSize(1);
    assertThat(result.rowCount()).isEqualTo(1);
    assertThat(result.truncated()).isFalse();
    assertThat(result.dbError()).isNull();
    wireMock.verify(
        postRequestedFor(urlEqualTo("/execute/query"))
            .withHeader("Authorization", equalTo("Internal test-token"))
            .withRequestBody(
                equalToJson(
                    """
                    {"tenantId":5,"query":"SELECT 1 FROM DUAL","max_rows":100,"read_only":true}
                    """)));
  }

  /** 실행 오류 응답은 예외가 아니라 결과(db_error)로 돌아온다. */
  @Test
  void executeQuery_failureResponse_returnsDbError() {
    stubJson(
        "/execute/query",
        """
        {"success": false, "columns": [], "rows": [], "row_count": 0, "truncated": false, "error": "x", "db_error": {"code": -8026, "message": "JDBC-8026:Invalid identifier."}}
        """);

    DbExecutorClient.QueryResult result = client().executeQuery("SELECT x FROM DUAL", 10);

    assertThat(result.success()).isFalse();
    assertThat(result.dbError())
        .isEqualTo(new ExecutorClient.DbError(-8026, "JDBC-8026:Invalid identifier."));
  }

  /** 연결 거부는 연결 실패 문구로 바뀐다. */
  @Test
  void executeQuery_connectionRefused_throwsConnectFailure() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    DbExecutorClient client = client("http://localhost:" + closedPort, Duration.ofSeconds(5));

    assertThatThrownBy(() -> client.executeQuery("SELECT 1 FROM DUAL", 10))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage(DbExecutorClient.CONNECT_FAILURE_MESSAGE);
  }

  /** 옮기기용 executeSql 은 timeout_seconds 를 본문에 싣는다(실행기가 문장 제한·전체 기한으로 쓴다). */
  @Test
  void executeSql_withTimeout_sendsTimeoutSeconds() {
    stubJson(
        "/execute/sql",
        """
        {"success": true, "row_count": 2, "execution_log": "ok"}
        """);

    ExecutorClient.SqlExecuteResult result =
        client().executeSql("INSERT INTO x SELECT 1 FROM dual", List.of(), 1800);

    assertThat(result.success()).isTrue();
    wireMock.verify(
        postRequestedFor(urlEqualTo("/execute/sql"))
            .withRequestBody(
                equalToJson(
                    """
                    {"tenantId": 5, "query": "INSERT INTO x SELECT 1 FROM dual",
                     "preStatements": [], "timeout_seconds": 1800}
                    """)));
  }

  @Test
  void executeSql_withNonPositiveTimeout_rejectedBeforeSending() {
    assertThatThrownBy(() -> client().executeSql("SELECT 1", List.of(), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }
}
