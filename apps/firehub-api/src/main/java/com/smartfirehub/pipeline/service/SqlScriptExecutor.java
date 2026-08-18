package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.config.TenantPipelineDataSourceRegistry;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
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
   * 테넌트별 파이프라인 실행 커넥션 풀의 레지스트리.
   *
   * <p><b>왜 단일 {@code pipelineDslContext} 빈을 주입받지 않는가(P3-b1 R2).</b> 그 빈은 공용
   * {@code pipeline_executor} 자격증명 하나로 만든 풀이라, 어느 테넌트의 SQL 스텝을 실행하든 같은 DB
   * 롤로 접속한다 — 즉 스키마·grant 계층에서 테넌트를 구분할 수단이 없다. 여기서 테넌트별 롤
   * ({@code pipeline_executor_t{tenantId}}) 로 접속하면 격리의 근거가 애플리케이션 코드가 아니라
   * <b>DB 권한</b>이 된다. 기존 빈은 R5 에 따라 그대로 남아 있다(dev·prod 의 현행 경로).
   */
  private final TenantPipelineDataSourceRegistry tenantPipelineDataSources;

  private final SqlValidator sqlValidator;

  public SqlScriptExecutor(
      TenantPipelineDataSourceRegistry tenantPipelineDataSources, SqlValidator sqlValidator) {
    this.tenantPipelineDataSources = tenantPipelineDataSources;
    this.sqlValidator = sqlValidator;
  }

  public String execute(String scriptContent) {
    // 심층 방어: 실행 전 검증
    sqlValidator.validate(scriptContent);

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
                  // 붙어도(data_t{id}) 그대로 해석된다 — PostgreSQL 로 직접 확인했고(psql SHOW
                  // search_path 프로브), SqlScriptExecutorSandboxTest 의
                  // execute_runsAsTenantPipelineRole_andSetsDataSearchPath_forSuffixedTenant 가
                  // 실제 테넌트 파이프라인 롤·커넥션 풀로 재확인한다. 고치지 않는다.
                  cfg.dsl().execute("SET LOCAL search_path = '" + DataSchema.current() + "'");
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
