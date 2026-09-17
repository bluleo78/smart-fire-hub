package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.config.TenantPipelineDataSourceRegistry;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import java.util.Arrays;
import java.util.List;
import org.jooq.DataType;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.stereotype.Service;

/**
 * SQL 스텝의 결과 컬럼(이름·타입)을 실제 DB probe 쿼리로 알아낸다 — 임시 데이터셋 스키마 생성에 쓴다.
 *
 * <p><b>왜 별도 클래스인가.</b> 원래 이 두 메서드는 {@code PipelineAsyncRunner} 의 private 메서드였다.
 * private 이라 테넌트별 접속 경로를 검증하는 통합 테스트를 붙일 수 없었고, 그래서 P3-b2(테넌트별 스키마
 * 분리)가 실행 경로({@code SqlScriptExecutor})만 테넌트 롤로 옮기고 <b>이 probe 경로를 공용
 * {@code pipeline_executor} 에 남겨 둔 것</b>이 아무 테스트에도 걸리지 않았다. 그 결과 테넌트 2의
 * 파이프라인이 운영에서 {@code SQL 컬럼 타입 분석 실패: ERROR: permission denied for schema data_t2} 로
 * 터졌다 — 공용 롤은 {@code data} 에만 USAGE 가 있기 때문이다. 회귀 가드는
 * {@code SqlColumnProbeSandboxTest}.
 *
 * <p><b>접속은 반드시 테넌트 롤로 한다.</b> probe 는 사용자가 작성한 SQL 을 그대로 감싸 실행하므로,
 * 공용 롤로 던지면 스키마·grant 계층에서 테넌트를 구분할 수단이 사라진다 — 실행 경로만 격리하고 probe 를
 * 공용 롤에 두면 격리의 근거가 DB 권한이 아니라 "probe 는 LIMIT 0 이니 괜찮다"는 애플리케이션 가정이 된다.
 */
@Service
public class SqlColumnProbe {

  private final TenantPipelineDataSourceRegistry tenantPipelineDataSources;

  private final SqlValidator sqlValidator;

  public SqlColumnProbe(
      TenantPipelineDataSourceRegistry tenantPipelineDataSources, SqlValidator sqlValidator) {
    this.tenantPipelineDataSources = tenantPipelineDataSources;
    this.sqlValidator = sqlValidator;
  }

  /**
   * SQL SELECT 문의 결과 컬럼(이름·타입)을 추출한다. 실제 DB 에 probe 쿼리를 실행해 정확한 값을 얻는다.
   *
   * <p>이름만 필요한 호출부도 이 메서드를 쓰고 {@code ColumnInfo::name} 으로 뽑는다 — 이름 전용 메서드를
   * 따로 두면 같은 SQL 에 대해 probe 가 스텝당 두 번 나가고(풀 대여·트랜잭션·왕복이 각각 두 벌),
   * 그 사이 풀이 축출될 틈도 생긴다.
   *
   * @param sql SELECT SQL 문
   * @return 컬럼 정보(이름, 타입) 목록 — SELECT 절 순서를 보존한다
   */
  public List<ColumnInfo> columnsWithTypes(String sql) {
    // 심층 방어 — SqlScriptExecutor.execute 와 같은 이유로 여기서도 검증한다. 현재 유일한 호출부
    // (PipelineAsyncRunner)가 이미 검증한 뒤 부르지만, 이 클래스는 임의 SQL 을 테넌트 샌드박스 풀에서
    // 실행하는 public API 라 "호출부가 검증했겠지" 를 유일한 방어선으로 두지 않는다.
    sqlValidator.validate(sql);
    // 테넌트 확인은 try 밖에서 한다 — MissingTenantScopeException 은 IllegalStateException 계층으로
    // 409·"배선 결함" 로그 구분이 걸린 계약이라, 여기서 ScriptExecutionException 으로 감싸면 스텝이
    // 실패한 것인지 테넌트 배선이 끊긴 것인지 구분할 수 없게 된다(SqlScriptExecutor 도 같은 이유로
    // try 밖에서 require 한다).
    long tenantId = TenantContext.require("SQL 컬럼 프로브");
    try {
      return Arrays.stream(fetchProbe(tenantId, sql).fields())
          .map(f -> new ColumnInfo(f.getName(), mapJooqTypeToAppType(f.getDataType())))
          .toList();
    } catch (Exception e) {
      throw new ScriptExecutionException("SQL 컬럼 타입 분석 실패: " + extractRootCauseMessage(e), e);
    }
  }

