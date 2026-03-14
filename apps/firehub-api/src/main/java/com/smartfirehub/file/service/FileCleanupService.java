package com.smartfirehub.file.service;

import static com.smartfirehub.jooq.Tables.UPLOADED_FILES;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class FileCleanupService {

  private static final Logger log = LoggerFactory.getLogger(FileCleanupService.class);

  private final DSLContext dsl;

  public FileCleanupService(DSLContext dsl) {
    this.dsl = dsl;
  }

  @PostConstruct
  public void cleanupOnStartup() {
    log.info("Running file cleanup on startup...");
    cleanupExpiredFiles();
  }

  @Scheduled(fixedRate = 3_600_000)
  public void cleanupExpiredFiles() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    List<String> expiredPaths =
        dsl.select(UPLOADED_FILES.STORAGE_PATH)
            .from(UPLOADED_FILES)
            .where(UPLOADED_FILES.EXPIRES_AT.lt(now))
            .fetchInto(String.class);

    if (expiredPaths.isEmpty()) {
      return;
    }

    int deleted = 0;
    for (String storagePath : expiredPaths) {
      try {
        Files.deleteIfExists(Path.of(storagePath));
        deleted++;
      } catch (IOException e) {
        log.warn("Failed to delete file: {}: {}", storagePath, e.getMessage());
      }
    }

    int dbDeleted =
        dsl.deleteFrom(UPLOADED_FILES).where(UPLOADED_FILES.EXPIRES_AT.lt(now)).execute();

    log.info(
        "Cleaned up {} expired uploaded file(s) (files deleted: {}, DB records: {})",
        expiredPaths.size(),
        deleted,
        dbDeleted);
  }
}
