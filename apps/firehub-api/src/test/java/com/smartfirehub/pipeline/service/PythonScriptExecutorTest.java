package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;

class PythonScriptExecutorTest extends IntegrationTestBase {

  @Autowired private PythonScriptExecutor pythonScriptExecutor;

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

    // pipeline_executor 자격증명만 포함되어야 함
    assertThat(result).contains("DB_URL");
    assertThat(result).contains("DB_USER");
    assertThat(result).contains("pipeline_executor");
    assertThat(result).contains("DB_SCHEMA");

    // 앱 자격증명/비밀이 없어야 함
    assertThat(result).doesNotContain("JWT_SECRET");
    assertThat(result).doesNotContain("ENCRYPTION_MASTER_KEY");
    assertThat(result).doesNotContain("AGENT_INTERNAL_TOKEN");
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void execute_envVars_dbUserIsPipelineExecutor() {
    String script = "import os; print(os.environ.get('DB_USER', 'NOT_SET'))";
    String result = pythonScriptExecutor.execute(script);
    assertThat(result.trim()).isEqualTo("pipeline_executor");
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void execute_scriptError_throwsException() {
    assertThatThrownBy(() -> pythonScriptExecutor.execute("raise ValueError('test error')"))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("exit code");
  }
}
