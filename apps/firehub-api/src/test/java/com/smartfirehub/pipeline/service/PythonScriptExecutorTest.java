package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

class PythonScriptExecutorTest extends IntegrationTestBase {

  @Autowired private PythonScriptExecutor pythonScriptExecutor;

  /** 롤 비밀번호 파생 HMAC 키 — 하드코딩하면 실행기가 계산하는 값과 어긋난다. */
  @Value("${app.pipeline.role-password-secret}")
  private String rolePasswordSecret;

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void execute_simpleScript_succeeds() {
    String result = pythonScriptExecutor.execute("print('hello from sandbox')");
    assertThat(result).contains("hello from sandbox");
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void execute_envVars_onlyContainPipelineCredentials() {
    // 스크립트가 모든 환경변수를 출력하여 앱 자격증명이 없는지 확인
    String script =
        """
        import os
        import json
        env_vars = dict(os.environ)
        print(json.dumps(env_vars))
        """;

    String result = pythonScriptExecutor.execute(script);

    // 파이프라인 실행 롤 자격증명만 포함되어야 함
    assertThat(result).contains("DB_URL");
    assertThat(result).contains("DB_USER");
    assertThat(result).contains(TenantPipelineRole.roleName(DEFAULT_TEST_TENANT_ID));
    assertThat(result).contains("DB_SCHEMA");

    // 앱 자격증명/비밀이 없어야 함
    assertThat(result).doesNotContain("JWT_SECRET");
    assertThat(result).doesNotContain("ENCRYPTION_MASTER_KEY");
    assertThat(result).doesNotContain("AGENT_INTERNAL_TOKEN");
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void execute_envVars_dbUserIsTenantPipelineRole() {
    String script = "import os; print(os.environ.get('DB_USER', 'NOT_SET'))";
    String result = pythonScriptExecutor.execute(script);
    assertThat(result.trim()).isEqualTo(TenantPipelineRole.roleName(DEFAULT_TEST_TENANT_ID));
  }

  /**
   * #681 회귀 가드 — <b>자격증명과 스키마가 같은 테넌트를 가리켜야 한다.</b>
   *
   * <p>예전에는 {@code DB_USER/DB_PASSWORD} 가 공용 {@code pipeline_executor} 이고
   * {@code DB_SCHEMA} 만 테넌트 파생이었다. 테넌트 1 에서는 그 롤이 {@code data} 에 USAGE 를
   * 가져 증상이 없고, 접미사 테넌트에서만 {@code permission denied for schema data_t{id}} 로
   * 터진다 — 그래서 이 가드는 <b>반드시 1 이 아닌 테넌트</b>로 확인한다(SqlColumnProbeSandboxTest
   * 가 접미사 테넌트를 쓰는 것과 같은 근거).
   *
   * <p>프로세스를 띄우지 않고 환경 조립만 본다 — 실제 접속·권한은 SQL 경로의 샌드박스 테스트가
   * 이미 보고 있고, 여기서 확인해야 하는 것은 "세 값이 같은 테넌트를 가리키는가" 하나다.
   */
  @Test
  void buildEnvironment_usesTenantRoleAndMatchingSchema_forSuffixedTenant() {
    long tenantId = 2L;

    Map<String, String> env = pythonScriptExecutor.buildEnvironment(tenantId);

    assertThat(env)
        .containsEntry("DB_USER", TenantPipelineRole.roleName(tenantId))
        .containsEntry("DB_PASSWORD", TenantPipelineRole.password(tenantId, rolePasswordSecret))
        .containsEntry("DB_SCHEMA", "data_t" + tenantId);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void execute_scriptError_throwsException() {
    assertThatThrownBy(() -> pythonScriptExecutor.execute("raise ValueError('test error')"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("exit code");
  }
}
