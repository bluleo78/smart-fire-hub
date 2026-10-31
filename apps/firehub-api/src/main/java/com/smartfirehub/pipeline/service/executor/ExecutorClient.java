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

  /**
   * Python 실행 HTTP 대기 여유(초) — 실행기 시간 제한({@code app.executor.python-timeout-seconds}) 뒤에 자식 프로세스
   * 정리(60초)와 HTTP 버퍼(30초)를 더한다. 기본 1800초면 예전 고정값 1890초와 같다.
   */
  static final long PYTHON_HTTP_TIMEOUT_MARGIN_SECONDS = 90;

  private final WebClient webClient;
  private final int maxResponseBytes;

  /** Python 실행 요청의 HTTP 대기 한도 — 실행기 시간 제한 + {@link #PYTHON_HTTP_TIMEOUT_MARGIN_SECONDS}. */
  private final Duration pythonTimeout;

  /** {@code /execute/sql} 기본 HTTP 대기 — 실행기의 본문 문장 제한(기본 50초)에 여유 10초. */
  static final Duration SQL_TIMEOUT = Duration.ofSeconds(60);

  /**
   * 선행 문장(REPLACE 출력 잠금·비우기 DELETE)이 있는 {@code /execute/sql} 요청이 더 기다리는 시간 — Python 실행기의 선행
   * 상한({@code EXECUTOR_SQL_PRE_STATEMENT_TIMEOUT_SECONDS})과 같은 값이어야 한다(WD-262).
   */
  private final Duration preStatementTimeout;

  /**
   * 선행 문장 상한 기본값(초) — Python 실행기 {@code EXECUTOR_SQL_PRE_STATEMENT_TIMEOUT_SECONDS} 기본값·계약 벡터
   * {@code expectedPreStatementTimeoutSeconds} 와 같다(WD-262). 생성자 {@code @Value} 기본값이 이 상수를 쓰므로 테스트는
   * 이 상수만 벡터와 대조한다.
   */
  static final int DEFAULT_PRE_STATEMENT_TIMEOUT_SECONDS = 1800;

  /**
   * @param preStatementTimeoutSeconds 선행 문장 상한({@code app.executor.pre-statement-timeout-seconds},
   *     기본 1800) — Python 실행기 {@code EXECUTOR_SQL_PRE_STATEMENT_TIMEOUT_SECONDS} 와 같은 값이어야 한다. 1
   *     미만이면 기동을 거부한다.
   */
  public ExecutorClient(
      WebClient.Builder webClientBuilder,
      @Value("${app.executor.base-url:http://localhost:8000}") String executorBaseUrl,
      @Value("${app.executor.internal-token:dev-executor-token}") String executorToken,
      @Value("${app.executor.max-response-bytes:" + DEFAULT_MAX_RESPONSE_BYTES + "}")
          int maxResponseBytes,
      @Value("${app.executor.python-timeout-seconds:1800}") long pythonTimeoutSeconds,
      @Value(
              "${app.executor.pre-statement-timeout-seconds:"
                  + DEFAULT_PRE_STATEMENT_TIMEOUT_SECONDS
                  + "}")
          int preStatementTimeoutSeconds) {
    if (maxResponseBytes <= 0) {
      // -1(무제한)·0 은 메모리 보호를 없애거나 모든 응답을 실패시킨다 — 설정 실수를 기동 시점에 드러낸다.
      throw new IllegalArgumentException(
          "app.executor.max-response-bytes 는 양수여야 합니다: " + maxResponseBytes);
    }
    if (preStatementTimeoutSeconds < 1) {
      // 0 이하로 두면 선행 문장이 있는 요청도 60초만 기다려, 큰 출력을 비우는 동안 api 가 먼저 포기한다 — 설정 실수를 기동 시점에 드러낸다.
      throw new IllegalArgumentException(
          "app.executor.pre-statement-timeout-seconds 는 1 이상이어야 합니다: "
              + preStatementTimeoutSeconds);
    }
    this.preStatementTimeout = Duration.ofSeconds(preStatementTimeoutSeconds);
    this.maxResponseBytes = maxResponseBytes;
    // 실행기 시간 제한을 늘리면 API 의 대기도 같이 늘어난다 — 따로 고정하면 실행기가 아직 도는데 API 가 먼저 끊는다.
    this.pythonTimeout =
        Duration.ofSeconds(pythonTimeoutSeconds + PYTHON_HTTP_TIMEOUT_MARGIN_SECONDS);
    this.webClient =
        internalClient(webClientBuilder, executorBaseUrl, executorToken, maxResponseBytes);
  }

  /**
   * 내부 실행기(Python executor·db-executor) 호출용 WebClient — 내부 토큰 인증({@code Authorization: Internal
   * <token>})과 응답 본문 버퍼 한도를 건다. 두 실행기 클라이언트가 같은 인증 방식·메모리 보호 정책을 쓰도록 여기 한 곳에서 만든다.
   */
  static WebClient internalClient(
      WebClient.Builder builder, String baseUrl, String internalToken, int maxResponseBytes) {
    return builder
        .baseUrl(baseUrl)
        .defaultHeader("Authorization", "Internal " + internalToken)
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
    return guardSize(maxResponseBytes, call);
  }

  /** {@link #guardSize(Supplier)} 의 공용판 — db-executor 클라이언트도 같은 크기 초과 판정을 쓴다. */
  static <T> T guardSize(int maxResponseBytes, Supplier<T> call) {
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
    return withTenant(request, "executor 실행 요청");
  }

  /**
   * {@link #withTenant(Map)} 의 공용판 — db-executor 클라이언트도 같은 필드명({@code tenantId})·fail-closed 규칙으로
   * 싣는다.
   *
   * @param purpose 테넌트가 없을 때 예외 문구에 넣을 호출 목적
   */
  static Map<String, Object> withTenant(Map<String, Object> request, String purpose) {
    Map<String, Object> body = new LinkedHashMap<>(request);
    body.put("tenantId", TenantContext.require(purpose));
    return body;
  }

  /**
   * Python 실행 요청. POST /execute/python Timeout: 실행기 시간 제한(기본 1800s, nsjail) + 60s subprocess + 30s
   * HTTP buffer — 기본값이면 1890s.
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
                .timeout(pythonTimeout)
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
   * SQL 실행 요청. POST /execute/sql Timeout: 60s, 선행 문장이 있으면 60s + 선행 상한({@link #sqlWait(List)})
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
    return executeSql(query, preStatements, null);
  }

  /**
   * {@link #executeSql(String, List)} 에 파이프라인 실행 id 를 더한 판(WD-466).
   *
   * <p><b>왜.</b> 실행기는 선행 문장 앞에 출력 잠금을 잡고, 같은 출력을 쓰는 다른 실행이 끝날 때까지 기다린다. 그 대기 중에 들어온 취소는 api 러너의
   * 「SQL 실행 직전」 체크포인트보다 뒤라 보이지 않았고, 잠금을 얻은 뒤 사용자 SQL 이 끝까지 돌아 출력을 다시 교체했다. 실행 id 를 실으면 실행기가 잠금을 얻은
   * 직후 api({@code /internal/executor/tenants/{tenantId}/pipeline-executions/{id}/stop-requested})에
   * 취소 여부를 되묻고, 취소면 롤백한 뒤 {@link SqlExecuteResult#cancelled()} 로 알린다.
   *
   * @param executionId 파이프라인 실행 id — null 이면 싣지 않는다(되묻지 않는다, 기존 동작)
   */
  public SqlExecuteResult executeSql(String query, List<String> preStatements, Long executionId) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("query", query);
    request.put("preStatements", preStatements);
    if (executionId != null) {
      request.put("executionId", executionId);
    }
    return guardSize(
        () ->
            webClient
                .post()
                .uri("/execute/sql")
                .bodyValue(withTenant(request))
                .retrieve()
                .bodyToMono(SqlExecuteResult.class)
                .timeout(sqlWait(preStatements))
                .block());
  }

  /**
   * {@code /execute/sql} 의 HTTP 대기: 60초, 선행 문장이 있으면 + 선행 상한(WD-262). {@code
   * DbExecutorClient#sqlWait} 와 같은 규칙({@link ExecutorWaits#sqlWait})이다.
   *
   * <p>왜: Python 실행기는 선행 문장(출력 잠금·비우기 DELETE)에 별도 합계 상한(기본 1800초)을 주고 본문에는 본문 제한(기본 50초)을 따로 준다.
   * 실행기가 끝나는 최악 시점은 「선행 상한 + 본문 제한」이므로 api 의 대기를 그만큼 늘리지 않으면 큰 출력을 비우는 동안 api 가 먼저 포기해 단계를 실패로 적은 뒤
   * 실행기가 커밋할 수 있다.
   *
   * <p>로컬 실행기 비활성 경로({@code SqlScriptExecutor})는 이 한도를 받지 않는다(제한 없음).
   */
  Duration sqlWait(List<String> preStatements) {
    return ExecutorWaits.sqlWait(SQL_TIMEOUT, preStatementTimeout, preStatements);
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

  /**
   * Python 실행 응답.
   *
   * @param pgError 출력 적재가 PostgreSQL 오류로 실패했을 때의 구조화 필드(WD-211) — 데이터 오류(SQLState 22)면 러너가 {@code
   *     PythonStepErrorMessages#fromExecutor} 로 한국어 사유 문구를 만든다. 적재 실패가 아니거나 구버전 실행기면 null 이고, 그때는
   *     {@code error} 문자열만 쓴다.
   */
  public record PythonExecuteResult(
      boolean success,
      String output,
      @JsonProperty("exit_code") int exitCode,
      String error,
      @JsonProperty("execution_time_ms") long executionTimeMs,
      @JsonProperty("rows_loaded") int rowsLoaded,
      @JsonProperty("pg_error") PgError pgError) {

    /** pg_error 없는 응답(성공·적재 외 실패) — 호출부·테스트 편의용. */
    public PythonExecuteResult(
        boolean success,
        String output,
        int exitCode,
        String error,
        long executionTimeMs,
        int rowsLoaded) {
      this(success, output, exitCode, error, executionTimeMs, rowsLoaded, null);
    }
  }

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

  /**
   * SQL 실행 응답.
   *
   * @param pgError 실패가 PostgreSQL 오류일 때의 구조화 필드(SQLState·주 메시지·DETAIL·HINT·컬럼, WD-111) — 스텝 오류를
   *     SQLState 로 분류한 한국어 안내 + 원문으로 만드는 데 쓴다. 검증 거부처럼 PG 오류가 아니거나 이 필드를 보내지 않는 구버전 실행기면 null 이고,
   *     그때는 {@code error} 문자열만 쓴다.
   * @param dbError 실패가 Tibero 등 JDBC 저장소 오류일 때의 코드·메시지(firehub-db-executor 응답의 {@code db_error},
   *     WD-77). PG 실행기(firehub-executor)는 이 필드를 보내지 않으므로 PG 경로에서는 언제나 null 이다.
   * @param cancelled 실행기가 출력 잠금을 얻은 뒤 파이프라인 취소 요청을 확인해 롤백했는가(WD-466) — true 면 출력은 손대지 않았고 러너는 스텝을
   *     실패가 아니라 CANCELLED 로 기록한다. 실행 id 를 싣지 않은 요청·db-executor 응답에는 없어 false 다.
   */
  public record SqlExecuteResult(
      boolean success,
      List<Map<String, Object>> rows,
      List<String> columns,
      @JsonProperty("row_count") int rowCount,
      @JsonProperty("execution_log") String executionLog,
      String error,
      @JsonProperty("pg_error") PgError pgError,
      @JsonProperty("db_error") DbError dbError,
      boolean cancelled) {

    /**
     * 취소 표시가 없는 결과(기존 8필드 모양) — db-executor·테스트 등 취소를 되묻지 않는 경로가 쓴다. 응답에 {@code cancelled} 가 없으면
     * 역직렬화도 false 다.
     */
    public SqlExecuteResult(
        boolean success,
        List<Map<String, Object>> rows,
        List<String> columns,
        int rowCount,
        String executionLog,
        String error,
        PgError pgError,
        DbError dbError) {
      this(success, rows, columns, rowCount, executionLog, error, pgError, dbError, false);
    }
  }

  /**
   * JDBC 저장소(Tibero) 오류 — db-executor 가 문장 실행 중 난 {@code SQLException} 의 {@code getErrorCode()} 와
   * 메시지를 그대로 싣는다(WD-77, 스펙 §5.3). 코드는 Tibero 자체 음수 코드(예: -10007 고유 위반)이며, 사용자 문구 분류는 {@code
   * PipelineSqlErrorMessages#describeTiberoError} 가 {@code TiberoDialect} 코드 묶음으로 한다.
   *
   * @param code 저장소 오류 코드(부호 포함)
   * @param message 저장소 오류 메시지(예: {@code JDBC-10007:UNIQUE constraint violation ...})
   */
  public record DbError(int code, String message) {}

  /**
   * PostgreSQL 서버 구조화 오류 중 사용자 문구에 쓰는 필드만. firehub-executor SQL 실행 응답의 {@code pg_error}(필드명
   * snake_case) — 실행기와의 통신 계약이라 응답 레코드 옆에 둔다. 로컬 경로(예외 원인 사슬)도 {@code
   * PipelineSqlErrorMessages#pgErrorOf} 로 같은 모양을 만든다.
   *
   * @param sqlState SQLState 5자리
   * @param message 주 메시지(ERROR 줄)
   * @param detail DETAIL(없으면 null)
   * @param hint HINT(없으면 null)
   * @param column 오류가 난 컬럼명(데이터 오류·NOT NULL 위반 등에서만, 없으면 null)
   */
  public record PgError(
      @JsonProperty("sql_state") String sqlState,
      String message,
      String detail,
      String hint,
      String column) {}

  public record ApiCallExecuteResult(
      boolean success,
      @JsonProperty("rows_loaded") int rowsLoaded,
      @JsonProperty("total_pages") int totalPages,
      @JsonProperty("execution_log") String executionLog,
      String error,
      @JsonProperty("execution_time_ms") long executionTimeMs) {}
}
