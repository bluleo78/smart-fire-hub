package com.smartfirehub.vectorstore;

import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.util.List;

/**
 * 데이터셋 카탈로그의 벡터·키워드 색인 포트(ADR 0002 결정 2). 서비스와 플랫폼 리포지토리는 이 포트만 보고 구현체를 모른다.
 *
 * <p>구현체는 세 가지다.
 *
 * <ul>
 *   <li>pgvector({@code PgDatasetVectorIndex}): 차원 테이블 {@code dataset_embedding_vec_N} 과 부모 {@code
 *       source_text} 의 pg_trgm
 *   <li>꺼짐({@code OffDatasetVectorIndex}): Tibero+platform. 호출되면 예외를 던진다
 *   <li>Qdrant: WD-720
 * </ul>
 *
 * <p>무엇이 여기에 오고 무엇이 남는가. 벡터·키워드 색인 연산만 이 포트로 왔다. 기반 테이블 연산(원문 저장, 전체 수, 표시 열 {@code
 * embedded_dim}·{@code embedded_model}, 백로그 판정)은 플랫폼 리포지토리({@code DatasetEmbeddingRepository})에
 * 남는다. 벡터를 쓰거나 지우는 리포지토리 메서드는 이 포트를 부른 뒤 같은 트랜잭션에서 표시 열을 맞춘다. pgvector 구현은 같은 DataSource 라 그 트랜잭션에
 * 합류하므로, 벡터 행과 표시 열이 한 트랜잭션으로 묶이는 지금 동작이 그대로다.
 *
 * <p>융합은 서비스에 남긴다. 의미 목록과 키워드 목록을 따로 돌려주고, 서비스가 {@code RankFusion} 으로 합친다.
 */
public interface DatasetVectorIndex {

  /**
   * 의미(코사인) top-K 데이터셋. 현재 공간의 벡터만 본다(차원·모델 일치, #392). 결과는 표시 필드를 채운 히트다.
   *
   * @param space 질의 벡터의 임베딩 공간
   * @param queryEmbedding 질의 벡터
   * @param storageType 저장 유형 필터. null 이면 거르지 않는다
   * @param topK 최대 건수
   * @param visibility 조회자 가시성(서비스가 {@code DatasetAccessGuard#visibility} 로 캡처한 값)
   */
  List<DatasetSearchHit> semantic(
      EmbeddingSpace space,
      float[] queryEmbedding,
      String storageType,
      int topK,
      DatasetVisibility visibility);

  /**
   * 키워드 top-K 데이터셋. 원문만 보므로 벡터가 없는 행도 검색된다(#713).
   *
   * @param query 질의 문자열
   * @param storageType 저장 유형 필터. null 이면 거르지 않는다
   * @param topK 최대 건수
   * @param visibility 조회자 가시성
   */
  List<DatasetSearchHit> keyword(
      String query, String storageType, int topK, DatasetVisibility visibility);

  /**
   * dense 벡터 여러 건을 {@code space} 로 쓴다. 같은 데이터셋의 다른 차원 벡터는 지우고, 현재 차원에 덮어쓴다(upsert). 부모 카탈로그 행이 없는
   * id(삭제 경합)는 조용히 건너뛴다. 표시 열은 호출한 리포지토리가 맞춘다.
   *
   * @throws IllegalArgumentException id 수와 벡터 수가 다를 때
   */
  void upsertDense(EmbeddingSpace space, List<Long> datasetIds, List<float[]> embeddings);

  /**
   * 이 테넌트 데이터셋들의 dense 벡터를 모든 차원에서 지운다. 카탈로그 행과 원문은 남아 키워드 검색은 그대로다. RLS 와 별개로 {@code tenantId} 를
   * 조건에 명시한다. 그래야 소유자 커넥션에서 불려도 남의 테넌트 행을 지우지 않는다.
   *
   * @return 지운 벡터 수
   */
  int deleteDense(long tenantId, List<Long> datasetIds);

  /**
   * {@code keep} 이 아닌 차원에 남은 이 테넌트의 카탈로그 벡터를 지운다(재임베딩 완료 뒤 정리).
   *
   * @return 지운 벡터 수
   */
  int deleteOtherDimensions(long tenantId, EmbeddingDimension keep);

  /**
   * 이 테넌트 데이터셋들의 색인을 전부 지운다(dense 와 키워드 모두). 데이터셋을 삭제할 때 부른다(ADR 0002 결정 6 — 명시 삭제).
   *
   * <p>pgvector 구현은 아무것도 하지 않는다. 차원 테이블은 부모 카탈로그 행의 FK CASCADE 로, 키워드 색인은 부모 행의 원문 열 자체라 데이터셋 삭제와
   * 함께 사라지기 때문이다. 별도 저장소(Qdrant)는 CASCADE 가 없어 이 호출이 정리 경로가 된다. 꺼짐 구현도 아무것도 하지 않는다 — 삭제 경로는 기능 가드
   * 밖이라 예외를 던지면 안 된다.
   */
  void deleteAll(long tenantId, List<Long> datasetIds);
}
