package com.smartfirehub.pipeline.service.executor;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.DbError;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.SqlExecuteResult;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Tibero 저장소 테넌트의 파이프라인 SQL 을 실행하는 firehub-db-executor 호출 클라이언트(WD-77, 스펙 §3·§5.1).
 *
 * <p><b>왜 따로 두나.</b> PG 테넌트의 SQL 은 {@link ExecutorClient}(Python firehub-executor)나 로컬 {@code
 * SqlScriptExecutor} 가 PG 에서 실행한다. Tibero 테넌트의 SQL 이 그 경로로 가면 PG 에서 실행되는데, 이는 스펙의 실패 조건이다. 그래서
 * Tibero 테넌트는 이 클라이언트 하나로만 실행하고, 주소가 없거나 닿지 않으면 PG 로 돌아가지 않고 실패한다(fail-closed). 이 클라이언트는 {@code
 * app.executor.enabled} 값과 무관하다.
 *
 * <p><b>계약.</b> 요청 본문은 camelCase({@code tenantId}·{@code query}·{@code preStatements}), 응답은
 * snake_case({@code row_count}·{@code execution_log}·{@code db_error}·{@code jdbc_type}·{@code
 * type_name})다. 인증은 db-executor 전용 내부 토큰({@code Authorization: Internal <token>}, Python 실행기 토큰과
 * 별개)이다. 실행 오류(검증 거부·DB 오류·시간 초과)는 HTTP 200 + {@code success=false} 로 오고, 그건 정상 스텝 실패로 호출자가 번역한다. 그
 * 밖의 전송 실패(주소 미설정·연결 실패·401·5xx·응답 시간 초과)는 모두 {@link #CONNECT_FAILURE_MESSAGE} 로 바꿔 던진다 — 내부 주소·토큰
 * 오류 같은 운영 정보가 실행 기록에 남지 않게 문구는 하나로 두고, 원인은 로그에만 남긴다. 응답 본문이 버퍼 한도를 넘은 경우만은 PG 실행기와 같이 {@link
 * ExecutorResponseTooLargeException}("결과가 너무 큽니다") 로 둔다.
 */
@Service
@Slf4j
public class DbExecutorClient {

  /** 전송 실패(주소 미설정·연결 실패·인증 실패·시간 초과) 때 스텝 오류로 저장하는 문구. */
  public static final String CONNECT_FAILURE_MESSAGE = "Tibero 실행기에 연결하지 못했습니다.";

  /** {@code /execute/sql} 응답 대기 한도 — 실행기의 전체 실행 한도(55초)에 여유 5초(스펙 §11). */
  static final Duration SQL_TIMEOUT = Duration.ofSeconds(60);

  /** {@code /execute/probe} 응답 대기 한도 — 실행기의 probe 한도(30초)에 여유 5초. */
  static final Duration PROBE_TIMEOUT = Duration.ofSeconds(35);

  /** {@code /execute/query} 응답 대기 한도 — 실행기의 조회 한도(30초)에 여유 5초. */
  static final Duration QUERY_TIMEOUT = Duration.ofSeconds(35);

  /** 요청별 시간 제한({@code timeout_seconds})을 쓰는 호출이 실행기 응답을 더 기다려 주는 여유(초). */
  static final int RESPONSE_GRACE_SECONDS = 5;

  /** 응답 본문 버퍼 한도 — PG 실행기 기본값과 같다({@link ExecutorClient#DEFAULT_MAX_RESPONSE_BYTES}). */
  private static final int MAX_RESPONSE_BYTES = ExecutorClient.DEFAULT_MAX_RESPONSE_BYTES;

  /** 주소가 설정되지 않았으면 null — 호출 시점에 실패시킨다(PG 만 쓰는 배포가 기동에 실패하지 않게). */
  private final WebClient webClient;

  private final Duration sqlTimeout;
  private final Duration probeTimeout;
  private final Duration queryTimeout;

  /**
   * 스프링 빈 생성자.
   *
   * @param baseUrl db-executor 주소({@code app.db-executor.base-url}). 비어 있으면 호출마다 실패한다.
   * @param internalToken db-executor 내부 토큰({@code app.db-executor.internal-token}) — Python 실행기
   *     토큰과는 별개 값
   */
  @Autowired
  public DbExecutorClient(
      WebClient.Builder webClientBuilder,
      @Value("${app.db-executor.base-url:}") String baseUrl,
      @Value("${app.db-executor.internal-token:dev-db-executor-token}") String internalToken) {
    this(webClientBuilder, baseUrl, internalToken, SQL_TIMEOUT, PROBE_TIMEOUT, QUERY_TIMEOUT);
  }

  /** 시간 한도를 바꿔 끼우는 생성자 — 시간 초과 테스트가 60초를 기다리지 않게 한다. */
  DbExecutorClient(
      WebClient.Builder webClientBuilder,
      String baseUrl,
      String internalToken,
      Duration sqlTimeout,
      Duration probeTimeout,
      Duration queryTimeout) {
    this.sqlTimeout = sqlTimeout;
    this.probeTimeout = probeTimeout;
    this.queryTimeout = queryTimeout;
    if (baseUrl == null || baseUrl.isBlank()) {
      this.webClient = null;
      return;
    }
    // 빌더는 공유 프로토타입일 수 있어 복제해서 쓴다(다른 클라이언트의 기본 헤더·주소와 섞이지 않게).
    // INSERT·MERGE 적재는 행을 돌려받지 않지만, 사용자 DML 결과 메타·오류 문구를 받을 여유를 PG 실행기와 같은 한도로 둔다.
    this.webClient =
        ExecutorClient.internalClient(
            webClientBuilder.clone(), baseUrl, internalToken, MAX_RESPONSE_BYTES);
  }

  /**
   * 본 문장을 선행 문장과 같은 트랜잭션으로 실행한다. POST /execute/sql, 응답 대기 60초.
   *
   * <p>선행 문장은 출력 비우기({@code DELETE FROM "S"."T"}) 뿐이며 실행기가 형식을 다시 검사하고, 그 대상 테이블을 먼저 잠근다(같은 출력에
   * REPLACE 두 실행이 겹쳐 행이 중복되는 #731 방지).
   *
   * @param query 본 문장(러너가 만든 INSERT…SELECT·MERGE, 또는 사용자 DML)
   * @param preStatements 본 문장보다 먼저 같은 트랜잭션에서 실행할 문장(없으면 빈 목록)
   * @return 실행 결과 — 실패면 {@code success=false} 와 {@code error}·{@code dbError}
   * @throws ScriptExecutionException 주소 미설정·연결 실패·인증 실패·응답 시간 초과
   */
  public SqlExecuteResult executeSql(String query, List<String> preStatements) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("query", query);
    request.put("preStatements", preStatements == null ? List.of() : preStatements);
    return post("/execute/sql", withTenant(request), SqlExecuteResult.class, sqlTimeout);
  }

  /**
   * {@link #executeSql(String, List)} 에 요청별 시간 제한을 더한 판 — 스테이징에서 출력 테이블로 옮기는 대량 {@code INSERT …
   * SELECT} 용이다. 실행기는 {@code timeout_seconds} 를 문장 제한이자 전체 기한으로 쓰고(상한 1800초), 이 클라이언트는 그보다 5초 더
   * 기다린다 — 실행기가 시간 초과를 정상 실패 응답({@code success=false})으로 돌려줄 틈을 주기 위해서다.
   *
   * @param timeoutSeconds 1 이상. 0 이하면 실행기가 거부하므로 호출 전에 막는다
   */
  public SqlExecuteResult executeSql(String query, List<String> preStatements, int timeoutSeconds) {
    requirePositive(timeoutSeconds);
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("query", query);
    request.put("preStatements", preStatements == null ? List.of() : preStatements);
    request.put("timeout_seconds", timeoutSeconds);
    return post(
        "/execute/sql",
        withTenant(request),
        SqlExecuteResult.class,
        Duration.ofSeconds(timeoutSeconds + RESPONSE_GRACE_SECONDS));
  }

  /** 시간 제한은 1 이상이어야 한다(실행기가 0 이하를 거부하므로 네트워크로 보내기 전에 막는다). */
  private static void requirePositive(int timeoutSeconds) {
    if (timeoutSeconds <= 0) {
      throw new IllegalArgumentException("timeoutSeconds 는 1 이상이어야 합니다: " + timeoutSeconds);
    }
  }

  /**
   * 사용자 SELECT 의 결과 컬럼 메타를 얻는다(실행기가 {@code WHERE 1 = 0} 으로 감싸 행 없이 실행). POST /execute/probe, 응답 대기
   * 35초.
   *
   * @param query 검증·보안 게이트를 통과한 사용자 SELECT
   * @return 컬럼 메타 — 실패면 {@code success=false} 와 {@code error}·{@code dbError}
   * @throws ScriptExecutionException 주소 미설정·연결 실패·인증 실패·응답 시간 초과
   */
  public ProbeResult probe(String query) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("query", query);
    return post("/execute/probe", withTenant(request), ProbeResult.class, probeTimeout);
  }

  /**
   * 분석 SELECT 를 테넌트 읽기 전용 계정으로 실행한다. POST /execute/query, 응답 대기 35초.
   *
   * @param query 검증을 통과한 조회 SQL
   * @param maxRows 가져올 행 상한(실행기가 1..10000 으로 제한)
   * @return 조회 결과 — 실패면 {@code success=false} 와 {@code error}·{@code dbError}
   * @throws ScriptExecutionException 주소 미설정·연결 실패·인증 실패·응답 시간 초과
   */
  public QueryResult executeQuery(String query, int maxRows) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("query", query);
    request.put("max_rows", maxRows);
    // 실행기 계약(Python 실행기와 같은 모양): 이 창구는 읽기 전용 조회만 받는다 — 명시하지 않으면 db-executor 가 거부한다.
    request.put("read_only", true);
    return post("/execute/query", withTenant(request), QueryResult.class, queryTimeout);
  }

  /**
   * 요청 본문에 현재 테넌트 id 를 싣는다(PG 실행기와 같은 필드명·규칙 — {@link ExecutorClient#withTenant(Map, String)}).
   * 실행기는 이 값으로 저장소 기술자를 조회해 그 테넌트 계정으로 접속한다. 테넌트가 없으면 연결 실패로 숨기지 않고 그대로 예외를 낸다(배선 결함이 운영 문구에 묻히지
   * 않게) — 그래서 전송 try 블록 밖({@link #post} 호출 전)에서 부른다.
   */
  private static Map<String, Object> withTenant(Map<String, Object> request) {
    return ExecutorClient.withTenant(request, "db-executor 실행 요청");
  }

  /**
   * 요청을 보내고, 전송 단계 실패를 {@link #CONNECT_FAILURE_MESSAGE} 로 바꾼다(원인은 로그에만). 응답 크기 초과는 {@link
   * ExecutorResponseTooLargeException} 그대로 던진다.
   */
  private <T> T post(String uri, Map<String, Object> body, Class<T> type, Duration timeout) {
    if (webClient == null) {
      log.warn("db-executor 주소(app.db-executor.base-url)가 설정되지 않아 {} 요청을 보내지 않았습니다", uri);
      throw new ScriptExecutionException(CONNECT_FAILURE_MESSAGE);
    }
    T result;
    try {
      result =
          ExecutorClient.guardSize(
              MAX_RESPONSE_BYTES,
              () ->
                  webClient
                      .post()
                      .uri(uri)
                      .bodyValue(body)
                      .retrieve()
                      .bodyToMono(type)
                      .timeout(timeout)
                      .block());
    } catch (ExecutorResponseTooLargeException e) {
      // 응답이 버퍼 한도를 넘었다 — 연결 실패가 아니므로 PG 실행기와 같은 "결과가 너무 큽니다" 안내로 실패시킨다(#761).
      throw e;
    } catch (RuntimeException e) {
      // 401(토큰 불일치)·5xx·연결 거부·응답 시간 초과(block() 이 TimeoutException 을 감싼 것) 모두 여기로 온다.
      log.warn("db-executor {} 요청 실패: {}", uri, e.toString());
      throw new ScriptExecutionException(CONNECT_FAILURE_MESSAGE, e);
    }
    if (result == null) {
      log.warn("db-executor {} 응답 본문이 비어 있습니다", uri);
      throw new ScriptExecutionException(CONNECT_FAILURE_MESSAGE);
    }
    return result;
  }

  /**
   * probe 응답.
   *
   * @param success 실행기가 컬럼 메타를 얻었는지
   * @param columns 결과 컬럼(순서 보존, 응답에 없으면 빈 목록). 이름은 실행기가 바꾸지 않고 보낸다 — 정규화는 러너({@code
   *     PipelineAsyncRunner.normalizeTiberoResultColumn})가 한다
   * @param error 실패 문구(검증 거부·연결 실패 등)
   * @param dbError 실패가 DB 오류일 때의 코드·메시지(아니면 null)
   */
  public record ProbeResult(
      boolean success,
      List<ProbeColumn> columns,
      String error,
      @JsonProperty("db_error") DbError dbError) {

    /** 실패 응답은 {@code columns} 가 없다 — 호출자가 null 을 따로 확인하지 않게 빈 목록으로 맞춘다. */
    public ProbeResult {
      columns = columns == null ? List.of() : columns;
    }
  }

  /**
   * probe 결과 컬럼 하나.
   *
   * @param name 결과 컬럼 이름(드라이버가 준 원래 이름 — 따옴표 없는 별칭은 대문자)
   * @param jdbcType {@code java.sql.Types} 값
   * @param typeName 저장소 타입 이름(예: {@code NUMBER}, {@code VARCHAR})
   * @param precision 정밀도(없으면 null)
   * @param scale 소수 자릿수(없으면 null)
   */
  public record ProbeColumn(
      String name,
      @JsonProperty("jdbc_type") int jdbcType,
      @JsonProperty("type_name") String typeName,
      Integer precision,
      Integer scale) {}

  /**
   * 조회 응답.
   *
   * @param columns 결과 컬럼 이름(순서 보존)
   * @param rows 행(컬럼명 → 값)
   * @param rowCount 가져온 행 수
   * @param truncated 행 수가 상한에 닿았는지
   * @param error 실패 문구
   * @param dbError 실패가 DB 오류일 때의 코드·메시지(아니면 null)
   */
  public record QueryResult(
      boolean success,
      List<String> columns,
      List<Map<String, Object>> rows,
      @JsonProperty("row_count") int rowCount,
      boolean truncated,
      String error,
      @JsonProperty("db_error") DbError dbError) {

    /** 실패 응답은 columns·rows 가 없을 수 있다 — 호출자가 null 을 따로 확인하지 않게 빈 목록으로 맞춘다. */
    public QueryResult {
      columns = columns == null ? List.of() : columns;
      rows = rows == null ? List.of() : rows;
    }
  }
}
