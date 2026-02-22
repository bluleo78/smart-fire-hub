package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SqlScriptExecutor {

  private static final Logger log = LoggerFactory.getLogger(SqlScriptExecutor.class);
  private final DSLContext dsl;

  public SqlScriptExecutor(DSLContext dsl) {
    this.dsl = dsl;
  }

  public String execute(String scriptContent) {
    try {
      log.info("Executing SQL script");
      dsl.execute(scriptContent);
      return "SQL executed successfully";
    } catch (Exception e) {
      log.error("SQL execution failed", e);
      throw new ScriptExecutionException("SQL execution failed: " + e.getMessage(), e);
    }
  }
}
