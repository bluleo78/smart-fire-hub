package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

class SqlScriptExecutorSandboxTest extends IntegrationTestBase {

  @Autowired private SqlScriptExecutor sqlScriptExecutor;

  @Autowired
  @Qualifier("pipelineDslContext")
  private DSLContext pipelineDsl;

  @Test
  void execute_selectFromDataSchema_succeeds() {
    // pipeline_executor는 data 스키마에서 SELECT 가능
    // information_schema 조회로 확인
    assertThatCode(
            () ->
                sqlScriptExecutor.execute(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'data' LIMIT 1"))
        .doesNotThrowAnyException();
  }

  @Test
  void execute_dropTable_failsWithPermissionDenied() {
    // SqlValidator가 먼저 차단
    assertThatThrownBy(() -> sqlScriptExecutor.execute("DROP TABLE IF EXISTS data.nonexistent"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("DROP");
  }

  @Test
  void execute_accessPublicSchema_failsWithPermissionDenied() {
    // SqlValidator가 CREATE를 차단 (public 스키마 접근 시도)
    assertThatThrownBy(() -> sqlScriptExecutor.execute("CREATE TABLE public.hack_test (id BIGINT)"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("CREATE");
  }

  @Test
  void execute_setRoleApp_failsWithPermissionDenied() {
    // SqlValidator가 SET ROLE을 차단
    assertThatThrownBy(() -> sqlScriptExecutor.execute("SET ROLE app; SELECT 1"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("SET ROLE");
  }

  @Test
  void execute_createExtension_failsWithPermissionDenied() {
    // SqlValidator가 차단 (CREATE 또는 DBLINK 키워드)
    assertThatThrownBy(() -> sqlScriptExecutor.execute("CREATE EXTENSION dblink"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("차단된 키워드");
  }

  @Test
  void execute_resetRole_blocked() {
    assertThatThrownBy(() -> sqlScriptExecutor.execute("RESET ROLE; SELECT 1"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("차단된 키워드");
  }

  @Test
  void pipelineDslContext_usesCorrectUser() {
    // pipeline_executor가 연결한 사용자인지 확인
    String currentUser = pipelineDsl.fetch("SELECT current_user").get(0).get(0, String.class);
    assertThat(currentUser).isEqualTo("pipeline_executor");
  }
}
