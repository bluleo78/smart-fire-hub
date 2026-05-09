package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
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

  @Value("${app.pipeline.datasource.username}")
  private String pipelineDbUser;

  @Value("${app.pipeline.datasource.password}")
  private String pipelineDbPassword;

  public String execute(String scriptContent) {
    Path tempFile = null;
    // process를 try 바깥에 선언해 catch(InterruptedException)에서 강제 종료 가능하게 함
    Process process = null;
    try {
      // Write script to temp file
      tempFile = Files.createTempFile("pipeline_script_", ".py");
      Files.writeString(tempFile, scriptContent);

      log.info("Executing Python script from temp file: {}", tempFile);

      // Build process
      ProcessBuilder pb = new ProcessBuilder("python3", tempFile.toString());

      // 모든 상속된 환경변수 제거 (환경 누출 방어)
      pb.environment().clear();

      // 파이프라인 전용 자격증명만 설정
      pb.environment().put("DB_URL", pipelineDbUrl);
      pb.environment().put("DB_USER", pipelineDbUser);
      pb.environment().put("DB_PASSWORD", pipelineDbPassword);
      pb.environment().put("DB_SCHEMA", "data");
      pb.environment().put("PATH", "/usr/bin:/usr/local/bin");
      pb.environment().put("HOME", "/tmp");

      pb.redirectErrorStream(true);

      // Start process
      process = pb.start();

      // Capture output
      StringBuilder output = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append("\n");
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

      if (exitCode != 0) {
        String errorMessage =
            "Python script failed with exit code " + exitCode + ": " + output.toString();
        log.error(errorMessage);
        throw new ScriptExecutionException(errorMessage);
      }

      log.info("Python script executed successfully");
      return output.toString();

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
      // Clean up temp file
      if (tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (Exception e) {
          log.warn("Failed to delete temp file: {}", tempFile, e);
        }
      }
    }
  }
}
