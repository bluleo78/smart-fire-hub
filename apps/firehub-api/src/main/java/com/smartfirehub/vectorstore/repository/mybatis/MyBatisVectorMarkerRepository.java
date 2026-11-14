package com.smartfirehub.vectorstore.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.vectorstore.VectorMarkerRepository;
import java.util.Collection;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link VectorMarkerRepository} 의 MyBatis 구현(WD-721) — {@code JooqVectorMarkerRepository} 와 같은 의미.
 * Tibero+qdrant 조합이 쓴다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisVectorMarkerRepository implements VectorMarkerRepository {

  private final VectorMarkerMapper mapper;

  /** 매퍼를 주입받는다(플랫폼 MyBatis 빈). */
  public MyBatisVectorMarkerRepository(VectorMarkerMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  @Transactional(readOnly = true)
  public int countDatasetDense(Collection<Long> datasetIds, Integer exceptDim) {
    if (datasetIds != null && datasetIds.isEmpty()) return 0;
    return mapper.countDense("dataset_embedding", chunksOrNull(datasetIds), exceptDim);
  }

  @Override
  @Transactional(readOnly = true)
  public int countChunkDense(Collection<Long> datasetIds, Integer exceptDim) {
    if (datasetIds != null && datasetIds.isEmpty()) return 0;
    return mapper.countDense("document_chunk", chunksOrNull(datasetIds), exceptDim);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> findDatasetsMarkedOtherThan(Collection<Long> datasetIds, int dim) {
    if (datasetIds.isEmpty()) return List.of();
    return mapper.findDatasetsMarkedOtherThan(InChunks.of(datasetIds), dim);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> findChunkDatasetsMarkedOtherThan(Collection<Long> chunkIds, int dim) {
    if (chunkIds.isEmpty()) return List.of();
    return mapper.findChunkDatasetsMarkedOtherThan(InChunks.of(chunkIds), dim);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Marker> findDatasetMarkers(Collection<Long> datasetIds) {
    if (datasetIds.isEmpty()) return List.of();
    return mapper.findDatasetMarkers(InChunks.of(datasetIds));
  }

  @Override
  @Transactional(readOnly = true)
  public List<Marker> findChunkMarkers(Collection<Long> chunkIds) {
    if (chunkIds.isEmpty()) return List.of();
    return mapper.findChunkMarkers(InChunks.of(chunkIds));
  }

  @Override
  @Transactional(readOnly = true)
  public List<Marker> pageMarkedDatasets(long afterId, int limit) {
    return mapper.pageMarkedDatasets(afterId, limit, 0);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Marker> pageMarkedChunks(long afterId, int limit) {
    return mapper.pageMarkedChunks(afterId, limit, 0);
  }

  @Override
  @Transactional(readOnly = true)
  public String findDatasetSourceText(long datasetId) {
    return mapper.findDatasetSourceText(datasetId);
  }

  @Override
  @Transactional(readOnly = true)
  public String findChunkContent(long chunkId) {
    return mapper.findChunkContent(chunkId);
  }

  @Override
  public int clearDatasetDense(long datasetId, int dim, String model) {
    return mapper.clearDatasetDense(TenantContext.require("데이터셋 표시 비우기"), datasetId, dim, model);
  }

  @Override
  public int clearDatasetKeyword(long datasetId) {
    return mapper.clearDatasetKeyword(TenantContext.require("데이터셋 키워드 표시 비우기"), datasetId);
  }

  @Override
  public int clearChunkDense(long chunkId, int dim, String model) {
    return mapper.clearChunkDense(TenantContext.require("청크 표시 비우기"), chunkId, dim, model);
  }

  @Override
  public int clearChunkKeyword(long chunkId) {
    return mapper.clearChunkKeyword(TenantContext.require("청크 키워드 표시 비우기"), chunkId);
  }

  private static List<List<Long>> chunksOrNull(Collection<Long> ids) {
    return ids == null ? null : InChunks.of(ids);
  }
}
