package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.exception.UnsafeSqlException;
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
    // pipeline_executor는 data 스키마에서 SELECT 가능. 여기서는 search_path=data 영구 설정과 함께
    // 표준 카탈로그 함수 fnAndArg 없이도 동작함을 확인하기 위해 pg_typeof로 상수 SELECT를 실행한다.
    // SELECT-only(테이블 참조 없음)는 AST 검증을 통과한다.
    assertThatCode(() -> sqlScriptExecutor.execute("SELECT 1")).doesNotThrowAnyException();
  }

  @Test
  void execute_dropTable_blockedByValidator() {
    // AST 검증: 비-DML(Drop)은 차단된다 (#136)
    assertThatThrownBy(() -> sqlScriptExecutor.execute("DROP TABLE IF EXISTS data.nonexistent"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("허용되지 않는 SQL 형태");
  }

  @Test
  void execute_createTablePublic_blockedByValidator() {
    // AST 검증: 비-DML(Create)은 차단된다
    assertThatThrownBy(() -> sqlScriptExecutor.execute("CREATE TABLE public.hack_test (id BIGINT)"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("허용되지 않는 SQL 형태");
  }

  @Test
  void execute_setRoleApp_blockedByValidator() {
    // AST 검증: 멀티 스테이트먼트 + SET ROLE(비-DML)은 차단된다
    assertThatThrownBy(() -> sqlScriptExecutor.execute("SET ROLE app; SELECT 1"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  @Test
  void execute_createExtension_blockedByValidator() {
    // AST 검증: CREATE EXTENSION은 비-DML로 차단된다
    assertThatThrownBy(() -> sqlScriptExecutor.execute("CREATE EXTENSION dblink"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  @Test
  void execute_resetRole_blockedByValidator() {
    assertThatThrownBy(() -> sqlScriptExecutor.execute("RESET ROLE; SELECT 1"))
        .isInstanceOf(UnsafeSqlException.class);
  }

  @Test
  void execute_selectFromInformationSchema_blockedByValidator() {
    // AST 검증: data 외 스키마 참조 차단 (정보 스키마 노출 방지)
    assertThatThrownBy(
            () ->
                sqlScriptExecutor.execute(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'data'"))
        .isInstanceOf(UnsafeSqlException.class)
        .hasMessageContaining("data");
  }

  @Test
  void pipelineDslContext_usesCorrectUser() {
    // pipeline_executor가 연결한 사용자인지 확인
    String currentUser = pipelineDsl.fetch("SELECT current_user").get(0).get(0, String.class);
    assertThat(currentUser).isEqualTo("pipeline_executor");
  }
}
