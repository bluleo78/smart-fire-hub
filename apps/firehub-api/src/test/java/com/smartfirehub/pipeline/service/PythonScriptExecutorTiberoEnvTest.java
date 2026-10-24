package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 로컬(실행기 끔) PYTHON 자식 프로세스의 Tibero 환경(WD-78, 스펙 §5.3).
 *
 * <ul>
 *   <li>자식에게는 {@code GENIA_DB_URL}(= {@code app.db-executor.base-url})·{@code
 *       GENIA_DB_TOKEN}·{@code PYTHONPATH}(= {@code app.pipeline.python-helper-dir} 절대 경로) 만 준다. PG
 *       접속({@code DB_URL}·{@code DB_SCHEMA})은 없다 — Tibero 읽기는 db-executor 로만.
 *   <li>도우미 경로·db-executor 주소가 비면 띄우지 않는다(fail-closed).
 *   <li>자식이 토큰을 찍어도 돌려주는 stdout·stderr 에는 남지 않는다(D3).
 * </ul>
 */
class PythonScriptExecutorTiberoEnvTest {

  private static final String TOKEN = "v1.5.4.9.1999999999.c2lnbmF0dXJlLWZvci1sb2NhbC10ZXN0";
  private static final String DBX = "http://localhost:8090";

  /** 리포 안 도우미 위치 — application-local.yml 의 상대 경로와 같다(api 앱 디렉터리 기준). */
  private static final String HELPER_DIR = "../firehub-executor/sandbox";

  private PythonScriptExecutor executor;

  @BeforeEach
  void setUp() {
    executor = new PythonScriptExecutor();
    ReflectionTestUtils.setField(executor, "dbExecutorBaseUrl", DBX);
    ReflectionTestUtils.setField(executor, "pythonHelperDir", HELPER_DIR);
  }

  @Test
  void tiberoEnvironment_hasOnlyHelperSettings_noPgUrl() {
    Map<String, String> env = executor.buildTiberoEnvironment(TOKEN);

    assertThat(env).containsEntry("GENIA_DB_URL", DBX).containsEntry("GENIA_DB_TOKEN", TOKEN);
    assertThat(env).doesNotContainKeys("DB_URL", "DB_SCHEMA");
    // 상대 경로는 자식 작업 디렉터리에 따라 뜻이 바뀌므로 절대 경로로 넘긴다.
    assertThat(Path.of(env.get("PYTHONPATH")))
        .isAbsolute()
        .isEqualTo(Path.of(HELPER_DIR).toAbsolutePath().normalize());
    assertThat(env.keySet())
        .containsExactlyInAnyOrder("GENIA_DB_URL", "GENIA_DB_TOKEN", "PYTHONPATH", "PATH", "HOME");
  }

  @Test
  void missingHelperDir_failsBeforeLaunch() {
    ReflectionTestUtils.setField(executor, "pythonHelperDir", " ");
    assertThatThrownBy(() -> executor.runTibero("print(1)", TOKEN))
        .isInstanceOf(ScriptExecutionException.class)
        // 코드리뷰 3: 스텝 오류(사용자 화면)에는 설정 키 이름을 싣지 않는다 — 자세한 사유는 서버 WARN 로그에만 남긴다.
        .hasMessage(PythonScriptExecutor.TIBERO_SETUP_MISSING)
        .hasMessageNotContaining("app.pipeline")
        .hasMessageNotContaining(TOKEN);
  }

  @Test
  void missingDbExecutorUrl_failsBeforeLaunch() {
    ReflectionTestUtils.setField(executor, "dbExecutorBaseUrl", "");
    assertThatThrownBy(() -> executor.runTibero("print(1)", TOKEN))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessage(PythonScriptExecutor.TIBERO_SETUP_MISSING)
        .hasMessageNotContaining("app.db-executor")
        .hasMessageNotContaining("DB_EXECUTOR_");
  }

  @Test
  void missingToken_failsBeforeLaunch() {
    assertThatThrownBy(() -> executor.runTibero("print(1)", " "))
        .isInstanceOf(ScriptExecutionException.class)
        .hasMessageContaining("스크립트 토큰");
  }

  /** 실제 자식 프로세스: 도우미를 import 할 수 있고 PG 접속 정보는 없다. 토큰을 찍어도 결과에는 가려진다. */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void runTibero_childCanImportHelper_hasNoPgEnv_andTokenIsRedacted() {
    PythonScriptExecutor.RunResult result =
        executor.runTibero(
            "import os, sys, genia_db\n"
                + "print(os.environ.get('DB_URL'), os.environ.get('DB_SCHEMA'), os.environ['GENIA_DB_URL'])\n"
                + "print(os.environ['GENIA_DB_TOKEN'], file=sys.stderr)\n"
                + "print(os.environ['GENIA_DB_TOKEN'])\n",
            TOKEN);

    assertThat(result.succeeded()).as(result.stderr()).isTrue();
    assertThat(result.stdout()).contains("None None " + DBX);
    assertThat(result.stdout()).doesNotContain(TOKEN);
    assertThat(result.stderr()).doesNotContain(TOKEN);
  }
}
