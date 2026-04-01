package com.smartfirehub.dataimport.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ExportFileCleanupService {
  private static final Path EXPORT_DIR =
      Path.of(System.getProperty("java.io.tmpdir"), "firehub-exports");
  private static final long RETENTION_HOURS = 24;

  @Scheduled(fixedRate = 3_600_000)
  public void cleanupExpiredExportFiles() {
    if (!Files.exists(EXPORT_DIR)) return;

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(EXPORT_DIR)) {
      int deleted = 0;
      for (Path file : stream) {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        Instant createdAt = attrs.creationTime().toInstant();
        if (createdAt.isBefore(Instant.now().minus(RETENTION_HOURS, ChronoUnit.HOURS))) {
          Files.deleteIfExists(file);
          deleted++;
        }
      }
      if (deleted > 0) {
        log.info("Cleaned up {} expired export file(s) older than {}h", deleted, RETENTION_HOURS);
      }
    } catch (IOException e) {
      log.warn("Failed to cleanup export files: {}", e.getMessage());
    }
  }
}
