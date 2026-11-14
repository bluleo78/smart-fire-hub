package com.smartfirehub.document.repository.mybatis;

import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository.ChunkContent;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * document_chunk MyBatis 매퍼(WD-712). SQL 은 {@code mybatis/document/DocumentChunkMapper.xml}.
 *
 * <p>기반 테이블(document_chunk)의 살아 있는 경로만 다룬다 — 벡터 테이블(document_chunk_vec_N)·트라이그램 문장은 두지 않는다(꺼진 기능
 * 경로, 스펙 D1).
 */
@PlatformMapper
public interface DocumentChunkMapper {

  /** 그 문서의 청크를 모두 지운다(지운 행 수). */
  int deleteByDocumentFileId(@Param("documentFileId") Long documentFileId);

  /** 청크 본문 한 건을 넣는다(id 는 IDENTITY — 넣은 뒤 {@code row.id} 에 채워진다, tenant_id 는 컨텍스트 DEFAULT). */
  int insertChunk(DocumentChunkRows.ChunkInsert row);

  /**
   * 목록의 청크 중 지금 있는 것의 (id, 데이터셋, 문서). 벡터를 쓰기 전에 사라진 청크를 거르고 색인 payload 를 채운다(WD-720).
   *
   * @param chunks {@code InChunks.of} 로 나눈 id 목록
   */
  List<com.smartfirehub.vectorstore.ChunkRef> selectRefs(@Param("chunks") List<List<Long>> chunks);

  /** 그 데이터셋 청크의 (id, content) 를 id 오름차순으로. */
  List<ChunkContent> selectChunkContentsByDataset(@Param("datasetId") long datasetId);

  /**
   * 그 데이터셋에 속한 청크 중 id 가 목록에 있는 것의 개수.
   *
   * @param chunks {@code InChunks.of} 로 중복을 걷고 500 개씩 나눈 id 목록
   */
  long countChunksInDataset(
      @Param("datasetId") long datasetId, @Param("chunks") List<List<Long>> chunks);

  /**
   * 등급이 임베딩 공급자를 허용하는 데이터셋의 청크 수.
   *
   * @param aiPolicy {@code MyBatisDatasetVisibility.aiPolicyValues} 가 호스팅 위치로 정한 허용 값
   */
  long countAllowed(@Param("aiPolicy") MyBatisDatasetVisibility.AiPolicyValues aiPolicy);

  /** 청크 표시 열을 공간(차원·모델)으로 채운다. */
  int markEmbedded(
      @Param("dim") int dim,
      @Param("model") String model,
      @Param("chunks") List<List<Long>> chunks);

  /** 이 테넌트 데이터셋들의 청크 표시 열을 비운다. */
  int clearMarkersOfDatasets(
      @Param("tenantId") long tenantId, @Param("chunks") List<List<Long>> chunks);

  /** 이 테넌트에서 {@code keepDim} 이 아닌 차원을 가리키는 청크 표시 열을 비운다. */
  int clearOtherDimensionMarkers(@Param("tenantId") long tenantId, @Param("keepDim") int keepDim);

  /** 표시 열이 이 공간을 가리키는 청크 수. */
  long countEmbedded(@Param("dim") int dim, @Param("model") String model);

  /** 판정식(등급 허용 포함)을 만족하는 청크 수. */
  long countMissing(
      @Param("dim") int dim,
      @Param("model") String model,
      @Param("aiPolicy") MyBatisDatasetVisibility.AiPolicyValues aiPolicy);

  /** 판정식(등급 허용 포함)을 만족하는 청크를 id 순으로 {@code limit} 건(키셋). offset 은 0 이다. */
  List<DocumentChunkRepository.ChunkContent> findMissing(
      @Param("dim") int dim,
      @Param("model") String model,
      @Param("afterChunkId") long afterChunkId,
      @Param("limit") int limit,
      @Param("offset") int offset,
      @Param("aiPolicy") MyBatisDatasetVisibility.AiPolicyValues aiPolicy);

  /** 청크 키워드 표시를 채운다 — 지금 본문 해시가 {@code hash} 이거나 없을 때만. */
  int markKeywordIndexed(
      @Param("marker") String marker, @Param("hash") String hash, @Param("chunkId") long chunkId);

  /** 키워드 표시가 비었거나 어긋난 청크(키셋, offset 은 0). */
  List<DocumentChunkRepository.ChunkSource> findKeywordMissing(
      @Param("prefix") String prefix,
      @Param("afterChunkId") long afterChunkId,
      @Param("limit") int limit,
      @Param("offset") int offset);
}
