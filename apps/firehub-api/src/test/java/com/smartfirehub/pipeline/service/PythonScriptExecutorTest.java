package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 로컬(실행기 끔) PYTHON 자식 프로세스 실행기 — 슬롯 롤 자격증명(WD-29)과 stdout/stderr 분리(R5)를 본다. 슬롯 롤의 실제 읽기 권한(42501)은
 * {@code PythonScriptExecutorSlotAccessTest} 가 같은 환경값으로 JDBC 실접속해 본다(호스트 python3 에 psycopg2 가 없다).
 */
class PythonScriptExecutorTest extends IntegrationTestBase {

  @Autowired private PythonScriptExecutor pythonScriptExecutor;

  /** 롤 비밀번호 파생 HMAC 키 — 하드코딩하면 실행기가 계산하는 값과 어긋난다. */
  @Value("${app.pipeline.role-password-secret}")
  private String rolePasswordSecret;

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void run_simpleScript_succeeds() {
    PythonScriptExecutor.RunResult result =
        pythonScriptExecutor.run("print('hello from sandbox')", 2);
    assertThat(result.succeeded()).isTrue();
    assertThat(result.stdout()).contains("hello from sandbox");
    assertThat(result.stderr()).isEmpty();
  }

  /**
   * stdout 과 stderr 는 섞이지 않는다 — 출력 테이블이 있으면 stdout 은 적재할 JSON 이라 경고 줄이 섞이면 파싱이 깨진다(executor 계약).
   */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void run_separatesStdoutAndStderr() {
    PythonScriptExecutor.RunResult result =
        pythonScriptExecutor.run(
            "import sys\nprint('[{\"a\": 1}]')\nprint('warn', file=sys.stderr)", 2);
    assertThat(result.stdout().strip()).isEqualTo("[{\"a\": 1}]");
    assertThat(result.stderr().strip()).isEqualTo("warn");
  }

  /** stderr 를 파이프 버퍼(~64KB)보다 많이 써도 교착하지 않는다 — stderr 는 파일로 받는다. */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void run_largeStderr_doesNotDeadlock() {
    PythonScriptExecutor.RunResult result =
        pythonScriptExecutor.run("import sys\nsys.stderr.write('e' * 300000)\nprint('done')", 2);
    assertThat(result.succeeded()).isTrue();
    assertThat(result.stdout().strip()).isEqualTo("done");
    assertThat(result.stderr()).hasSize(300000);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void run_envVars_onlyContainSlotRoleCredentials() {
    // 스크립트가 모든 환경변수를 출력하여 앱 자격증명·테넌트 실행 롤이 없는지 확인
    String script =
        """
        import os
        import json
        env_vars = dict(os.environ)
        print(json.dumps(env_vars))
        """;

    String stdout = pythonScriptExecutor.run(script, 3).stdout();

    assertThat(stdout).contains("DB_URL");
    assertThat(stdout).contains(TenantPipelineRole.pythonReadRoleName(DEFAULT_TEST_TENANT_ID, 3));
    assertThat(stdout).contains("DB_SCHEMA");
    // 테넌트 실행 롤(쓰기 가능·전 등급 읽기)은 자식에게 가지 않는다
    assertThat(stdout).doesNotContain(TenantPipelineRole.roleName(DEFAULT_TEST_TENANT_ID));
    assertThat(stdout).doesNotContain("pipeline_executor");

    // 앱 자격증명/비밀이 없어야 함
    assertThat(stdout).doesNotContain("JWT_SECRET");
    assertThat(stdout).doesNotContain("ENCRYPTION_MASTER_KEY");
    assertThat(stdout).doesNotContain("AGENT_INTERNAL_TOKEN");
  }

  /**
   * 로컬 경로도 슬롯 롤로 접속한다 — 테넌트 실행 롤 자격증명이 자식 프로세스로 새지 않는다. #681 회귀 가드(자격증명과 스키마가 같은 테넌트)도 함께 — 반드시 1 이
   * 아닌 테넌트로 본다(테넌트 1 에서는 스키마 불일치 증상이 숨는다).
   */
  @Test
  void buildEnvironment_usesSlotRoleNotTenantRole() {
    Map<String, String> env = pythonScriptExecutor.buildEnvironment(2L, 3);

    // executor 계약과 같이 DB_URL·DB_SCHEMA 만 — 개별 자격증명 키는 없다(CR8)
    assertThat(env).containsOnlyKeys("DB_URL", "DB_SCHEMA", "PATH", "HOME");
    assertThat(env.get("DB_URL"))
        .startsWith(
            "postgresql://pipeline_py_t2_s3:"
                + TenantPipelineRole.pythonReadPassword(2, 3, rolePasswordSecret)
                + "@")
        .doesNotContain("jdbc:")
        .doesNotContain("?");
    assertThat(env.values()).noneMatch(v -> v.contains("pipeline_executor_t"));
    assertThat(env.get("DB_SCHEMA")).isEqualTo("data_t2");
  }

  /** JDBC URL → libpq URI 변환은 JDBC 전용 쿼리 매개변수를 버린다(libpq 가 모르는 이름이면 접속 오류). */
  @Test
  void libpqUrl_dropsJdbcQueryAndInjectsSlotCredentials() {
    assertThat(
            PythonScriptExecutor.libpqUrl(
                "jdbc:postgresql://db:5432/smartfirehub?loggerLevel=OFF", "r", "p"))
        .isEqualTo("postgresql://r:p@db:5432/smartfirehub");
  }

  /** 범위 밖 슬롯은 자식을 띄우지 않고 거부한다(테넌트 롤 폴백 없음). */
  @Test
  void run_outOfRangeSlot_isRejectedBeforeSpawning() {
    assertThatThrownBy(() -> pythonScriptExecutor.run("print(1)", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pythonScriptExecutor.run("print(1)", 11))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 비정상 종료는 예외가 아니라 결과다 — 메시지 조립(어느 스트림을 보일지)은 러너가 executor 와 같은 규칙으로 한다. traceback 은 stderr. */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void run_scriptError_returnsExitCodeAndStderr() {
    PythonScriptExecutor.RunResult result =
        pythonScriptExecutor.run("raise ValueError('test error')", 2);
    assertThat(result.succeeded()).isFalse();
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.stderr()).contains("ValueError: test error");
    assertThat(result.stdout()).isEmpty();
  }
}
