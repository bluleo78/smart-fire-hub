package com.smartfirehub.vectorstore.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.vectorstore.VectorMarkerRepository.Marker;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 표시 열 매퍼(WD-721). SQL 은 {@code mybatis/vectorstore/VectorMarkerMapper.xml}. id 목록 인자는 {@code
 * InChunks.of} 로 500 개씩 나눈 목록이다(Tibero IN 상한).
 */
@PlatformMapper
public interface VectorMarkerMapper {

  /** dense 표시 수. {@code table} 은 코드 상수만 넘긴다. */
  int countDense(
      @Param("table") String table,
      @Param("chunks") List<List<Long>> datasetIds,
      @Param("exceptDim") Integer exceptDim);

  List<Long> findDatasetsMarkedOtherThan(
      @Param("chunks") List<List<Long>> datasetIds, @Param("dim") int dim);

  List<Long> findChunkDatasetsMarkedOtherThan(
      @Param("chunks") List<List<Long>> chunkIds, @Param("dim") int dim);

  List<Marker> findDatasetMarkers(@Param("chunks") List<List<Long>> datasetIds);

  List<Marker> findChunkMarkers(@Param("chunks") List<List<Long>> chunkIds);

  List<Marker> pageMarkedDatasets(
      @Param("afterId") long afterId, @Param("limit") int limit, @Param("offset") int offset);

  List<Marker> pageMarkedChunks(
      @Param("afterId") long afterId, @Param("limit") int limit, @Param("offset") int offset);

  /** 데이터셋 원문(없으면 null). */
  String findDatasetSourceText(@Param("id") long datasetId);

  /** 청크 본문(없으면 null). */
  String findChunkContent(@Param("id") long chunkId);

  int clearDatasetDense(
      @Param("tenantId") long tenantId,
      @Param("id") long id,
      @Param("dim") int dim,
      @Param("model") String model);

  int clearDatasetKeyword(@Param("tenantId") long tenantId, @Param("id") long id);

  int clearChunkDense(
      @Param("tenantId") long tenantId,
      @Param("id") long id,
      @Param("dim") int dim,
      @Param("model") String model);

  int clearChunkKeyword(@Param("tenantId") long tenantId, @Param("id") long id);
}
