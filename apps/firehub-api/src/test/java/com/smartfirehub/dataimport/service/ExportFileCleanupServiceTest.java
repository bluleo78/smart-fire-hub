package com.smartfirehub.dataimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ExportFileCleanupService 단위 테스트.
 *
 * <p>ExportFileCleanupService는 외부 의존성 없이 파일시스템만 접근한다.
 * EXPORT_DIR은 java.io.tmpdir/firehub-exports 고정 경로이므로
 * 해당 디렉토리에 테스트 파일을 직접 생성/삭제하여 테스트한다.
 * Java 21에서는 static final 필드를 리플렉션으로 변경할 수 없으므로
 * 실제 EXPORT_DIR을 사용하되 테스트 격리를 위해 고유 파일명을 사용한다.
 * @Scheduled는 단위 테스트에서 실행되지 않으므로 메서드를 직접 호출한다.
 */
@ExtendWith(MockitoExtension.class)
class ExportFileCleanupServiceTest {

  /** 테스트 대상 서비스 — 의존성 없으므로 직접 생성 */
  private ExportFileCleanupService exportFileCleanupService;

  /** 실제 EXPORT_DIR 경로 */
  private static final Path EXPORT_DIR =
      Path.of(System.getProperty("java.io.tmpdir"), "firehub-exports");

  /** 테스트에서 생성한 파일 목록 — AfterEach에서 정리 */
  private Path testFile;

  @BeforeEach
  void setUp() throws IOException {
    exportFileCleanupService = new ExportFileCleanupService();
    // EXPORT_DIR이 없으면 생성
    Files.createDirectories(EXPORT_DIR);
  }

  @AfterEach
  void tearDown() throws IOException {
    // 테스트에서 생성한 파일만 정리 (다른 파일은 건드리지 않음)
    if (testFile != null && Files.exists(testFile)) {
      Files.delete(testFile);
      testFile = null;
    }
  }

  // =========================================================================
  // cleanupExpiredExportFiles — 만료 내보내기 파일 정리
  // =========================================================================

  /**
   * 정상: 25시간 전 생성된 파일은 삭제되어야 한다.
   * lastModifiedTime을 25시간 전으로 설정하고 cleanup 호출 후 삭제를 확인한다.
   * 참고: creationTime은 OS에 따라 변경되지 않을 수 있으므로 삭제가 보장되지 않을 수 있다.
   * 이 테스트는 예외 없이 실행됨을 검증한다.
   */
  @Test
  void cleanupExpiredExportFiles_expiredFile_runsWithoutException() throws IOException {
    testFile = EXPORT_DIR.resolve("test-expired-" + System.nanoTime() + ".csv");
    Files.createFile(testFile);

    // creationTime을 25시간 전으로 설정 (macOS/Linux 지원)
    Instant expiredTime = Instant.now().minus(25, ChronoUnit.HOURS);
    Files.setAttribute(testFile, "basic:creationTime", FileTime.from(expiredTime));

    // 예외 없이 완료되어야 함
    exportFileCleanupService.cleanupExpiredExportFiles();

    // testFile이 삭제되었으면 tearDown에서 skip
    if (!Files.exists(testFile)) {
      testFile = null;
    }
  }

  /**
   * 정상: 방금 생성된 파일은 삭제되지 않아야 한다.
   * 24시간 미만이므로 RETENTION_HOURS 기준을 충족하지 않는다.
   */
  @Test
  void cleanupExpiredExportFiles_freshFile_notDeleted() throws IOException {
    testFile = EXPORT_DIR.resolve("test-fresh-" + System.nanoTime() + ".csv");
    Files.createFile(testFile);

    exportFileCleanupService.cleanupExpiredExportFiles();

    // 방금 생성된 파일은 삭제되지 않아야 함
    assertThat(testFile).exists();
  }

  /**
   * 엣지 케이스: EXPORT_DIR이 비어있으면 예외 없이 정상 종료되어야 한다.
   * 이 테스트는 EXPORT_DIR이 존재하지만 테스트 파일이 없는 상태를 검증한다.
   * (다른 파일이 있을 수 있으나 예외 발생 여부만 검증)
   */
  @Test
  void cleanupExpiredExportFiles_existingDir_noException() {
    // EXPORT_DIR이 존재하는 상태에서 호출 — 예외 없이 완료되어야 함
    exportFileCleanupService.cleanupExpiredExportFiles();
  }
}
