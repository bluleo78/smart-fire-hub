package com.smartfirehub.file.repository.mybatis;

import com.smartfirehub.file.repository.UploadedFileRepository;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * UploadedFileRepository 의 MyBatis 구현(WD-712) — {@code JooqUploadedFileRepository} 와 같은 의미.
 *
 * <p>jOOQ 와 같이 {@code @Transactional} 을 달지 않는다 — 호출자(FileUploadService·FileCleanupService)가 디스크 I/O
 * 를 트랜잭션 밖에 두려고 DB 구간만 좁게 감싼다. uploaded_files 는 RLS 대상이라 테넌트 컨텍스트는 호출자가 연 트랜잭션에서만 심긴다.
 *
 * <p>시각은 {@code Instant} 로 바인딩·조회하고 UTC {@link OffsetDateTime} 으로 바꾼다 — JVM 기본 시간대(KST 등)와 무관하다. 목록
 * 삭제는 Tibero IN 목록 상한(1000)을 피하려 {@link InChunks} 로 나눠 한 문장에 OR 로 잇는다.
 */
@PlatformMyBatisRepository
public class MyBatisUploadedFileRepository implements UploadedFileRepository {

  private final UploadedFileMapper mapper;

  /** 매퍼를 주입받는다(플랫폼 MyBatis 빈 — 매퍼 SQL 은 mybatis/ 아래 XML). */
  public MyBatisUploadedFileRepository(UploadedFileMapper mapper) {
    this.mapper = mapper;
  }

  /** 업로드 파일 메타데이터를 저장하고 생성된 id 를 돌려준다(RETURNING 대신 생성 키). */
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
    UploadedFileInsert row =
        new UploadedFileInsert(
            originalName,
            storedName,
            mimeType,
            fileSize,
            category,
            storagePath,
            userId,
            createdAt.toInstant(),
            expiresAt.toInstant());
    mapper.insert(row);
    return row.getId();
  }

  /** id 와 업로더가 모두 일치하는 파일을 찾는다. 남의 파일은 없는 것처럼 비어 돌아온다. */
  @Override
  public Optional<UploadedFile> findByIdAndUploader(Long fileId, Long userId) {
    UploadedFileRow r = mapper.selectByIdAndUploader(fileId, userId);
    if (r == null) {
      return Optional.empty();
    }
    return Optional.of(
        new UploadedFile(
            r.id(),
            r.originalName(),
            r.mimeType(),
            r.fileSize(),
            r.fileCategory(),
            r.storagePath(),
            OffsetDateTime.ofInstant(r.createdAt(), ZoneOffset.UTC)));
  }

  /** 만료 시각이 now 보다 앞선(경계 시각은 제외) 파일의 id·저장 경로. */
  @Override
  public List<ExpiredFile> findExpired(OffsetDateTime now) {
    return mapper.selectExpired(now.toInstant());
  }

  /** id 목록 중 아직 만료 상태인 행만 지운다. 빈 목록이면 아무것도 지우지 않고 0 이다. */
  @Override
  public int deleteExpiredByIds(List<Long> ids, OffsetDateTime now) {
    List<List<Long>> chunks = InChunks.of(ids);
    if (chunks.isEmpty()) {
      return 0;
    }
    return mapper.deleteExpiredByIds(chunks, now.toInstant());
  }
}
