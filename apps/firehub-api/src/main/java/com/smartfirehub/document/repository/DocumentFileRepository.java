package com.smartfirehub.document.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.document.dto.DocumentFileResponse;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/** document_file 메타 CRUD + 상태 전이. */
@Repository
@RequiredArgsConstructor
public class DocumentFileRepository {

  private final DSLContext dsl;

  private static final Table<?> T = table(name("document_file"));
  private static final Field<Long> ID = field(name("document_file", "id"), Long.class);
  private static final Field<Long> DATASET_ID = field(name("document_file", "dataset_id"), Long.class);
  private static final Field<String> ORIGINAL_NAME = field(name("document_file", "original_name"), String.class);
  private static final Field<String> MIME_TYPE = field(name("document_file", "mime_type"), String.class);
  private static final Field<Long> FILE_SIZE = field(name("document_file", "file_size"), Long.class);
  private static final Field<String> STORAGE_PATH = field(name("document_file", "storage_path"), String.class);
  private static final Field<String> CHECKSUM = field(name("document_file", "checksum"), String.class);
  private static final Field<String> STATUS = field(name("document_file", "status"), String.class);
  private static final Field<Integer> PAGE_COUNT = field(name("document_file", "page_count"), Integer.class);
  private static final Field<Integer> CHUNK_COUNT = field(name("document_file", "chunk_count"), Integer.class);
  private static final Field<String> ERROR_DETAIL = field(name("document_file", "error_detail"), String.class);
  private static final Field<Long> UPLOADED_BY = field(name("document_file", "uploaded_by"), Long.class);
  private static final Field<LocalDateTime> CREATED_AT = field(name("document_file", "created_at"), LocalDateTime.class);
  private static final Field<LocalDateTime> COMPLETED_AT = field(name("document_file", "completed_at"), LocalDateTime.class);

  /** PENDING 상태로 신규 메타를 만들고 id 반환. */
  public Long create(
      Long datasetId, String originalName, String mimeType, long fileSize,
      String storagePath, String checksum, Long uploadedBy) {
    return dsl.insertInto(T)
        .set(DATASET_ID, datasetId)
        .set(ORIGINAL_NAME, originalName)
        .set(MIME_TYPE, mimeType)
        .set(FILE_SIZE, fileSize)
        .set(STORAGE_PATH, storagePath)
        .set(CHECKSUM, checksum)
        .set(STATUS, "PENDING")
        .set(UPLOADED_BY, uploadedBy)
        .returning(ID)
        .fetchOne()
        .get(ID);
  }

  public void updateStatus(Long id, String status) {
    dsl.update(T).set(STATUS, status).where(ID.eq(id)).execute();
  }

  /** 성공 완료: 상태 COMPLETED + 페이지/청크 수 + 완료시각. */
  public void markCompleted(Long id, Integer pageCount, int chunkCount) {
    dsl.update(T)
        .set(STATUS, "COMPLETED")
        .set(PAGE_COUNT, pageCount)
        .set(CHUNK_COUNT, chunkCount)
        .set(COMPLETED_AT, LocalDateTime.now())
        .where(ID.eq(id))
        .execute();
  }

  /** 실패 처리: 오류 메시지는 컬럼 제약(4000자)에 맞춰 잘라 저장. null이면 'unknown'으로 대체. */
  public void markFailed(Long id, String errorDetail) {
    dsl.update(T)
        .set(STATUS, "FAILED")
        .set(ERROR_DETAIL, errorDetail == null ? "unknown" : errorDetail.substring(0, Math.min(errorDetail.length(), 4000)))
        .where(ID.eq(id))
        .execute();
  }

  public String findStoragePath(Long id) {
    return dsl.select(STORAGE_PATH).from(T).where(ID.eq(id)).fetchOne(STORAGE_PATH);
  }

  /** 같은 데이터셋 내 동일 체크섬 문서가 이미 있는지(중복 업로드 감지). */
  public boolean existsByChecksum(Long datasetId, String checksum) {
    return dsl.fetchExists(
        dsl.selectOne().from(T).where(DATASET_ID.eq(datasetId)).and(CHECKSUM.eq(checksum)));
  }

  public List<DocumentFileResponse> findByDataset(Long datasetId) {
    return dsl.select().from(T).where(DATASET_ID.eq(datasetId))
        .orderBy(CREATED_AT.desc()).fetch(this::map);
  }

  public Optional<DocumentFileResponse> findById(Long id) {
    return dsl.select().from(T).where(ID.eq(id)).fetchOptional(this::map);
  }

  public void delete(Long id) {
    dsl.deleteFrom(T).where(ID.eq(id)).execute();
  }

  private DocumentFileResponse map(Record r) {
    // created_at/completed_at 컬럼은 TIMESTAMPTZ(OffsetDateTime)이므로 DTO의 LocalDateTime으로 변환한다.
    OffsetDateTime createdAt = r.get("created_at", OffsetDateTime.class);
    OffsetDateTime completedAt = r.get("completed_at", OffsetDateTime.class);
    return new DocumentFileResponse(
        r.get(ID), r.get(DATASET_ID), r.get(ORIGINAL_NAME), r.get(MIME_TYPE),
        r.get(FILE_SIZE), r.get(STATUS), r.get(PAGE_COUNT), r.get(CHUNK_COUNT),
        r.get(ERROR_DETAIL), r.get(UPLOADED_BY),
        createdAt == null ? null : createdAt.toLocalDateTime(),
        completedAt == null ? null : completedAt.toLocalDateTime());
  }
}
