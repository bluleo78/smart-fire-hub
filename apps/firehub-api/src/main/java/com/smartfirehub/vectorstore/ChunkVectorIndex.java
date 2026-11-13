package com.smartfirehub.vectorstore;

import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.util.List;

/**
 * 문서 청크의 벡터·키워드 색인 포트(ADR 0002 결정 2). {@link DatasetVectorIndex} 와 같은 일을 문서 청크에 대해 한다.
 *
 * <p>구현체는 pgvector({@code PgChunkVectorIndex}: 차원 테이블 {@code document_chunk_vec_N} 과 청크 본문
 * pg_trgm), 꺼짐({@code OffChunkVectorIndex}, Tibero+platform), Qdrant(WD-720)다.
 *
 * <p>청크 본문 저장, 전체 수, 표시 열, 백로그 판정은 플랫폼 리포지토리({@code DocumentChunkRepository})에 남는다. 벡터를 쓰거나 지우는
 * 리포지토리 메서드는 이 포트를 부른 뒤 같은 트랜잭션에서 표시 열을 맞춘다. pgvector 구현은 그 트랜잭션에 합류한다.
 */
public interface ChunkVectorIndex {

  /**
   * 의미(코사인) top-K 청크. 현재 공간의 벡터만 보고(#392) 완료된 문서만 낸다.
   *
   * @param datasetIds 이 데이터셋들로 좁힌다. null 이거나 비어 있으면 좁히지 않는다
   */
  List<DocumentSearchHit> semantic(
      EmbeddingSpace space,
      float[] queryEmbedding,
      List<Long> datasetIds,
      int topK,
      DatasetVisibility visibility);

  /**
   * 키워드 top-K 청크. 완료된 문서만 낸다. 임베딩이 필요 없어 임베딩 장애에도 동작한다.
   *
   * @param datasetIds 이 데이터셋들로 좁힌다. null 이거나 비어 있으면 좁히지 않는다
   */
  List<DocumentSearchHit> keyword(
      String query, List<Long> datasetIds, int topK, DatasetVisibility visibility);

  /**
   * 방금 저장한 새 청크들의 dense 벡터를 {@code space} 로 넣는다. 새 청크라 다른 차원 행이 있을 수 없어 지우지 않는다.
   *
   * @param chunkIds 저장된 청크 id. {@code embeddings} 와 순서가 같다
   */
  void insertDense(
      EmbeddingSpace space, long datasetId, List<Long> chunkIds, List<float[]> embeddings);

  /**
   * 기존 청크들의 dense 벡터를 {@code space} 로 옮긴다. 다른 차원의 같은 청크 벡터를 지우고 현재 차원에 덮어쓴다(upsert). 부모가 사라진
   * id(재임베딩 중 문서 삭제 경합)는 조용히 건너뛴다.
   *
   * @throws IllegalArgumentException id 수와 벡터 수가 다를 때
   */
  void upsertDense(EmbeddingSpace space, List<Long> chunkIds, List<float[]> embeddings);

  /**
   * 이 테넌트 데이터셋들의 청크 dense 벡터를 모든 차원에서 지운다. 청크 본문은 남아 키워드 검색은 그대로다. {@code tenantId} 를 조건에 명시한다.
   *
   * @return 지운 벡터 수
   */
  int deleteDense(long tenantId, List<Long> datasetIds);

  /**
   * {@code keep} 이 아닌 차원에 남은 이 테넌트의 청크 벡터를 지운다(재임베딩 완료 뒤 정리).
   *
   * @return 지운 벡터 수
   */
  int deleteOtherDimensions(long tenantId, EmbeddingDimension keep);

  /**
   * 이 테넌트 문서 파일의 청크 색인을 전부 지운다(dense 와 키워드 모두). 문서를 삭제하거나 다시 적재하기 전에 부른다(ADR 0002 결정 6).
   *
   * <p>pgvector 구현과 꺼짐 구현은 아무것도 하지 않는다. 청크 벡터는 청크 행의 FK CASCADE 로, 키워드 색인은 청크 본문 열 자체라 청크와 함께 사라진다.
   * 삭제 경로는 기능 가드 밖이라 꺼짐 구현도 예외를 던지지 않는다.
   */
  void deleteAllOfDocument(long tenantId, long documentFileId);

  /**
   * 이 테넌트 데이터셋들의 청크 색인을 전부 지운다(dense 와 키워드 모두). 데이터셋을 삭제할 때 부른다. pgvector·꺼짐 구현은 아무것도 하지 않는다({@link
   * #deleteAllOfDocument} 와 같은 이유).
   */
  void deleteAllOfDatasets(long tenantId, List<Long> datasetIds);
}
