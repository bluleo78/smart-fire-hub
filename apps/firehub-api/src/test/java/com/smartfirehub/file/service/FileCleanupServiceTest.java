package com.smartfirehub.file.service;

import static com.smartfirehub.jooq.Tables.UPLOADED_FILES;
import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FileCleanupService 통합 테스트.
 *
 * <p>jOOQ DSLContext를 실제 DB와 함께 사용하여 만료 파일 정리 로직을 검증한다. UPLOADED_FILES 테이블에 만료된/유효한 레코드를 삽입하고
 * cleanupExpiredFiles() 호출 후 DB 레코드 삭제 여부를 검증한다. 실제 파일 I/O는 @TempDir을 활용하여 격리한다.
 *
 * <p><b>클래스 레벨 {@code @Transactional} 을 뺐다 — 의도된 것이다(P2-b Task 9).</b> 붙어 있으면
 * 정리 서비스의 테넌트 순회가 테스트 트랜잭션에 얹혀 <b>모든 순회 패스가 테넌트 1 의 GUC 로</b>
 * 실행된다. 즉 순회 배선을 통째로 지워도 테스트가 통과하는 구조적 사각지대가 생긴다. 픽스처와
 * 검증 조회만 테넌트 트랜잭션으로 감싸고, 검증 대상인 {@code cleanupExpiredFiles()} 는 트랜잭션
 * 밖에 남긴다.
 *
 * <p>롤백이 사라졌으므로 심은 행은 {@link #cleanup()} 에서 직접 지우고, 유니크 컬럼(username/email)은
 * 실행마다 고유해야 한다.
 */
class FileCleanupServiceTest extends IntegrationTestBase {

  @Autowired private FileCleanupService fileCleanupService;
  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  /** 테스트용 사용자 ID — uploaded_by FK 제약 충족을 위해 필요 */
  private Long testUserId;

  @BeforeEach
  void setUp() {
    // "user" 는 테넌트 경계 위의 전역 테이블이라 컨텍스트 없이 만들 수 있다.
    String unique = String.valueOf(System.nanoTime());
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "file_cleanup_user_" + unique)
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "File Cleanup User")
            .set(USER.EMAIL, "file_cleanup_" + unique + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
  }

  @AfterEach
  void cleanup() {
    // uploaded_files 는 RLS 대상(V96)이라 트랜잭션 밖 삭제는 0행이 되고 user 삭제가 FK 로 터진다.
    inTenantTx(
        () -> dsl.deleteFrom(UPLOADED_FILES).where(UPLOADED_FILES.UPLOADED_BY.eq(testUserId)).execute());
    dsl.deleteFrom(USER).where(USER.ID.eq(testUserId)).execute();
  }

  // =========================================================================
  // cleanupExpiredFiles — 만료 파일 정리
  // =========================================================================

  /**
   * 정상: 만료된 파일 레코드가 있으면 DB에서 삭제되어야 한다. expires_at이 과거인 레코드를 삽입 후 cleanupExpiredFiles() 호출 시 해당 레코드가
   * 삭제된다.
   */
  @Test
  void cleanupExpiredFiles_expiredRecord_deletedFromDb(@TempDir Path tempDir) throws IOException {
    // 만료된 파일 생성 (실제 파일 경로를 DB에 저장)
    Path expiredFile = tempDir.resolve("expired-upload.csv");
    Files.createFile(expiredFile);

    insertUploadedFile("expired-upload.csv", expiredFile, expiredAt());

    fileCleanupService.cleanupExpiredFiles();

    // DB 레코드가 삭제되어야 함
    assertThat(countByPath(expiredFile)).isEqualTo(0);

    // 실제 파일도 삭제되어야 함
    assertThat(expiredFile).doesNotExist();
  }

  /** 정상: 만료되지 않은 파일 레코드는 삭제되지 않아야 한다. expires_at이 미래인 레코드는 cleanupExpiredFiles() 호출 후에도 유지된다. */
  @Test
  void cleanupExpiredFiles_validRecord_notDeleted(@TempDir Path tempDir) throws IOException {
    Path validFile = tempDir.resolve("valid-upload.csv");
    Files.createFile(validFile);

    // 유효한 레코드 삽입 (expires_at = 1시간 후)
    insertUploadedFile("valid-upload.csv", validFile, OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));

    fileCleanupService.cleanupExpiredFiles();

    // DB 레코드가 유지되어야 함
    assertThat(countByPath(validFile)).isEqualTo(1);

    // 실제 파일도 유지되어야 함
    assertThat(validFile).exists();
  }

  /** 엣지 케이스: 만료된 레코드가 없으면 아무것도 삭제하지 않아야 한다. 빈 테이블에서 cleanupExpiredFiles() 호출 시 예외 없이 종료된다. */
  @Test
  void cleanupExpiredFiles_noExpiredRecords_noException() {
    // UPLOADED_FILES 테이블이 비어있는 상태에서 호출
    fileCleanupService.cleanupExpiredFiles();

    // 예외 없이 완료 (assertion 불필요 — 예외 발생 시 테스트 실패)
  }

  /**
   * 버그 회귀: 디스크 파일 삭제 실패 시 DB 레코드는 유지되어야 한다.
   *
   * <p>존재하지 않는 경로(삭제 불가 경로)를 DB에 등록한 후 cleanupExpiredFiles() 호출 시 DB 레코드가 유지되는지 검증한다. 이슈 #152: 디스크
   * 삭제 실패 시에도 DB 레코드가 삭제되어 고아 파일(orphan) 발생하는 버그 재현 방지.
   */
  @Test
  void cleanupExpiredFiles_diskDeleteFails_dbRecordRetained(@TempDir Path tempDir)
      throws IOException {
    // 만료된 파일을 생성하고 권한을 제거하여 삭제 불가 상태로 만듦
    Path undeletableFile = tempDir.resolve("locked-upload.csv");
    Files.createFile(undeletableFile);

    insertUploadedFile("locked-upload.csv", undeletableFile, expiredAt());

    // 부모 디렉토리를 읽기 전용으로 설정하여 Files.deleteIfExists() 실패 유도
    undeletableFile.toFile().getParentFile().setWritable(false);

    try {
      fileCleanupService.cleanupExpiredFiles();

      // 디스크 삭제 실패 시 DB 레코드는 반드시 유지되어야 함 (고아 파일 방지)
      assertThat(countByPath(undeletableFile)).isEqualTo(1);
    } finally {
      // 테스트 후 디렉토리 쓰기 권한 복원 (TempDir 정리를 위해)
      undeletableFile.toFile().getParentFile().setWritable(true);
    }
  }

  /**
   * 혼합 시나리오: 일부 파일 삭제 성공, 일부 실패 시 성공한 파일의 DB 레코드만 삭제되어야 한다.
   *
   * <p>이슈 #152 수정 검증 — 디스크 삭제 성공 경로만 IN 절로 지정하여 DB 삭제하는 로직을 확인한다.
   */
  @Test
  void cleanupExpiredFiles_partialDiskFailure_onlySuccessfulRecordsDeleted(@TempDir Path tempDir)
      throws IOException {
    // 삭제 가능한 만료 파일
    Path deletableFile = tempDir.resolve("deletable-upload.csv");
    Files.createFile(deletableFile);

    // 삭제 불가 만료 파일 (부모 디렉토리를 별도 서브디렉토리로 분리)
    Path lockedDir = tempDir.resolve("locked");
    Files.createDirectory(lockedDir);
    Path undeletableFile = lockedDir.resolve("locked-upload.csv");
    Files.createFile(undeletableFile);

    insertUploadedFile("deletable-upload.csv", deletableFile, expiredAt());
    insertUploadedFile("locked-upload.csv", undeletableFile, expiredAt());

    // lockedDir 쓰기 권한 제거로 undeletableFile 삭제 불가 유도
    lockedDir.toFile().setWritable(false);

    try {
      fileCleanupService.cleanupExpiredFiles();

      // 삭제 성공한 파일의 DB 레코드는 삭제되어야 함
      assertThat(countByPath(deletableFile)).isEqualTo(0);

      // 삭제 실패한 파일의 DB 레코드는 유지되어야 함
      assertThat(countByPath(undeletableFile)).isEqualTo(1);
    } finally {
      lockedDir.toFile().setWritable(true);
    }
  }

  // ── 픽스처·검증 헬퍼 ──────────────────────────────────────────────────────
  //
  // 픽스처 INSERT 와 검증 조회만 테넌트 트랜잭션 안에서 돈다. 검증 대상인 cleanupExpiredFiles()
  // 는 밖에 남겨야 "잡이 스스로 테넌트와 트랜잭션을 확보하는가" 를 계속 검증할 수 있다.

  private OffsetDateTime expiredAt() {
    return OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
  }

  private void insertUploadedFile(String originalName, Path path, OffsetDateTime expiresAt) {
    inTenantTx(
        () ->
            dsl.insertInto(UPLOADED_FILES)
                .set(UPLOADED_FILES.ORIGINAL_NAME, originalName)
                .set(UPLOADED_FILES.STORED_NAME, originalName + "-stored")
                .set(UPLOADED_FILES.STORAGE_PATH, path.toString())
                .set(UPLOADED_FILES.MIME_TYPE, "text/csv")
                .set(UPLOADED_FILES.FILE_SIZE, 100L)
                .set(UPLOADED_FILES.FILE_CATEGORY, "IMPORT")
                .set(UPLOADED_FILES.UPLOADED_BY, testUserId)
                .set(UPLOADED_FILES.EXPIRES_AT, expiresAt)
                .execute());
  }

  private int countByPath(Path path) {
    return inTenantTx(
        () -> dsl.fetchCount(UPLOADED_FILES, UPLOADED_FILES.STORAGE_PATH.eq(path.toString())));
  }

  // runInTenantTransaction 이 진입 전 컨텍스트를 복원하므로, 픽스처 뒤에도 기본 테넌트가 남는다 —
  // 검증 대상인 cleanupExpiredFiles() 는 그 컨텍스트에서(트랜잭션 없이) 순회를 시작한다.
  private <T> T inTenantTx(Supplier<T> action) {
    return TenantRlsTestSupport.runInTenantTransaction(tx, DEFAULT_TEST_TENANT_ID, action);
  }
}
