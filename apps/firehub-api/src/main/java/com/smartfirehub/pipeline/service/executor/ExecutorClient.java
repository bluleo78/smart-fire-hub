package com.smartfirehub.pipeline.service.executor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartfirehub.global.tenant.TenantContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class ExecutorClient {

  private final WebClient webClient;

  public ExecutorClient(
      WebClient.Builder webClientBuilder,
      @Value("${app.executor.base-url:http://localhost:8000}") String executorBaseUrl,
      @Value("${app.executor.internal-token:dev-executor-token}") String executorToken) {
    this.webClient =
        webClientBuilder
            .baseUrl(executorBaseUrl)
            .defaultHeader("Authorization", "Internal " + executorToken)
            .build();
  }

  /**
   * 요청 본문에 현재 테넌트 id 를 실어 준다.
   *
   * <p><b>왜 필요한가.</b> executor 는 별도 프로세스(Python)라 API 의 {@link TenantContext}
   * ThreadLocal 을 볼 수 없다. 그런데 executor 는 사용자 SQL·Python 을 <b>DB 에 직접</b> 실행하므로,
   * 어느 테넌트의 자격증명·스키마로 접속해야 하는지 알아야 한다. 그 유일한 전달 경로가 요청 본문이다
   * (필드명은 {@code tenantId} camelCase — executor 쪽이 이 이름을 소비한다).
   *
   * <p>테넌트가 없으면 <b>fail-closed</b> 로 즉시 예외를 던진다. 조용히 기본 테넌트로 떨어지면
   * 배경 잡·트리거 경로가 남의 데이터에 쓰게 된다.
   *
   * <p>호출부가 넘긴 맵을 그대로 수정하지 않고 복사한다 — {@code Map.of(...)} 같은 불변 맵이 들어오고,
   * 호출부의 맵을 몰래 바꾸면 재시도 로직에서 추적하기 어려운 부작용이 된다.
   */
  private Map<String, Object> withTenant(Map<String, Object> request) {
    Map<String, Object> body = new LinkedHashMap<>(request);
    body.put("tenantId", TenantContext.require("executor 실행 요청"));
    return body;
  }

  /**
   * Python 실행 요청. POST /execute/python Timeout: 1890s (30분 nsjail + 60s subprocess + 30s HTTP
   * buffer)
   */
  public PythonExecuteResult executePython(Map<String, Object> request) {
    return webClient
        .post()
        .uri("/execute/python")
        .bodyValue(withTenant(request))
        .retrieve()
        .bodyToMono(PythonExecuteResult.class)
        .timeout(Duration.ofSeconds(1890))
        .block();
  }

  /** 분석 쿼리 실행 요청. POST /execute/query Timeout: 35s (30s statement_timeout + 5s buffer) */
  public QueryExecuteResult executeQuery(String query, int maxRows, boolean readOnly) {
    return webClient
        .post()
        .uri("/execute/query")
        .bodyValue(withTenant(Map.of("query", query, "max_rows", maxRows, "read_only", readOnly)))
        .retrieve()
        .bodyToMono(QueryExecuteResult.class)
        .timeout(Duration.ofSeconds(35))
        .block();
  }

  /** SQL 실행 요청. POST /execute/sql Timeout: 60s */
  public SqlExecuteResult executeSql(String query) {
    return webClient
        .post()
        .uri("/execute/sql")
        .bodyValue(withTenant(Map.of("query", query)))
        .retrieve()
        .bodyToMono(SqlExecuteResult.class)
        .timeout(Duration.ofSeconds(60))
        .block();
  }

  /** API_CALL 실행 요청. POST /execute/api-call Timeout: 3660s (1시간 + 60초 버퍼) */
  public ApiCallExecuteResult executeApiCall(Map<String, Object> request) {
    return webClient
        .post()
        .uri("/execute/api-call")
        .bodyValue(withTenant(request))
        .retrieve()
        .bodyToMono(ApiCallExecuteResult.class)
        .timeout(Duration.ofSeconds(3660))
        .block();
  }

  public record PythonExecuteResult(
      boolean success,
      String output,
      @JsonProperty("exit_code") int exitCode,
      String error,
      @JsonProperty("execution_time_ms") long executionTimeMs,
      @JsonProperty("rows_loaded") int rowsLoaded) {}

  public record QueryExecuteResult(
      boolean success,
      @JsonProperty("query_type") String queryType,
      List<String> columns,
      List<Map<String, Object>> rows,
      @JsonProperty("row_count") int rowCount,
      @JsonProperty("affected_rows") int affectedRows,
      @JsonProperty("execution_time_ms") long executionTimeMs,
      boolean truncated,
      String error) {}

  public record SqlExecuteResult(
      boolean success,
      List<Map<String, Object>> rows,
      List<String> columns,
      @JsonProperty("row_count") int rowCount,
      @JsonProperty("execution_log") String executionLog,
      String error) {}

  public record ApiCallExecuteResult(
      boolean success,
      @JsonProperty("rows_loaded") int rowsLoaded,
      @JsonProperty("total_pages") int totalPages,
      @JsonProperty("execution_log") String executionLog,
      String error,
      @JsonProperty("execution_time_ms") long executionTimeMs) {}
}
