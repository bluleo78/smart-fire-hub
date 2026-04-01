package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * @deprecated Use {@link com.smartfirehub.pipeline.service.executor.ExecutorClient#executeSql}
 *     instead.
 */
@Deprecated
@Slf4j
@Service
public class SqlScriptExecutor {

  private final DSLContext pipelineDsl;
  private final SqlValidator sqlValidator;

  public SqlScriptExecutor(
      @Qualifier("pipelineDslContext") DSLContext pipelineDsl, SqlValidator sqlValidator) {
    this.pipelineDsl = pipelineDsl;
    this.sqlValidator = sqlValidator;
  }

  public String execute(String scriptContent) {
    // 심층 방어: 실행 전 검증
    sqlValidator.validate(scriptContent);

    try {
      log.info("Executing SQL script via pipeline sandbox");
      pipelineDsl.execute(scriptContent);
      return "SQL executed successfully";
    } catch (Exception e) {
      log.error("SQL execution failed", e);
      throw new ScriptExecutionException("SQL execution failed: " + e.getMessage(), e);
    }
    // search_path 리셋 불필요 — pipeline_executor 역할에 search_path=data가 영구 설정됨
  }
}