  /**
   * 컬럼 메타데이터만 필요하므로 {@code LIMIT 0} 으로 래핑해 행은 한 건도 읽지 않는다.
   *
   * <p>접속 경로는 {@code SqlScriptExecutor#execute} 와 같은 조리법을 따른다 — 같은 SQL 이 probe 와 실행에서
   * 서로 다른 롤·search_path 로 해석되면, probe 가 본 컬럼과 실제로 적재되는 컬럼이 달라질 수 있다.
   *
   * <ul>
   *   <li>{@code withTenantDsl} 로 <b>대여</b>한다 — {@code dslForWithoutLease} 를 직접 쓰면 이 쿼리가
   *       도는 동안 다른 테넌트 요청이 풀 상한을 넘겼을 때 이 풀이 축출·close() 되어 진행 중인 문장이 죽는다.
   *   <li>{@code SET LOCAL} 은 트랜잭션 안에서만 유효하다 — autocommit 으로 실행하면 PostgreSQL 이 경고만
   *       내고 아무 효과가 없다. 그래서 SET 과 probe 를 같은 트랜잭션으로 묶는다.
   * </ul>
   *
   * <p>{@code tenantId} 는 이 코드가 도는 {@code pipelineExecutor} 스레드가 {@code
   * TenantContextTaskDecorator} 로 승계받은 값이다.
   */
  private Result<Record> fetchProbe(long tenantId, String sql) {
    String probeSql = "SELECT * FROM (" + sql + ") AS _probe LIMIT 0";
    return tenantPipelineDataSources.withTenantDsl(
        tenantId,
        tenantDsl ->
            tenantDsl.transactionResult(
                cfg -> {
                  cfg.dsl().execute("SET LOCAL search_path = '" + DataSchema.current() + "'");
                  return cfg.dsl().fetch(probeSql);
                }));
  }

  /**
   * SQL 컬럼 스키마 추론용 probe 쿼리 실행 실패 시, 사용자에게 보여줄 근본 원인 메시지를 추출한다(#662).
   *
   * <p>jOOQ가 던지는 {@code DataAccessException.getMessage()}는 "jOOQ; bad SQL grammar [<probe SQL 원문>]"
   * 형식으로 {@code SELECT * FROM (...) AS _probe LIMIT 0}처럼 사용자가 작성하지 않은 내부 구현 세부사항(probe
   * 래핑)을 그대로 노출한다. 반면 실제 DB(PostgreSQL 등)가 반환한 구체적 원인(예: "relation ... does not
   * exist")은 {@code getCause()}에만 담겨 있어 그대로는 사용자에게 전달되지 않는다. 이 메서드는 cause 체인을
   * 우선 사용해 근본 원인만 뽑아내고, cause가 없거나 메시지가 비어 있을 때만 원래 메시지로 폴백한다.
   *
   * @param e probe 쿼리 실행 중 발생한 예외
   * @return 사용자에게 노출할 근본 원인 메시지 (probe SQL 구문 등 내부 구현 디테일 제거)
   */
  private String extractRootCauseMessage(Exception e) {
    Throwable cause = e.getCause();
    if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
      return cause.getMessage();
    }
    // cause가 없으면 원본 예외 메시지로 폴백 (probe SQL 원문이 포함될 수 있으나 최소한의 정보는 제공)
    return e.getMessage();
  }

  /** jOOQ 가 돌려준 DB 타입을 앱의 데이터셋 컬럼 타입 어휘로 옮긴다. */
  private String mapJooqTypeToAppType(DataType<?> dataType) {
    String sqlType = dataType.getTypeName().toUpperCase();
    if (sqlType.contains("VARCHAR") || sqlType.contains("TEXT") || sqlType.contains("CHAR"))
      return "TEXT";
    if (sqlType.contains("INT") || sqlType.contains("SERIAL")) return "INTEGER";
    if (sqlType.contains("NUMERIC")
        || sqlType.contains("DECIMAL")
        || sqlType.contains("FLOAT")
        || sqlType.contains("DOUBLE")) return "DECIMAL";
    if (sqlType.contains("BOOL")) return "BOOLEAN";
    if (sqlType.equals("DATE")) return "DATE";
    if (sqlType.contains("TIMESTAMP")) return "TIMESTAMP";
    return "TEXT";
  }
}
