package com.smartfirehub.pipeline.service.executor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class ExecutorClient {

  /**
   * executor 응답 본문 버퍼 한도 기본값 — 32MB (#761).
   *
   * <p><b>왜 필요한가.</b> 한도를 지정하지 않으면 Spring WebClient 기본값 256KB 가 적용돼, 기본 maxRows=1000 조회도 행당 약
   * 260바이트(텍스트 컬럼 8개 정도)만 넘으면 {@link DataBufferLimitException} 으로 실패했다(직접 경로는 정상이라 운영 기본 경로만 깨졌다).
   *
   * <p><b>값의 근거.</b> 이 클라이언트로 오는 가장 큰 응답은 애드혹·저장 쿼리·차트 결과({@code /execute/query}) 이고, 행 수 상한은
   * {@code AnalyticsQueryRequest} 의 {@code @Max(10000)} 이다(차트는 1000행 고정). 10,000행 × 행당 약 3.3KB(32자
   * 텍스트 컬럼 수십 개 수준의 넓은 행)를 수용하는 값이 32MB 다. 같은 저장소의 다른 대용량 응답 클라이언트({@code OntologyService}·{@code
   * EmbeddingProviderFactory})도 32MB 를 쓴다. 파이프라인 SQL 스텝은 SELECT 를 INSERT 로 감싸 행을 돌려받지 않고, 메트릭 폴러는
   * 1행만 받으므로 이 값의 영향을 받지 않는다. Python 스텝의 stdout(출력 테이블 없음)도 같은 한도를 받는다.
   *
   * <p><b>무제한(-1) 금지.</b> executor 쪽에는 행 수({@code max_rows}) 상한만 있고 응답 바이트 상한이 없다 — 이 한도가 API 메모리를
   * 지키는 유일한 장치다. 한도를 넘으면 {@link ExecutorResponseTooLargeException} 으로 "결과가 너무 큽니다" 안내를 돌려준다. 더 넓은
   * 결과가 필요하면 {@code app.executor.max-response-bytes} 로 조정한다.
   */
  public static final int DEFAULT_MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

  private final WebClient webClient;
  private final int maxResponseBytes;

  public ExecutorClient(
      WebClient.Builder webClientBuilder,
      @Value("${app.executor.base-url:http://localhost:8000}") String executorBaseUrl,
      @Value("${app.executor.internal-token:dev-executor-token}") String executorToken,
      @Value("${app.executor.max-response-bytes:" + DEFAULT_MAX_RESPONSE_BYTES + "}")
          int maxResponseBytes) {
    if (maxResponseBytes <= 0) {
      // -1(무제한)·0 은 메모리 보호를 없애거나 모든 응답을 실패시킨다 — 설정 실수를 기동 시점에 드러낸다.
      throw new IllegalArgumentException(
          "app.executor.max-response-bytes 는 양수여야 합니다: " + maxResponseBytes);
    }
    this.maxResponseBytes = maxResponseBytes;
    this.webClient =
        webClientBuilder
            .baseUrl(executorBaseUrl)
            .defaultHeader("Authorization", "Internal " + executorToken)
            .codecs(c -> c.defaultCodecs().maxInMemorySize(maxResponseBytes))
            .build();
  }

  /**
   * 요청을 실행하고, 응답 본문이 버퍼 한도를 넘어 실패했으면 {@link ExecutorResponseTooLargeException} 으로 바꿔 던진다(#761).
   *
   * <p>WebClient 는 한도 초과를 {@code WebClientResponseException}("200 OK … but response failed with
   * cause …") 등으로 감싸 던지므로 원인 사슬을 따라가 {@link DataBufferLimitException} 을 찾는다. 그 밖의 예외는 그대로 전파한다(호출부의
   * 기존 "연결 실패" 처리 유지).
   */
  private <T> T guardSize(Supplier<T> call) {
    try {
      return call.get();
    } catch (RuntimeException e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof DataBufferLimitException) {
          throw new ExecutorResponseTooLargeException(maxResponseBytes, e);
        }
      }
      throw e;
    }
  }

  /**
   * 요청 본문에 현재 테넌트 id 를 실어 준다.
   *
   * <p><b>왜 필요한가.</b> executor 는 별도 프로세스(Python)라 API 의 {@link TenantContext} ThreadLocal 을 볼 수 없다.
   * 그런데 executor 는 사용자 SQL·Python 을 <b>DB 에 직접</b> 실행하므로, 어느 테넌트의 자격증명·스키마로 접속해야 하는지 알아야 한다. 그 유일한
   * 전달 경로가 요청 본문이다 (필드명은 {@code tenantId} camelCase — executor 쪽이 이 이름을 소비한다).
   *
   * <p>테넌트가 없으면 <b>fail-closed</b> 로 즉시 예외를 던진다. 조용히 기본 테넌트로 떨어지면 배경 잡·트리거 경로가 남의 데이터에 쓰게 된다.
   *
   * <p>호출부가 넘긴 맵을 그대로 수정하지 않고 복사한다 — {@code Map.of(...)} 같은 불변 맵이 들어오고, 호출부의 맵을 몰래 바꾸면 재시도 로직에서
   * 추적하기 어려운 부작용이 된다.
   */
  private Map<String, Object> withTenant(Map<String, Object> request) {
    Map<String, Object> body = new LinkedHashMap<>(request);
    body.put("tenantId", TenantContext.require("executor 실행 요청"));
    return body;
  }

  /**
   * Python 실행 요청. POST /execute/python Timeout: 1890s (30분 nsjail + 60s subprocess + 30s HTTP
   * buffer)
   *
   * @param readSlot 실행 주체의 읽기 슬롯(1~10, PythonReadGrantSync.prepareForRun). executor 는 이 슬롯 롤로 스크립트를
   *     접속시킨다 — 없거나 범위 밖이면 executor 가 422 로 거부한다(테넌트 롤 폴백 없음, WD-29). 여기서도 먼저 막아 잘못된 값이 네트워크로 나가지
   *     않게 한다.
   */
  public PythonExecuteResult executePython(Map<String, Object> request, int readSlot) {
    if (readSlot < 1 || readSlot > TenantPipelineRole.PYTHON_READ_SLOTS) {
      throw new IllegalArgumentException(
          "readSlot 은 1~" + TenantPipelineRole.PYTHON_READ_SLOTS + " 이어야 합니다: " + readSlot);
    }
    Map<String, Object> body = withTenant(request); // withTenant 가 이미 복사본을 돌려준다
    body.put("readSlot", readSlot);
    return guardSize(
        () ->
            webClient
                .post()
                .uri("/execute/python")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(PythonExecuteResult.class)
                .timeout(Duration.ofSeconds(1890))
                .block());
  }

  /** 분석 쿼리 실행 요청. POST /execute/query Timeout: 35s (30s statement_timeout + 5s buffer) */
  public QueryExecuteResult executeQuery(String query, int maxRows, boolean readOnly) {
    return guardSize(
        () ->
            webClient
                .post()
                .uri("/execute/query")
                .bodyValue(
                    withTenant(Map.of("query", query, "max_rows", maxRows, "read_only", readOnly)))
                .retrieve()
                .bodyToMono(QueryExecuteResult.class)
                .timeout(Duration.ofSeconds(35))
                .block());
  }

  /** SQL 실행 요청(선행 문장 없음). {@link #executeSql(String, List)} 에 빈 목록으로 위임한다. */
  public SqlExecuteResult executeSql(String query) {
    return executeSql(query, List.of());
  }

  /**
   * SQL 실행 요청. POST /execute/sql Timeout: 60s
   *
   * <p><b>선행 문장(preStatements)이란.</b> Task 4 — REPLACE 전략의 SQL 스텝은 "출력 비우기(DELETE) + INSERT"를 한
   * 트랜잭션으로 묶어야 한다. 따로 요청을 두 번 보내면(비우기 커밋 → INSERT 별도 요청) INSERT 가 실패했을 때 출력 테이블이 <b>빈 채로</b> 남는다(원래
   * 결함). 그래서 비우기 문장을 본 쿼리와 같은 요청에 실어 executor 가 같은 트랜잭션에서 순서대로 실행하게 한다({@code SqlScriptExecutor} 의
   * 실행기 비활성 경로도 동일 계약을 따른다).
   *
   * <p>HTTP 필드명은 {@code preStatements}(camelCase) — executor(Python) 쪽 Pydantic 모델이 이 이름을 alias 로
   * 받는다(Task 3). executor 는 각 선행 문장을 엄격한 화이트리스트 정규식으로 재검증하므로, 여기서는 호출부가 만든 문자열을 그대로 전달한다({@link
   * com.smartfirehub.pipeline.service.OutputClearStatement} 가 그 형태를 보장하는 유일한 지점).
   *
   * @param query 본 SQL 쿼리
   * @param preStatements 본 쿼리보다 먼저, 같은 트랜잭션으로 실행할 문장 목록(순서 보존). 없으면 빈 목록.
   */
  public SqlExecuteResult executeSql(String query, List<String> preStatements) {
    return guardSize(
        () ->
            webClient
                .post()
                .uri("/execute/sql")
                .bodyValue(withTenant(Map.of("query", query, "preStatements", preStatements)))
                .retrieve()
                .bodyToMono(SqlExecuteResult.class)
                .timeout(Duration.ofSeconds(60))
                .block());
  }

  /** API_CALL 실행 요청. POST /execute/api-call Timeout: 3660s (1시간 + 60초 버퍼) */
  public ApiCallExecuteResult executeApiCall(Map<String, Object> request) {
    return guardSize(
        () ->
            webClient
                .post()
                .uri("/execute/api-call")
                .bodyValue(withTenant(request))
                .retrieve()
                .bodyToMono(ApiCallExecuteResult.class)
                .timeout(Duration.ofSeconds(3660))
                .block());
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
