package com.smartfirehub.document.repository.mybatis;

import com.smartfirehub.document.dto.DocumentFileResponse;
import com.smartfirehub.document.repository.DocumentFileRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * DocumentFileRepository 의 MyBatis 구현(WD-712) — {@code JooqDocumentFileRepository} 와 같은 의미.
 *
 * <p>document_file 메타 CRUD + 상태 전이.
 */
@PlatformMyBatisRepository
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Transactional
public class MyBatisDocumentFileRepository implements DocumentFileRepository {

  /**
   * error_detail 에 저장하는 최대 글자 수(jOOQ 판과 같음). Tibero 열은 VARCHAR2(65532) 바이트라 한글 4000자(12000바이트)도
   * 들어간다.
   */
  private static final int ERROR_DETAIL_MAX = 4000;

  private final DocumentFileMapper mapper;

  /** 매퍼를 주입받는다(플랫폼 MyBatis 빈 — 매퍼 SQL 은 mybatis/ 아래 XML). */
  public MyBatisDocumentFileRepository(DocumentFileMapper mapper) {
    this.mapper = mapper;
  }

  /** PENDING 상태로 신규 메타를 만들고 id 반환(RETURNING 대신 생성 키). */
  @Override
  public Long create(
      Long datasetId,
      String originalName,
      String mimeType,
      long fileSize,
      String storagePath,
      String checksum,
      Long uploadedBy) {
    DocumentFileInsert row =
        new DocumentFileInsert(
            datasetId, originalName, mimeType, fileSize, storagePath, checksum, uploadedBy);
    mapper.insert(row);
    return row.getId();
  }

  /** 문서 처리 상태(PENDING·PROCESSING 등)만 바꾼다. */
  @Override
  public void updateStatus(Long id, String status) {
    mapper.updateStatus(id, status);
  }

  /** 성공 완료: 상태 COMPLETED + 페이지/청크 수 + 완료시각(DB 시계). */
  @Override
  public void markCompleted(Long id, Integer pageCount, int chunkCount) {
    mapper.markCompleted(id, pageCount, chunkCount);
  }

  /** 실패 처리: 오류 메시지는 컬럼 제약(4000자)에 맞춰 잘라 저장. null이면 'unknown'으로 대체. */
  @Override
  public void markFailed(Long id, String errorDetail) {
    // 서로게이트 짝을 가르지 않는 공용 자르기(보충 문자가 경계에 걸려도 깨진 글자를 남기지 않는다).
    String detail =
        errorDetail == null ? "unknown" : TiberoTextLimits.truncate(errorDetail, ERROR_DETAIL_MAX);
    mapper.markFailed(id, detail);
  }

  /** 저장소 경로를 돌려준다. 행이 없으면(또는 RLS 로 보이지 않으면) null. */
  @Override
  public String findStoragePath(Long id) {
    return mapper.selectStoragePath(id);
  }

  /** 같은 데이터셋 내 동일 체크섬 문서가 이미 있는지(중복 업로드 감지). */
  @Override
  public boolean existsByChecksum(Long datasetId, String checksum) {
    return mapper.countByChecksum(datasetId, checksum) > 0;
  }

  /** 데이터셋에 속한 문서 메타 목록 — 최신순(생성 시각 내림차순, 같으면 id 내림차순). */
  @Override
  public List<DocumentFileResponse> findByDataset(Long datasetId) {
    return mapper.selectByDataset(datasetId).stream()
        .map(MyBatisDocumentFileRepository::map)
        .toList();
  }

  /** id 로 문서 메타 한 건. 없으면 빈 Optional. */
  @Override
  public Optional<DocumentFileResponse> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(MyBatisDocumentFileRepository::map);
  }

  /** id 로 문서 메타 한 행을 지운다. 없는 id 면 아무 일도 하지 않는다. */
  @Override
  public void delete(Long id) {
    mapper.deleteById(id);
  }

  /**
   * 행 → 응답 DTO. jOOQ 판처럼 JVM 기본 시간대의 벽시계 LocalDateTime 으로 바꾼다(jOOQ 가 timestamptz 를 JVM 오프셋
   * OffsetDateTime 으로 읽어 toLocalDateTime 한 결과와 같다).
   */
  private static DocumentFileResponse map(DocumentFileRow r) {
    return new DocumentFileResponse(
        r.id(),
        r.datasetId(),
        r.originalName(),
        r.mimeType(),
        r.fileSize(),
        r.status(),
        r.pageCount(),
        r.chunkCount(),
        r.errorDetail(),
        r.uploadedBy(),
        toLocal(r.createdAt()),
        toLocal(r.completedAt()));
  }

  private static LocalDateTime toLocal(Instant t) {
    return t == null ? null : LocalDateTime.ofInstant(t, ZoneId.systemDefault());
  }
}
