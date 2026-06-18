package com.smartfirehub.dataset.search;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * 데이터셋 카탈로그 검색 리포지토리 (dataset_embedding 대상).
 *
 * <p>벡터 바인딩·트라이그램 임계값 설정 방식은 {@code DocumentChunkRepository} 패턴을 그대로 복제한다.
 * embedding 은 '[..]'::vector 텍스트 리터럴 캐스팅으로 바인딩하고, 트라이그램은 {@code source_text %> ?}
 * 교환 연산자로 GIN 인덱스를 태우며 같은 트랜잭션에서 SET LOCAL 로 임계값을 0.1 로 낮춘다.
 *
 * <p>A8 설계상 source_text 는 동기 저장되지만 embedding 은 비동기로 채워지므로 embedding 이 NULL 인 행이
 * 정상적으로 존재한다. 따라서 코사인 검색은 {@code de.embedding IS NOT NULL} 로 NULL 행을 제외하고,
 * 트라이그램은 source_text 만 보므로 NULL embedding 행도 검색되어 가시성을 보장한다.
 */
@Repository
@RequiredArgsConstructor
public class DatasetSearchRepository {

  private final DSLContext dsl;

  /**
   * 쿼리 벡터와의 코사인 거리 기준 top-K 데이터셋 조회. HNSW 인덱스(vector_cosine_ops)를 사용한다.
   * score = 1 - (embedding <=> query) (코사인 유사도, 1에 가까울수록 유사).
   * embedding 이 NULL 인 행(임베딩 미생성)은 제외한다. storageType 이 null 이면 저장유형 필터를 적용하지 않는다.
   */
  public List<DatasetSearchHit> searchByCosine(
      float[] queryEmbedding, String storageType, int topK) {
    String vectorLiteral = VectorLiterals.toVectorLiteral(queryEmbedding);
    StringBuilder sql =
        new StringBuilder(
            "SELECT d.id, d.name, d.description, d.storage_type, d.origin_type, d.table_name,"
                + " c.name AS category_name, 1 - (de.embedding <=> ?::vector) AS score"
                + " FROM dataset_embedding de"
                + " JOIN dataset d ON d.id = de.dataset_id"
                + " LEFT JOIN dataset_category c ON c.id = d.category_id"
                + " WHERE de.embedding IS NOT NULL");
    List<Object> params = new java.util.ArrayList<>();
    params.add(vectorLiteral); // SELECT score 의 코사인 거리 인자
    if (storageType != null) {
      sql.append(" AND d.storage_type = ?");
      params.add(storageType);
    }
    sql.append(" ORDER BY de.embedding <=> ?::vector LIMIT ?");
    params.add(vectorLiteral); // ORDER BY 거리 정렬 인자
    params.add(topK);

    return dsl.fetch(sql.toString(), params.toArray()).map(DatasetSearchRepository::toHit);
  }

  /**
   * pg_trgm word_similarity 기준 키워드 top-K 데이터셋 조회.
   * word_similarity(query, source_text) 는 짧은 질의를 긴 본문의 일부와 매칭해 0~1 점수를 준다.
   *
   * <p>{@code source_text %> ?} 교환 연산자로 source_text 의 GIN trigram 인덱스
   * (idx_dataset_embedding_source_trgm)를 타게 한다(컬럼이 좌변이어야 인덱스 사용).
   * {@code %>} 는 pg_trgm.word_similarity_threshold GUC(기본 0.6)를 임계값으로 쓰므로,
   * DocumentChunkRepository 와 동일하게 같은 트랜잭션에서 SET LOCAL 로 0.1 로 낮춘다(LOCAL 은 tx 종료 시 자동 복원).
   * source_text 만 보므로 embedding 이 NULL 인 행도 검색되어 가시성이 보장된다.
   * storageType 이 null 이면 저장유형 필터를 적용하지 않는다.
   */
  public List<DatasetSearchHit> searchByTrigram(String query, String storageType, int topK) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT d.id, d.name, d.description, d.storage_type, d.origin_type, d.table_name,"
                + " c.name AS category_name, word_similarity(?, de.source_text) AS score"
                + " FROM dataset_embedding de"
                + " JOIN dataset d ON d.id = de.dataset_id"
                + " LEFT JOIN dataset_category c ON c.id = d.category_id"
                + " WHERE de.source_text %> ?");
    List<Object> params = new java.util.ArrayList<>();
    params.add(query); // SELECT 의 word_similarity 첫 인자
    params.add(query); // %> 우변(질의)
    if (storageType != null) {
      sql.append(" AND d.storage_type = ?");
      params.add(storageType);
    }
    sql.append(" ORDER BY score DESC LIMIT ?");
    params.add(topK);

    String finalSql = sql.toString();
    Object[] finalParams = params.toArray();
    // SET LOCAL 과 조회를 같은 커넥션/트랜잭션에서 실행해야 임계값이 적용된다.
    return dsl.transactionResult(cfg -> {
      DSLContext tx = DSL.using(cfg);
      tx.execute("SET LOCAL pg_trgm.word_similarity_threshold = 0.1");
      return tx.fetch(finalSql, finalParams).map(DatasetSearchRepository::toHit);
    });
  }

  /** row → DatasetSearchHit 매핑. score 가 null 이면 0.0(primitive double 이므로 명시적 가드). */
  private static DatasetSearchHit toHit(org.jooq.Record r) {
    Double score = r.get("score", Double.class);
    return new DatasetSearchHit(
        r.get("id", Long.class),
        r.get("name", String.class),
        r.get("description", String.class),
        r.get("storage_type", String.class),
        r.get("origin_type", String.class),
        r.get("table_name", String.class),
        r.get("category_name", String.class),
        score == null ? 0.0 : score);
  }
}
