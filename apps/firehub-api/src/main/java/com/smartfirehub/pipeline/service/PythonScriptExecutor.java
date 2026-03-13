package com.smartfirehub.pipeline.service;

import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PythonScriptExecutor {

  private static final Logger log = LoggerFactory.getLogger(PythonScriptExecutor.class);
  private static final int TIMEOUT_SECONDS = 300;

  @Value("${app.pipeline.datasource.url}")
  private String pipelineDbUrl;

  @Value("${app.pipeline.datasource.username}")
  private String pipelineDbUser;

  @Value("${app.pipeline.datasource.password}")
  private String pipelineDbPassword;

  public String execute(String scriptContent) {
    Path tempFile = null;
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
      Process process = pb.start();

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
    } catch (Exception e) {
      log.error("Python script execution failed", e);
      throw new ScriptExecutionException("Python execution failed: " + e.getMessage(), e);
    } finally {
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
