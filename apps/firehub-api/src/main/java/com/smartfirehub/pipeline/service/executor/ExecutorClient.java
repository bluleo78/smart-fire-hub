package com.smartfirehub.pipeline.service.executor;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
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
   * Python 실행 요청.
   * POST /execute/python
   * Timeout: 1890s (30분 nsjail + 60s subprocess + 30s HTTP buffer)
   */
  public PythonExecuteResult executePython(String script) {
    return webClient
        .post()
        .uri("/execute/python")
        .bodyValue(Map.of("script", script))
        .retrieve()
        .bodyToMono(PythonExecuteResult.class)
        .timeout(Duration.ofSeconds(1890))
        .block();
  }

  /**
   * 분석 쿼리 실행 요청.
   * POST /execute/query
   * Timeout: 35s (30s statement_timeout + 5s buffer)
   */
  public QueryExecuteResult executeQuery(String query, int maxRows, boolean readOnly) {
    return webClient
        .post()
        .uri("/execute/query")
        .bodyValue(Map.of("query", query, "max_rows", maxRows, "read_only", readOnly))
        .retrieve()
        .bodyToMono(QueryExecuteResult.class)
        .timeout(Duration.ofSeconds(35))
        .block();
  }

  public record PythonExecuteResult(
      boolean success,
      String output,
      @JsonProperty("exit_code") int exitCode,
      String error,
      @JsonProperty("execution_time_ms") long executionTimeMs) {}

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
}
