package com.smartfirehub.vectorstore.repository.jooq;

import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository;
import com.smartfirehub.vectorstore.SearchCandidateRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SearchCandidateRepository} 의 jOOQ(PostgreSQL) 구현(WD-720). 조건은 pgvector 검색 SQL({@code
 * PgDatasetVectorIndex}·{@code PgChunkVectorIndex})과 같다 — 가시성은 같은 렌더({@code visibleSql(v, "d")})를
 * 쓴다.
 *
 * <p>읽기 전용 트랜잭션을 연다. RLS GUC 는 트랜잭션 시작 때 주입되므로 배경 경로에서 불려도 테넌트가 적용된다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JooqSearchCandidateRepository implements SearchCandidateRepository {

  private final DSLContext dsl;
  private final DatasetAccessRepository accessRepository;

  @Override
  public List<Long> findVisibleDatasetIds(
      DatasetVisibility visibility, String storageType, Collection<Long> restrictTo) {
    StringBuilder sql =
        new StringBuilder("SELECT d.id FROM dataset d WHERE ")
            .append(accessRepository.visibleSql(visibility, "d"));
    List<Object> params = new ArrayList<>();
    if (storageType != null) {
      sql.append(" AND d.storage_type = ?");
      params.add(storageType);
    }
    if (restrictTo != null && !restrictTo.isEmpty()) {
      sql.append(" AND d.id = ANY(?)");
      params.add(restrictTo.toArray(Long[]::new));
    }
    sql.append(" ORDER BY d.id");
    return dsl.fetch(sql.toString(), params.toArray()).getValues(0, Long.class);
  }

  @Override
  public List<DatasetSearchHit> findDatasetHits(
      Collection<Long> datasetIds,
      DatasetVisibility visibility,
      String storageType,
      EmbeddingSpace space) {
    if (datasetIds.isEmpty()) return List.of();
    StringBuilder sql =
        new StringBuilder(
                "SELECT d.id, d.name, d.description, d.storage_type, d.origin_type, d.table_name,"
                    + " c.name AS category_name FROM dataset_embedding de"
                    + " JOIN dataset d ON d.id = de.dataset_id"
                    + " LEFT JOIN dataset_category c ON c.id = d.category_id"
                    + " WHERE de.dataset_id = ANY(?) AND ")
            .append(accessRepository.visibleSql(visibility, "d"));
    List<Object> params = new ArrayList<>();
    params.add(datasetIds.toArray(Long[]::new));
    if (storageType != null) {
      sql.append(" AND d.storage_type = ?");
      params.add(storageType);
    }
    if (space != null) {
      // 공간 일치(#392) — 저장소 반영이 늦어 옛 공간 벡터가 남아 있어도 결과에 섞이지 않게 표시 열로 다시 본다.
      sql.append(" AND de.embedded_dim = ? AND de.embedded_model = ?");
      params.add(space.dimension().size());
      params.add(space.model());
    }
    return dsl.fetch(sql.toString(), params.toArray())
        .map(
            r ->
                new DatasetSearchHit(
                    r.get("id", Long.class),
                    r.get("name", String.class),
                    r.get("description", String.class),
                    r.get("storage_type", String.class),
                    r.get("origin_type", String.class),
                    r.get("table_name", String.class),
                    r.get("category_name", String.class),
                    0.0));
  }

  @Override
  public List<DocumentSearchHit> findChunkHits(
      Collection<Long> chunkIds,
      DatasetVisibility visibility,
      Collection<Long> restrictTo,
      EmbeddingSpace space) {
    if (chunkIds.isEmpty()) return List.of();
    StringBuilder sql =
        new StringBuilder(
                "SELECT c.id, c.document_file_id, c.dataset_id, df.original_name, c.chunk_index,"
                    + " c.content FROM document_chunk c"
                    + " JOIN document_file df ON df.id = c.document_file_id"
                    + " JOIN dataset d ON d.id = c.dataset_id"
                    + " WHERE c.id = ANY(?) AND df.status = 'COMPLETED' AND ")
            .append(accessRepository.visibleSql(visibility, "d"));
    List<Object> params = new ArrayList<>();
    params.add(chunkIds.toArray(Long[]::new));
    if (restrictTo != null && !restrictTo.isEmpty()) {
      sql.append(" AND c.dataset_id = ANY(?)");
      params.add(restrictTo.toArray(Long[]::new));
    }
    if (space != null) {
      sql.append(" AND c.embedded_dim = ? AND c.embedded_model = ?");
      params.add(space.dimension().size());
      params.add(space.model());
    }
    return dsl.fetch(sql.toString(), params.toArray())
        .map(
            r ->
                new DocumentSearchHit(
                    r.get("id", Long.class),
                    r.get("document_file_id", Long.class),
                    r.get("dataset_id", Long.class),
                    r.get("original_name", String.class),
                    r.get("chunk_index", Integer.class),
                    r.get("content", String.class),
                    0.0));
  }
}
