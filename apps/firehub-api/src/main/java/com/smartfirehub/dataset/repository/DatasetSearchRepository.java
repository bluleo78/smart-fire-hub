package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.util.List;

/**
 * 데이터셋 카탈로그 검색 리포지토리 (dataset_embedding 대상).
 *
 * <p>벡터 바인딩·트라이그램 임계값 설정 방식은 {@code DocumentChunkRepository} 패턴을 그대로 복제한다. embedding 은
 * '[..]'::vector 텍스트 리터럴 캐스팅으로 바인딩하고, 트라이그램은 {@code source_text %> ?} 교환 연산자로 GIN 인덱스를 태우며 같은
 * 트랜잭션에서 SET LOCAL 로 임계값을 0.1 로 낮춘다.
 *
 * <p>코사인 검색은 현재 공간의 차원 테이블만 보고(모델 필터), 트라이그램은 부모 source_text 그대로라 벡터가 없는 행도 키워드로 보인다(#713).
 *
 * <p>구현은 {@code JooqDatasetSearchRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DatasetSearchRepository {

  /**
   * 코사인 top-K 데이터셋. 현재 공간의 차원 테이블 한 개 + 현재 모델 벡터만(#392). storageType 이 null 이면 필터 없음. iterative
   * scan + 바깥 재정렬({@code HnswSearch}).
   *
   * @param visibility 조회자 가시성(서비스가 {@code DatasetAccessGuard#visibility} 로 캡처). 구현이 dataset 별칭
   *     {@code d} 로 렌더한다
   */
  List<DatasetSearchHit> searchByCosine(
      EmbeddingSpace space,
      float[] queryEmbedding,
      String storageType,
      int topK,
      DatasetVisibility visibility);

  /**
   * pg_trgm word_similarity 기준 키워드 top-K 데이터셋 조회. word_similarity(query, source_text) 는 짧은 질의를 긴
   * 본문의 일부와 매칭해 0~1 점수를 준다.
   *
   * <p>{@code source_text %> ?} 교환 연산자로 source_text 의 GIN trigram 인덱스
   * (idx_dataset_embedding_source_trgm)를 타게 한다(컬럼이 좌변이어야 인덱스 사용). {@code %>} 는
   * pg_trgm.word_similarity_threshold GUC(기본 0.6)를 임계값으로 쓰므로, DocumentChunkRepository 와 동일하게 같은
   * 트랜잭션에서 SET LOCAL 로 0.1 로 낮춘다(LOCAL 은 tx 종료 시 자동 복원). source_text 만 보므로 벡터가 없는 행도 검색되어 가시성이
   * 보장된다. storageType 이 null 이면 저장유형 필터를 적용하지 않는다.
   */
  List<DatasetSearchHit> searchByTrigram(
      String query, String storageType, int topK, DatasetVisibility visibility);
}
