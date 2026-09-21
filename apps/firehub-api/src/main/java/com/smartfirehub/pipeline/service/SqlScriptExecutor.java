package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.config.TenantPipelineDataSourceRegistry;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * @deprecated Use {@link com.smartfirehub.pipeline.service.executor.ExecutorClient#executeSql}
 *     instead.
 */
@Deprecated
@Slf4j
@Service
public class SqlScriptExecutor {

  /**
   * 선행 문장의 테이블명 부분이 만족해야 하는 형태 — {@code DataTableService.validateName} 과 같은
   * 규칙([a-z0-9_]+, 소문자·숫자·밑줄)이다. 스키마 부분은 접두어 자체에 {@link DataSchema#current()}
   * 를 실어 비교하므로 별도 정규식이 필요 없다(현재 테넌트 스키마 하나만 허용).
   */
  private static final Pattern PRE_STATEMENT_TABLE_PATTERN = Pattern.compile("^[a-z0-9_]+\"$");

  /**
   * 테넌트별 파이프라인 실행 커넥션 풀의 레지스트리.
   *
   * <p><b>왜 단일 {@code pipelineDslContext} 빈을 주입받지 않는가(P3-b1 R2).</b> 그 빈은 공용
   * {@code pipeline_executor} 자격증명 하나로 만든 풀이라, 어느 테넌트의 SQL 스텝을 실행하든 같은 DB
   * 롤로 접속한다 — 즉 스키마·grant 계층에서 테넌트를 구분할 수단이 없다. 여기서 테넌트별 롤
   * ({@code pipeline_executor_t{tenantId}}) 로 접속하면 격리의 근거가 애플리케이션 코드가 아니라
   * <b>DB 권한</b>이 된다.
   *
   * <p>그 공용 빈({@code pipelineDslContext})은 이제 존재하지 않는다 — 삭제 경위는 {@link
   * SqlColumnProbe} 의 Javadoc 에 있다.
   */
  private final TenantPipelineDataSourceRegistry tenantPipelineDataSources;

  private final SqlValidator sqlValidator;

  public SqlScriptExecutor(
      TenantPipelineDataSourceRegistry tenantPipelineDataSources, SqlValidator sqlValidator) {
    this.tenantPipelineDataSources = tenantPipelineDataSources;
    this.sqlValidator = sqlValidator;
  }

  /** 선행 문장 없는 실행. {@link #execute(List, String)} 에 빈 목록으로 위임한다. */
  public String execute(String scriptContent) {
    return execute(List.of(), scriptContent);
  }

  /**
   * 선행 문장(preStatements)을 본 스크립트와 <b>같은 트랜잭션</b>으로 실행한다.
   *
   * <p><b>왜 필요한가(Task 4).</b> REPLACE 전략의 SQL 스텝은 "출력 비우기(DELETE) + INSERT"가 원자적으로
   * 커밋·롤백돼야 한다 — 따로 실행하면(비우기 커밋 → INSERT 별도 트랜잭션) INSERT 실패 시 출력 테이블이
   * 빈 채로 남는다. {@link OutputClearStatement#deleteAll} 이 만든 DELETE 문을 여기서 본 스크립트보다
   * 먼저, 같은 {@code dsl.transaction} 안에서 실행한다.
   *
   * <p>각 선행 문장은 {@link OutputClearStatement#deleteAll} 이 만드는 형태
   * ({@code DELETE FROM "<현재 테넌트 스키마>"."<테이블명>"}) 와 <b>완전히</b> 일치해야 한다 —
   * firehub-executor(Python) 의 선행 문장 화이트리스트 정규식과 같은 엄격도를 이쪽에도 둔다
   * (Fix round 1, 리뷰 지적 2). 접두어(`DELETE FROM "<스키마>".`)는 {@link DataSchema#current()} 로
   * 직접 조립해 비교한다 — executor 의 파이썬 정규식 리터럴을 그대로 옮기면
   * {@code DataSchemaResolutionTest.noProductionSourceOutsideDataSchemaHoldsTheLiteral} 가드(물리
   * 스키마 리터럴 금지)에 걸리므로, 이 계약을 별도 정규식 리터럴로 하드코딩하지 않는다. 테이블명
   * 부분은 {@link #PRE_STATEMENT_TABLE_PATTERN} 으로 확인한다 — {@code DataTableService.validateName}
   * 이 기존 {@code truncateTable} 경로에서 하던 것과 같은 방어를 이 경로에도 되살린다. 이 검증
   * 하나로 "시작 문자열 + 세미콜론 없음"만 보던 이전 형태보다, API 서버가 만들지 않은 임의의
   * DELETE(WHERE 절 포함·다른 스키마 대상 등)를 테넌트 파이프라인 롤 권한으로 실행할 길을 막는다.
   *
   * @param preStatements 본 스크립트보다 먼저 실행할 문장 목록(순서 보존). 없으면 빈 목록.
   * @param scriptContent 본 SQL 스크립트
   */
  public String execute(List<String> preStatements, String scriptContent) {
    // 심층 방어: 실행 전 검증
    sqlValidator.validate(scriptContent);
    // 선행 문장은 DataSchema.current() 로 얻은 "현재 테넌트 스키마" 를 대상으로 하는 DELETE 전체
    // 삭제문 하나만 허용한다 — WHERE 절도, 다른 스키마도, 세미콜론으로 이어붙인 두 번째 문장도
    // 이 접두어·패턴 조합을 통과할 수 없다.
    String expectedPrefix = "DELETE FROM \"" + DataSchema.current() + "\".\"";
    for (String preStatement : preStatements) {
      if (preStatement == null
          || !preStatement.startsWith(expectedPrefix)
          || !PRE_STATEMENT_TABLE_PATTERN
              .matcher(preStatement.substring(expectedPrefix.length()))
              .matches()) {
        throw new ScriptExecutionException("허용되지 않은 선행 문장입니다: " + preStatement);
      }
    }

    long tenantId = TenantContext.require("파이프라인 SQL 실행");

    try {
      log.info("Executing SQL script via tenant pipeline sandbox (tenant={})", tenantId);
      // search_path 를 명시적으로 세운다 — 롤 레벨 설정에 의존하지 않는다.
      //
      // (정정) 이전 주석은 "pipeline_executor 역할에 search_path=data 가 영구 설정돼 있으므로 리셋이
      // 불필요하다"고 적혀 있었다. 그 처방은 더 이상 유효하지 않다: (1) 롤 레벨 설정은 롤을 만든
      // 마이그레이션에만 기록돼 있어 코드에서는 보이지 않고, 롤이 늘어날 때(테넌트별 롤) 한 곳만
      // 누락되면 조용히 남의(혹은 없는) 스키마를 보게 된다. (2) P3-b2 가 스키마를 테넌트별로 개명하면
      // 정본은 DataSchema.current() 이고 롤 설정은 그것을 따라가는 사본일 뿐이다. 그래서 실행 직전에
      // 애플리케이션이 정본을 직접 세운다.
      //
      // SET LOCAL 은 트랜잭션 안에서만 유효하다 — autocommit 으로 실행하면 PostgreSQL 이 경고만 내고
      // 아무 효과가 없다. 그래서 SET 과 스크립트를 같은 트랜잭션으로 묶는다(트랜잭션 종료 시 자동
      // 복원되므로, 풀로 반납되는 커넥션에 search_path 가 남지 않는다).
      // withTenantDsl 로 **대여**한다 — dslForWithoutLease 를 직접 쓰면 이 트랜잭션이 도는 동안 다른 테넌트
      // 요청이 상한을 넘겼을 때 이 풀이 축출·close() 되어 진행 중인 문장이 죽는다(코드리뷰 지적 3).
      tenantPipelineDataSources.withTenantDsl(
          tenantId,
          tenantDsl -> {
            tenantDsl.transaction(
                cfg -> {
                  // search_path 는 한정 이름이 아니라 스키마 식별자 목록이므로 qualify() 가 아니라 current().
                  //
                  // P3-b2 T4 — 무변경 판정(실측 근거). 인용된 단일 스키마 조립은 숫자 접미사가
                  // 붙어도(data_t{id}) 그대로 해석된다 — data_t900000123 라는 한 이름으로 PostgreSQL
                  // 로 직접 확인했다(psql SHOW search_path 프로브, 자릿수 자체는 해석에 영향 없음).
                  // SqlScriptExecutorSandboxTest 의
                  // execute_runsAsTenantPipelineRole_andSetsDataSearchPath_forSuffixedTenant 가
                  // 실제 테넌트 파이프라인 롤·커넥션 풀로 재확인한다. 고치지 않는다.
                  cfg.dsl().execute("SET LOCAL search_path = '" + DataSchema.current() + "'");
                  // 선행 문장(비우기 등)을 본 스크립트보다 먼저, 같은 트랜잭션에서 실행한다 — 이 트랜잭션이
                  // 커밋되기 전까지는 DELETE 도 INSERT 도 밖에서 보이지 않고(MVCC), 실패하면 둘 다
                  // 롤백된다.
                  for (String preStatement : preStatements) {
                    cfg.dsl().execute(preStatement);
                  }
                  cfg.dsl().execute(scriptContent);
                });
            return null;
          });
      return "SQL executed successfully";
    } catch (Exception e) {
      log.error("SQL execution failed", e);
      throw new ScriptExecutionException("SQL execution failed: " + e.getMessage(), e);
    }
  }
}
