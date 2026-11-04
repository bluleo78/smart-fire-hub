package com.smartfirehub.file.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * uploaded_files(채팅 첨부 업로드 파일 메타데이터) 접근 리포지토리.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 일부러 붙이지 않는다 — 호출자(FileUploadService·FileCleanupService)가 디스크
 * I/O 를 트랜잭션 밖에 두려고 {@code TransactionTemplate}·{@code @Transactional} 로 DB 구간만 좁게 감싼다. 여기서 트랜잭션을
 * 더하면 그 경계가 바뀐다. uploaded_files 는 RLS 대상이라 GUC(app.tenant_id)는 호출자가 연 트랜잭션에서만 주입된다 — 반드시 호출자의 트랜잭션
 * 안에서 부른다.
 *
 * <p>구현은 {@code JooqUploadedFileRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface UploadedFileRepository {

  /** 업로드 파일 한 건의 메타데이터 값 객체(조회용). */
  record UploadedFile(
      Long id,
      String originalName,
      String mimeType,
      Long fileSize,
      String fileCategory,
      String storagePath,
      OffsetDateTime createdAt) {}

  /** 만료 정리 대상 한 건 — 디스크 삭제에 경로, DB 삭제에 id 를 쓴다. */
  record ExpiredFile(Long id, String storagePath) {}

  /**
   * 업로드 파일 메타데이터를 저장하고 생성된 id 를 돌려준다.
   *
   * @return 새 행의 id
   */
  Long insert(
      String originalName,
      String storedName,
      String mimeType,
      long fileSize,
      String category,
      String storagePath,
      Long userId,
      OffsetDateTime createdAt,
      OffsetDateTime expiresAt);

  /** id 와 업로더가 모두 일치하는 파일을 찾는다. 남의 파일은 없는 것처럼 비어 돌아온다. */
  Optional<UploadedFile> findByIdAndUploader(Long fileId, Long userId);

  /** 만료 시각이 now 보다 앞선 파일의 id·저장 경로. */
  List<ExpiredFile> findExpired(OffsetDateTime now);

  /**
   * id 목록 중 아직 만료 상태인 행만 지운다. 만료 조건을 한 번 더 걸어, 조회 이후 갱신돼 더는 만료가 아닌 행을 지우지 않게 한다.
   *
   * @return 삭제한 행 수
   */
  int deleteExpiredByIds(List<Long> ids, OffsetDateTime now);
}
