package com.smartfirehub.file.service;

import static com.smartfirehub.jooq.Tables.UPLOADED_FILES;
import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * FileCleanupService 통합 테스트.
 *
 * <p>jOOQ DSLContext를 실제 DB와 함께 사용하여 만료 파일 정리 로직을 검증한다.
 * UPLOADED_FILES 테이블에 만료된/유효한 레코드를 삽입하고 cleanupExpiredFiles() 호출 후
 * DB 레코드 삭제 여부를 검증한다.
 * 실제 파일 I/O는 @TempDir을 활용하여 격리한다.
 */
@Transactional
class FileCleanupServiceTest extends IntegrationTestBase {

  @Autowired private FileCleanupService fileCleanupService;
  @Autowired private DSLContext dsl;

  /** 테스트용 사용자 ID — uploaded_by FK 제약 충족을 위해 필요 */
  private Long testUserId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "file_cleanup_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "File Cleanup User")
            .set(USER.EMAIL, "file_cleanup@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
  }

  // =========================================================================
  // cleanupExpiredFiles — 만료 파일 정리
  // =========================================================================

  /**
   * 정상: 만료된 파일 레코드가 있으면 DB에서 삭제되어야 한다.
   * expires_at이 과거인 레코드를 삽입 후 cleanupExpiredFiles() 호출 시 해당 레코드가 삭제된다.
   */
  @Test
  void cleanupExpiredFiles_expiredRecord_deletedFromDb(@TempDir Path tempDir) throws IOException {
    // 만료된 파일 생성 (실제 파일 경로를 DB에 저장)
    Path expiredFile = tempDir.resolve("expired-upload.csv");
    Files.createFile(expiredFile);

    // UPLOADED_FILES 테이블에 만료된 레코드 삽입 (expires_at = 1시간 전)
    OffsetDateTime expiredAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
    dsl.insertInto(UPLOADED_FILES)
        .set(UPLOADED_FILES.ORIGINAL_NAME, "expired-upload.csv")
        .set(UPLOADED_FILES.STORED_NAME, "expired-upload-stored.csv")
        .set(UPLOADED_FILES.STORAGE_PATH, expiredFile.toString())
        .set(UPLOADED_FILES.MIME_TYPE, "text/csv")
        .set(UPLOADED_FILES.FILE_SIZE, 100L)
        .set(UPLOADED_FILES.FILE_CATEGORY, "IMPORT")
        .set(UPLOADED_FILES.UPLOADED_BY, testUserId)
        .set(UPLOADED_FILES.EXPIRES_AT, expiredAt)
        .execute();

    fileCleanupService.cleanupExpiredFiles();

    // DB 레코드가 삭제되어야 함
    int remaining = dsl.fetchCount(UPLOADED_FILES,
        UPLOADED_FILES.STORAGE_PATH.eq(expiredFile.toString()));
    assertThat(remaining).isEqualTo(0);

    // 실제 파일도 삭제되어야 함
    assertThat(expiredFile).doesNotExist();
  }

  /**
   * 정상: 만료되지 않은 파일 레코드는 삭제되지 않아야 한다.
   * expires_at이 미래인 레코드는 cleanupExpiredFiles() 호출 후에도 유지된다.
   */
  @Test
  void cleanupExpiredFiles_validRecord_notDeleted(@TempDir Path tempDir) throws IOException {
    Path validFile = tempDir.resolve("valid-upload.csv");
    Files.createFile(validFile);

    // 유효한 레코드 삽입 (expires_at = 1시간 후)
    OffsetDateTime futureExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
    dsl.insertInto(UPLOADED_FILES)
        .set(UPLOADED_FILES.ORIGINAL_NAME, "valid-upload.csv")
        .set(UPLOADED_FILES.STORED_NAME, "valid-upload-stored.csv")
        .set(UPLOADED_FILES.STORAGE_PATH, validFile.toString())
        .set(UPLOADED_FILES.MIME_TYPE, "text/csv")
        .set(UPLOADED_FILES.FILE_SIZE, 100L)
        .set(UPLOADED_FILES.FILE_CATEGORY, "IMPORT")
        .set(UPLOADED_FILES.UPLOADED_BY, testUserId)
        .set(UPLOADED_FILES.EXPIRES_AT, futureExpiry)
        .execute();

    fileCleanupService.cleanupExpiredFiles();

    // DB 레코드가 유지되어야 함
    int remaining = dsl.fetchCount(UPLOADED_FILES,
        UPLOADED_FILES.STORAGE_PATH.eq(validFile.toString()));
    assertThat(remaining).isEqualTo(1);

    // 실제 파일도 유지되어야 함
    assertThat(validFile).exists();
  }

  /**
   * 엣지 케이스: 만료된 레코드가 없으면 아무것도 삭제하지 않아야 한다.
   * 빈 테이블에서 cleanupExpiredFiles() 호출 시 예외 없이 종료된다.
   */
  @Test
  void cleanupExpiredFiles_noExpiredRecords_noException() {
    // UPLOADED_FILES 테이블이 비어있는 상태에서 호출
    fileCleanupService.cleanupExpiredFiles();

    // 예외 없이 완료 (assertion 불필요 — 예외 발생 시 테스트 실패)
  }
}
