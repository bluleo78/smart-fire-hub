package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 자식 {@code python3} 프로세스로 PYTHON 스텝을 실행한다.
 *
 * <p><b>자격증명 이력(#681).</b> 예전에는 {@code app.pipeline.datasource.username/password}(공용 레거시 {@code
 * pipeline_executor})를 넘기면서 {@code DB_SCHEMA} 만 테넌트에서 파생시켰다 — 스키마는 옮기고 자격증명은 두고 온 비대칭이라, 테넌트 2
 * 이상에서는 그 롤에 {@code data_t{id}} USAGE 가 없어 {@code permission denied for schema data_t{id}} 로 터진다.
 * SQL 컬럼 probe 가 같은 계열의 누락으로 실제 운영 장애를 냈고({@link SqlColumnProbe} Javadoc), 이 클래스는 그 자매 결함이었다. 이제
 * {@link SqlScriptExecutor}·{@link SqlColumnProbe} 와 같은 테넌트 실행 롤로 옮겼다가, WD-29 에서 아래 슬롯 롤로 다시 좁혔다.
 *
 * <p><b>WD-29 이후 자격증명은 실행 주체의 읽기 슬롯 롤이다({@code pipeline_py_t{id}_s{k}}).</b> 테넌트 실행 롤은 모든 등급을 읽고 쓸
 * 수 있어, 자식이 그 롤로 접속하면 등급 밖 데이터를 읽는다. 슬롯 롤은 읽기 전용이므로 출력 적재는 executor 와 같은 계약으로 한다 — 스크립트가 stdout 에
 * JSON 행 배열을 내고, API 가 그것을 파싱해 앱 연결로 적재한다({@link LocalPythonOutputLoader}, R5).
 *
 * <p><b>경로가 살아 있는 곳은 {@code app.executor.enabled=false}(로컬 개발 프로필)뿐이다.</b> prod 는 {@code true} 라
 * PYTHON 스텝이 외부 executor 서비스로 가고 이 클래스는 호출되지 않는다. 그래도 고치는 이유: 여기만 공용 자격증명을 계속 읽으면 "공용 롤 정리가 끝났다"는
 * 서술이 거짓이 되고, 로컬에서만 재현되는 잠복 결함이 남는다.
 *
 * @deprecated Use {@link com.smartfirehub.pipeline.service.executor.ExecutorClient#executePython}
 *     instead.
 */
@Deprecated
@Slf4j
@Service
public class PythonScriptExecutor {
  private static final int TIMEOUT_SECONDS = 300;

  @Value("${app.pipeline.datasource.url}")
  private String pipelineDbUrl;

  /**
   * 테넌트별 롤 비밀번호 파생 HMAC 키. 접속 비밀번호를 저장하지 않고 매번 파생하는 근거는 {@link TenantPipelineRole} Javadoc 참조.
   *
   * <p>{@code app.pipeline.datasource.username/password}(공용 롤 자격증명)는 더 이상 읽지 않는다. {@code url} 은 계속
   * 읽는다 — 접속 대상 DB 는 테넌트와 무관하게 같다.
   */
  @Value("${app.pipeline.role-password-secret}")
  private String rolePasswordSecret;

  /**
   * 자식 프로세스 실행 결과 — executor 응답과 같은 재료(종료 코드·stdout·stderr)를 따로 돌려준다.
   *
   * <p>stdout 과 stderr 를 나누는 이유: 출력 테이블이 있으면 stdout 은 적재할 JSON 행이고 실행 로그는 stderr 뿐이다(executor 의
   * {@code python_executor.py} 와 같은 계약, R5). 합치면 JSON 앞뒤에 경고 줄이 섞여 파싱이 깨진다.
   */
  public record RunResult(int exitCode, String stdout, String stderr) {
    public boolean succeeded() {
      return exitCode == 0;
    }
  }

  /**
   * 스크립트를 실행 주체의 읽기 슬롯 롤로 띄운다(WD-29). 종료 코드가 0 이 아니어도 예외가 아니라 결과로 돌려준다 — 실패 메시지 조립(어느 스트림을 보일지)은 출력
   * 테이블 유무를 아는 러너가 executor 와 같은 규칙으로 정한다. 타임아웃·인터럽트·프로세스 기동 실패만 예외다.
   *
   * @param readSlot 실행 주체 슬롯(1~10, PythonReadGrantSync.prepareForRun) — 테넌트 실행 롤로 대체하지 않는다
   */
  public RunResult run(String scriptContent, int readSlot) {
    // 테넌트 확인은 try 밖에서 한다 — MissingTenantScopeException 은 409·"배선 결함" 로그 구분이
    // 걸린 계약이라, ScriptExecutionException 으로 감싸면 스텝 실패와 배선 결함이 같은 모양이 된다
    // (SqlScriptExecutor·SqlColumnProbe 와 같은 배치).
    long tenantId = TenantContext.require("Python 스크립트 실행");
    // 환경 조립도 try 밖이다 — 같은 계약이다. 비밀번호 파생은 비밀 키가 비어 있으면
    // IllegalStateException 으로 fail-fast 하는데(예측 가능한 비밀번호 방지), 아래 catch(Exception)
    // 안에서 터지면 그 설정 오류가 평범한 스텝 실패로 둔갑하고 프로퍼티 이름까지 사용자에게 노출된다.
    // 슬롯 범위 밖도 여기서 IllegalArgumentException(TenantPipelineRole) — 자식은 뜨지 않는다.
    Map<String, String> childEnvironment = buildEnvironment(tenantId, readSlot);
    Path tempFile = null;
    Path stderrFile = null;
    // process를 try 바깥에 선언해 catch(InterruptedException)에서 강제 종료 가능하게 함
    Process process = null;
    try {
      // Write script to temp file
      tempFile = Files.createTempFile("pipeline_script_", ".py");
      Files.writeString(tempFile, scriptContent);
      // stderr 는 파일로 받는다 — 한 스레드가 stdout 을 읽는 동안 stderr 파이프 버퍼(~64KB)가 차면 자식이 막혀 교착한다.
      stderrFile = Files.createTempFile("pipeline_script_", ".stderr");

      log.info("Executing Python script from temp file: {}", tempFile);

      // Build process
      ProcessBuilder pb = new ProcessBuilder("python3", tempFile.toString());

      // 모든 상속된 환경변수 제거 (환경 누출 방어) 후, 이 실행 전용 환경만 다시 채운다.
      pb.environment().clear();
      pb.environment().putAll(childEnvironment);

      pb.redirectError(stderrFile.toFile());

      // Start process
      process = pb.start();

      // Capture stdout
      StringBuilder stdout = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          stdout.append(line).append("\n");
        }
      }

      // Wait for completion with timeout
      boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      if (!finished) {
        // 타임아웃 시 강제 종료 (SIGKILL)
        process.destroyForcibly();
        throw new ScriptExecutionException(
            "Python script execution timed out after " + TIMEOUT_SECONDS + " seconds");
      }

      int exitCode = process.exitValue();
      String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
      if (exitCode != 0) {
        log.error("Python script failed with exit code {}", exitCode);
      } else {
        log.info("Python script executed successfully");
      }
      return new RunResult(exitCode, stdout.toString(), stderr);

    } catch (ScriptExecutionException e) {
      throw e;
    } catch (InterruptedException ie) {
      // waitFor() 블로킹 중 스레드 인터럽트: 자식 프로세스 강제 종료
      // (readLine() 블로킹 중 인터럽트는 InterruptedIOException → catch(Exception)에서 처리)
      log.warn("Python script execution interrupted at waitFor — force-killing child process");
      // 인터럽트 상태 복원 (호출자가 인터럽트를 인지할 수 있도록)
      Thread.currentThread().interrupt();
      throw new ScriptExecutionException("Python execution interrupted", ie);
    } catch (Exception e) {
      // InterruptedIOException 포함: readLine() 블로킹 중 인터럽트된 경우
      boolean isInterrupted =
          e instanceof java.io.InterruptedIOException
              || Thread.currentThread().isInterrupted()
              || (e.getCause() instanceof InterruptedException);
      if (isInterrupted) {
        log.warn("Python script execution interrupted (IO) — force-killing child process");
      } else {
        log.error("Python script execution failed", e);
      }
      throw new ScriptExecutionException("Python execution failed: " + e.getMessage(), e);
    } finally {
      // 정상/예외 종료 모두에서 자식 프로세스가 살아있으면 강제 종료
      // 파이프라인 취소·타임아웃·예외 등 모든 경로에서 좀비 프로세스 방지
      if (process != null && process.isAlive()) {
        log.warn("Python child process still alive at finally — force-killing");
        process.destroyForcibly();
      }
      // Clean up temp files
      deleteQuietly(tempFile);
      deleteQuietly(stderrFile);
    }
  }

  /** 임시 파일 정리 — 실패해도 실행 결과에 영향을 주지 않는다(로그만). */
  private static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (Exception e) {
      log.warn("Failed to delete temp file: {}", file, e);
    }
  }

  /**
   * 자식 프로세스에 넘길 환경변수를 조립한다 — <b>자격증명과 스키마가 같은 테넌트를 가리키는지</b> 를 프로세스를 띄우지 않고 검증할 수 있도록 별도 메서드로
   * 둔다({@code PythonScriptExecutorTest}).
   *
   * <p>{@code DB_SCHEMA} 는 프로세스 경계를 넘어 자식 파이썬이 실제로 접근할 스키마를 정한다. 리터럴을 남기면 Java 전용 리팩터링이 절대 잡지 못하는
   * 자리가 되므로 {@link DataSchema} 에서 파생시키고, 같은 이유로 롤 이름·비밀번호도 {@link TenantPipelineRole} 을 거친다(문자열 직접
   * 조립 금지 — {@code TenantPipelineRoleTest} 가 규약을 고정한다).
   */
  Map<String, String> buildEnvironment(long tenantId, int readSlot) {
    // 슬롯 롤로 직접 로그인(WD-29) — 테넌트 실행 롤(쓰기 가능·스키마 전체 읽기)로 폴백하지 않는다. 슬롯 롤은 실행 주체 등급 위치 1..k 의
    // 데이터셋만 SELECT 할 수 있다. 출력 적재는 자식이 아니라 API 가 stdout JSON 을 받아 앱 연결로 한다(LocalPythonOutputLoader).
    String role = TenantPipelineRole.pythonReadRoleName(tenantId, readSlot);
    String password = TenantPipelineRole.pythonReadPassword(tenantId, readSlot, rolePasswordSecret);
    Map<String, String> env = new LinkedHashMap<>();
    // DB_URL 은 executor 와 같은 libpq URI(슬롯 롤 자격증명 포함) — 같은 스크립트가
    // psycopg2.connect(os.environ["DB_URL"]) 로 두 경로에서 똑같이 접속한다(R5).
    // 개별 자격증명 키(DB_USER·DB_PASSWORD)는 넣지 않는다 — executor 계약과 같이 DB_URL·DB_SCHEMA 만(CR8).
    // 같은 비밀을 두 곳에 두면 env 덤프 시 노출 면만 늘고 두 경로에서 쓸 수 있는 키가 갈라진다.
    env.put("DB_URL", libpqUrl(pipelineDbUrl, role, password));
    env.put("DB_SCHEMA", DataSchema.forTenant(tenantId));
    env.put("PATH", "/usr/bin:/usr/local/bin");
    env.put("HOME", "/tmp");
    return env;
  }

  /**
   * {@code jdbc:postgresql://host:port/db?...} → {@code postgresql://role:pw@host:port/db}. JDBC 전용
   * 쿼리 매개변수는 libpq 가 모르는 이름이 섞여(예: loggerLevel) 접속 오류가 나므로 버린다. 롤 이름·비밀번호(HMAC 16진수)는 URI 예약 문자가
   * 없다.
   */
  static String libpqUrl(String jdbcUrl, String role, String password) {
    String prefix = "jdbc:postgresql://";
    if (jdbcUrl == null || !jdbcUrl.startsWith(prefix)) {
      throw new IllegalStateException("app.pipeline.datasource.url 은 jdbc:postgresql:// 형식이어야 합니다");
    }
    String hostAndDb = jdbcUrl.substring(prefix.length());
    int query = hostAndDb.indexOf('?');
    if (query >= 0) {
      hostAndDb = hostAndDb.substring(0, query);
    }
    return "postgresql://" + role + ":" + password + "@" + hostAndDb;
  }
}
