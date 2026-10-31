package com.smartfirehub.securitylevel.sql;

import com.smartfirehub.global.util.AdhocSqlStatements;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.pipeline.service.PipelineSqlErrorMessages;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import com.smartfirehub.pipeline.service.executor.DbExecutorClient.QueryResult;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.DbError;
import com.smartfirehub.pipeline.service.executor.ExecutorResponseTooLargeException;
import com.smartfirehub.pipeline.service.validator.SqlValidator.StatementKind;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Tibero 테넌트의 분석 SQL 실행 창구(WD-76). 검증(조회 전용·스키마 한정·위치 파라미터 거부)을 거친 뒤 db-executor 의 읽기 전용 조회로 실행하고,
 * 오류를 한국어 문구로 바꾼다.
 *
 * <p>왜 한 서비스인가: 분석 실행·데이터셋 조회·관문이 모두 같은 검증과 같은 오류 문구를 써야 한다. 호출자는 {@link SqlGateArchitectureTest} 가
 * 세 곳으로 고정한다. PG 폴백은 없다 — 실패하면 실패로 돌려준다.
 */
@Service
public class TiberoQueryService {

  /** 조회 외 문장 거부 문구. */
  static final String QUERY_ONLY_MESSAGE = "Tibero 저장소의 분석 SQL 은 조회(SELECT·WITH)만 실행합니다.";

  /** db-executor 가 받아들이는 행 상한의 최댓값. */
  static final int MAX_ROWS_LIMIT = 10_000;

  private final PipelineDataSchema pipelineDataSchema;
  private final DbExecutorClient dbExecutorClient;

  public TiberoQueryService(
      PipelineDataSchema pipelineDataSchema, DbExecutorClient dbExecutorClient) {
    this.pipelineDataSchema = pipelineDataSchema;
    this.dbExecutorClient = dbExecutorClient;
  }

  /**
   * 실행 결과. {@code error != null} 이면 실패이며 나머지 값은 비어 있다.
   *
   * @param truncated 결과가 {@code maxRows} 행을 넘어 잘렸는지(실행기가 상한+1 행으로 판정, WD-454)
   * @param executionTimeMs 실행기 호출에 걸린 시간(검증 실패면 0)
   */
  public record Outcome(
      List<String> columns,
      List<Map<String, Object>> rows,
      boolean truncated,
      long executionTimeMs,
      String error) {

    /** 폴러가 쓰는 결과 모양({@code QueryExecuteResult})으로 바꾼다 — 성공 여부는 {@code error == null}, 변경 행 수는 0. */
    public ExecutorClient.QueryExecuteResult toQueryExecuteResult() {
      return new ExecutorClient.QueryExecuteResult(
          error == null,
          "SELECT",
          columns,
          rows,
          rows.size(),
          0,
          executionTimeMs,
          truncated,
          error);
    }
  }

  /** 현재 테넌트의 데이터 스키마 이름(Tibero 는 대문자 {@code GENIA_T{id}}). 스키마 정보 응답의 {@code schema} 를 만들 때 쓴다. */
  public String schemaName() {
    return pipelineDataSchema.current();
  }

  /** 현재 테넌트의 저장소가 Tibero 인가. */
  public boolean isTibero() {
    return pipelineDataSchema.isTibero();
  }

  /**
   * 분석 SQL 한 문장을 검증하고 실행한다. 검증 순서: 검증기(스키마·가드) → 조회 여부 → 위치 파라미터. 앞 단계에서 걸리면 실행기를 부르지 않는다.
   *
   * @param maxRows 가져올 행 상한
   */
  public Outcome run(String sql, int maxRows) {
    StatementKind kind;
    try {
      kind = pipelineDataSchema.validator().validate(sql);
    } catch (UnsafeSqlException e) {
      return failure(e.getMessage(), 0);
    }
    if (kind != StatementKind.QUERY) {
      return failure(QUERY_ONLY_MESSAGE, 0);
    }
    String paramMessage = AdhocSqlStatements.positionalParameterMessage(sql);
    if (paramMessage != null) {
      return failure(paramMessage, 0);
    }

    // 호출자(저장 쿼리 실행 등)가 검증 없이 0·50000 을 넘겨도 PG 경로는 돌아가므로, db-executor 가 요청 형식 오류로
    // 거부하지 않게 실행기가 받는 범위(1..10000)로 보정한다.
    int clampedMaxRows = Math.max(1, Math.min(MAX_ROWS_LIMIT, maxRows));

    long start = System.nanoTime();
    QueryResult result;
    try {
      result = dbExecutorClient.executeQuery(sql, clampedMaxRows);
    } catch (ScriptExecutionException | ExecutorResponseTooLargeException e) {
      // 연결 실패 문구·응답 크기 초과 안내는 이미 사용자용 문구다.
      return failure(e.getMessage(), elapsedMs(start));
    }
    long elapsed = elapsedMs(start);
    if (result.success()) {
      return new Outcome(result.columns(), result.rows(), result.truncated(), elapsed, null);
    }
    return failure(errorMessage(result), elapsed);
  }

  /** 실패 응답을 사용자 문구로 바꾼다. dbError 가 없으면 실행기가 보낸 문구를 그대로 쓴다. */
  private static String errorMessage(QueryResult result) {
    DbError dbError = result.dbError();
    if (dbError == null) {
      // 사유 없는 실패 응답이 성공(error == null)으로 읽히지 않게 기본 문구를 둔다.
      return result.error() == null ? "Tibero 조회에 실패했습니다." : result.error();
    }
    return PipelineSqlErrorMessages.describeTiberoQueryError(dbError.code(), dbError.message());
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  private static Outcome failure(String message, long elapsedMs) {
    return new Outcome(List.of(), List.of(), false, elapsedMs, message);
  }
}
