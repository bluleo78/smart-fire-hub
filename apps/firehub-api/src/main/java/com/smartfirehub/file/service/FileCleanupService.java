package com.smartfirehub.file.service;

import static com.smartfirehub.jooq.Tables.UPLOADED_FILES;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileCleanupService {

  private final DSLContext dsl;

  @PostConstruct
  public void cleanupOnStartup() {
    log.info("Running file cleanup on startup...");
    cleanupExpiredFiles();
  }

  /**
   * 만료된 업로드 파일을 정리한다.
   *
   * <p>디스크 파일 삭제에 성공한 경로만 추적하여 해당 경로에 해당하는 DB 레코드만 삭제한다. 디스크 삭제 실패 시 경고 로그만 남기고 DB 레코드는 유지하여 이후
   * 재시도가 가능하도록 한다. 이렇게 함으로써 디스크에 파일이 남아있지만 DB 추적이 사라지는 고아 파일(orphan) 문제를 방지한다.
   */
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

    // 디스크 삭제 성공한 경로만 추적 — 실패한 경로는 DB 레코드를 유지하여 재시도 가능하게 함
    List<String> successfullyDeletedPaths = new ArrayList<>();
    for (String storagePath : expiredPaths) {
      try {
        Files.deleteIfExists(Path.of(storagePath));
        successfullyDeletedPaths.add(storagePath);
      } catch (IOException e) {
        log.warn(
            "디스크 파일 삭제 실패 — DB 레코드를 유지하여 다음 정리 사이클에서 재시도 가능: {}: {}", storagePath, e.getMessage());
      }
    }

    // 디스크 삭제 성공한 경로에 해당하는 DB 레코드만 삭제
    int dbDeleted = 0;
    if (!successfullyDeletedPaths.isEmpty()) {
      dbDeleted =
          dsl.deleteFrom(UPLOADED_FILES)
              .where(UPLOADED_FILES.STORAGE_PATH.in(successfullyDeletedPaths))
              .execute();
    }

    int failedCount = expiredPaths.size() - successfullyDeletedPaths.size();
    log.info(
        "만료 업로드 파일 정리 완료 (전체: {}, 파일 삭제 성공: {}, DB 레코드 삭제: {}, 실패(DB 유지): {})",
        expiredPaths.size(),
        successfullyDeletedPaths.size(),
        dbDeleted,
        failedCount);
  }
}
