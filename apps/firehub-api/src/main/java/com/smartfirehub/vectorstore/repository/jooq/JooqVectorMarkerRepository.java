package com.smartfirehub.vectorstore.repository.jooq;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.vectorstore.VectorMarkerRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link VectorMarkerRepository} 의 jOOQ(PostgreSQL) 구현(WD-721). 읽기는 RLS 로 현재 테넌트만 보고, 비우기는 RLS 와
 * 별개로 {@code tenant_id} 를 명시한다. 데이터셋 dense 표시를 비울 때는 {@code embedded_hash} 도 함께 비운다 — 표시를 비우는 다른
 * 경로({@code clearMarkers})와 같은 규칙이다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
@Transactional
public class JooqVectorMarkerRepository implements VectorMarkerRepository {

  private static final String DATASET_COLS =
      "SELECT dataset_id AS id, dataset_id, embedded_dim, embedded_model,"
          + " keyword_marker IS NOT NULL AS kw FROM dataset_embedding";
  private static final String CHUNK_COLS =
      "SELECT id, dataset_id, embedded_dim, embedded_model,"
          + " keyword_marker IS NOT NULL AS kw FROM document_chunk";

  private final DSLContext dsl;

  @Override
  @Transactional(readOnly = true)
  public int countDatasetDense(Collection<Long> datasetIds, Integer exceptDim) {
    return countDense("dataset_embedding", "dataset_id", datasetIds, exceptDim);
  }

  @Override
  @Transactional(readOnly = true)
  public int countChunkDense(Collection<Long> datasetIds, Integer exceptDim) {
    return countDense("document_chunk", "dataset_id", datasetIds, exceptDim);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> findDatasetsMarkedOtherThan(Collection<Long> datasetIds, int dim) {
    if (datasetIds.isEmpty()) return List.of();
    return dsl.fetch(
            "SELECT dataset_id FROM dataset_embedding WHERE dataset_id = ANY(?)"
                + " AND embedded_dim IS NOT NULL AND embedded_dim <> ? ORDER BY dataset_id",
            datasetIds.toArray(Long[]::new),
            dim)
        .getValues(0, Long.class);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> findChunkDatasetsMarkedOtherThan(Collection<Long> chunkIds, int dim) {
    if (chunkIds.isEmpty()) return List.of();
    return dsl.fetch(
            "SELECT DISTINCT dataset_id FROM document_chunk WHERE id = ANY(?)"
                + " AND embedded_dim IS NOT NULL AND embedded_dim <> ? ORDER BY dataset_id",
            chunkIds.toArray(Long[]::new),
            dim)
        .getValues(0, Long.class);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Marker> findDatasetMarkers(Collection<Long> datasetIds) {
    if (datasetIds.isEmpty()) return List.of();
    return dsl.fetch(
            DATASET_COLS + " WHERE dataset_id = ANY(?)", (Object) datasetIds.toArray(Long[]::new))
        .map(JooqVectorMarkerRepository::marker);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Marker> findChunkMarkers(Collection<Long> chunkIds) {
    if (chunkIds.isEmpty()) return List.of();
    return dsl.fetch(CHUNK_COLS + " WHERE id = ANY(?)", (Object) chunkIds.toArray(Long[]::new))
        .map(JooqVectorMarkerRepository::marker);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Marker> pageMarkedDatasets(long afterId, int limit) {
    return dsl.fetch(
            DATASET_COLS
                + " WHERE dataset_id > ? AND (embedded_dim IS NOT NULL OR keyword_marker IS NOT NULL)"
                + " ORDER BY dataset_id LIMIT ?",
            afterId,
            limit)
        .map(JooqVectorMarkerRepository::marker);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Marker> pageMarkedChunks(long afterId, int limit) {
    return dsl.fetch(
            CHUNK_COLS
                + " WHERE id > ? AND (embedded_dim IS NOT NULL OR keyword_marker IS NOT NULL)"
                + " ORDER BY id LIMIT ?",
            afterId,
            limit)
        .map(JooqVectorMarkerRepository::marker);
  }

  @Override
  @Transactional(readOnly = true)
  public String findDatasetSourceText(long datasetId) {
    return dsl.fetchOptional(
            "SELECT source_text FROM dataset_embedding WHERE dataset_id = ?", datasetId)
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  @Override
  @Transactional(readOnly = true)
  public String findChunkContent(long chunkId) {
    return dsl.fetchOptional("SELECT content FROM document_chunk WHERE id = ?", chunkId)
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  @Override
  public int clearDatasetDense(long datasetId, int dim, String model) {
    return dsl.execute(
        "UPDATE dataset_embedding SET embedded_dim = NULL, embedded_model = NULL, embedded_hash = NULL"
            + " WHERE tenant_id = ? AND dataset_id = ? AND embedded_dim = ? AND embedded_model = ?",
        TenantContext.require("데이터셋 표시 비우기"),
        datasetId,
        dim,
        model);
  }

  @Override
  public int clearDatasetKeyword(long datasetId) {
    return dsl.execute(
        "UPDATE dataset_embedding SET keyword_marker = NULL WHERE tenant_id = ? AND dataset_id = ?",
        TenantContext.require("데이터셋 키워드 표시 비우기"),
        datasetId);
  }

  @Override
  public int clearChunkDense(long chunkId, int dim, String model) {
    return dsl.execute(
        "UPDATE document_chunk SET embedded_dim = NULL, embedded_model = NULL"
            + " WHERE tenant_id = ? AND id = ? AND embedded_dim = ? AND embedded_model = ?",
        TenantContext.require("청크 표시 비우기"),
        chunkId,
        dim,
        model);
  }

  @Override
  public int clearChunkKeyword(long chunkId) {
    return dsl.execute(
        "UPDATE document_chunk SET keyword_marker = NULL WHERE tenant_id = ? AND id = ?",
        TenantContext.require("청크 키워드 표시 비우기"),
        chunkId);
  }

  /** dense 표시가 있는 행 수 — 데이터셋 id·제외 차원으로 좁힌다. */
  private int countDense(
      String table, String datasetCol, Collection<Long> datasetIds, Integer exceptDim) {
    if (datasetIds != null && datasetIds.isEmpty()) return 0;
    StringBuilder sql =
        new StringBuilder("SELECT count(*) FROM ")
            .append(table)
            .append(" WHERE embedded_dim IS NOT NULL");
    List<Object> params = new ArrayList<>();
    if (datasetIds != null) {
      sql.append(" AND ").append(datasetCol).append(" = ANY(?)");
      params.add(datasetIds.toArray(Long[]::new));
    }
    if (exceptDim != null) {
      sql.append(" AND embedded_dim <> ?");
      params.add(exceptDim);
    }
    return dsl.fetchOne(sql.toString(), params.toArray()).get(0, Integer.class);
  }

  private static Marker marker(Record r) {
    return new Marker(
        r.get("id", Long.class),
        r.get("dataset_id", Long.class),
        r.get("embedded_dim", Integer.class),
        r.get("embedded_model", String.class),
        Boolean.TRUE.equals(r.get("kw", Boolean.class)));
  }
}
