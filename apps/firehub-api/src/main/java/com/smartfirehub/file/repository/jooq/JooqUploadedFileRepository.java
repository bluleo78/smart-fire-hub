package com.smartfirehub.file.repository.jooq;

import static com.smartfirehub.jooq.Tables.UPLOADED_FILES;

import com.smartfirehub.file.repository.UploadedFileRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * UploadedFileRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>uploaded_files(채팅 첨부 업로드 파일 메타데이터) 접근 리포지토리.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 일부러 붙이지 않는다 — 호출자(FileUploadService·FileCleanupService)가 디스크
 * I/O 를 트랜잭션 밖에 두려고 {@code TransactionTemplate}·{@code @Transactional} 로 DB 구간만 좁게 감싼다. 여기서 트랜잭션을
 * 더하면 그 경계가 바뀐다. uploaded_files 는 RLS 대상이라 GUC(app.tenant_id)는 호출자가 연 트랜잭션에서만 주입된다 — 반드시 호출자의 트랜잭션
 * 안에서 부른다.
 */
@Repository
@RequiredArgsConstructor
public class JooqUploadedFileRepository implements UploadedFileRepository {

  private final DSLContext dsl;

  /**
   * 업로드 파일 메타데이터를 저장하고 생성된 id 를 돌려준다.
   *
   * @return 새 행의 id
   */
  @Override
  public Long insert(
      String originalName,
      String storedName,
      String mimeType,
      long fileSize,
      String category,
      String storagePath,
      Long userId,
      OffsetDateTime createdAt,
      OffsetDateTime expiresAt) {
    return dsl.insertInto(UPLOADED_FILES)
        .set(UPLOADED_FILES.ORIGINAL_NAME, originalName)
        .set(UPLOADED_FILES.STORED_NAME, storedName)
        .set(UPLOADED_FILES.MIME_TYPE, mimeType)
        .set(UPLOADED_FILES.FILE_SIZE, fileSize)
        .set(UPLOADED_FILES.FILE_CATEGORY, category)
        .set(UPLOADED_FILES.STORAGE_PATH, storagePath)
        .set(UPLOADED_FILES.UPLOADED_BY, userId)
        .set(UPLOADED_FILES.CREATED_AT, createdAt)
        .set(UPLOADED_FILES.EXPIRES_AT, expiresAt)
        .returning(UPLOADED_FILES.ID)
        .fetchOne()
        .getId();
  }

  /** id 와 업로더가 모두 일치하는 파일을 찾는다. 남의 파일은 없는 것처럼 비어 돌아온다. */
  @Override
  public Optional<UploadedFile> findByIdAndUploader(Long fileId, Long userId) {
    var record =
        dsl.selectFrom(UPLOADED_FILES)
            .where(UPLOADED_FILES.ID.eq(fileId))
            .and(UPLOADED_FILES.UPLOADED_BY.eq(userId))
            .fetchOne();
    if (record == null) {
      return Optional.empty();
    }
    return Optional.of(
        new UploadedFile(
            record.getId(),
            record.getOriginalName(),
            record.getMimeType(),
            record.getFileSize(),
            record.getFileCategory(),
            record.getStoragePath(),
            record.getCreatedAt()));
  }

  /** 만료 시각이 now 보다 앞선 파일의 id·저장 경로. */
  @Override
  public List<ExpiredFile> findExpired(OffsetDateTime now) {
    return dsl.select(UPLOADED_FILES.ID, UPLOADED_FILES.STORAGE_PATH)
        .from(UPLOADED_FILES)
        .where(UPLOADED_FILES.EXPIRES_AT.lt(now))
        .fetch(r -> new ExpiredFile(r.value1(), r.value2()));
  }

  /**
   * id 목록 중 아직 만료 상태인 행만 지운다. 만료 조건을 한 번 더 걸어, 조회 이후 갱신돼 더는 만료가 아닌 행을 지우지 않게 한다.
   *
   * @return 삭제한 행 수
   */
  @Override
  public int deleteExpiredByIds(List<Long> ids, OffsetDateTime now) {
    return dsl.deleteFrom(UPLOADED_FILES)
        .where(UPLOADED_FILES.ID.in(ids))
        .and(UPLOADED_FILES.EXPIRES_AT.lt(now))
        .execute();
  }
}
