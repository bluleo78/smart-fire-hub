package com.smartfirehub.document.repository;

import com.smartfirehub.document.dto.DocumentFileResponse;
import java.util.List;
import java.util.Optional;

/**
 * document_file 메타 CRUD + 상태 전이.
 *
 * <p>구현은 {@code JooqDocumentFileRepository}(jOOQ/PostgreSQL) 이다 — WD-485. 다루는 테이블: {@code
 * document_file}.
 */
public interface DocumentFileRepository {

  /** PENDING 상태로 신규 메타를 만들고 id 반환. */
  Long create(
      Long datasetId,
      String originalName,
      String mimeType,
      long fileSize,
      String storagePath,
      String checksum,
      Long uploadedBy);

  void updateStatus(Long id, String status);

  /** 성공 완료: 상태 COMPLETED + 페이지/청크 수 + 완료시각. */
  void markCompleted(Long id, Integer pageCount, int chunkCount);

  /** 실패 처리: 오류 메시지는 컬럼 제약(4000자)에 맞춰 잘라 저장. null이면 'unknown'으로 대체. */
  void markFailed(Long id, String errorDetail);

  String findStoragePath(Long id);

  /** 같은 데이터셋 내 동일 체크섬 문서가 이미 있는지(중복 업로드 감지). */
  boolean existsByChecksum(Long datasetId, String checksum);

  List<DocumentFileResponse> findByDataset(Long datasetId);

  Optional<DocumentFileResponse> findById(Long id);

  void delete(Long id);
}
