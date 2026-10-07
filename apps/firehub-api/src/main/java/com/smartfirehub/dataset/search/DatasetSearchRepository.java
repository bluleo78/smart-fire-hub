package com.smartfirehub.dataset.search;

import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.HnswSearch;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * 데이터셋 카탈로그 검색 리포지토리 (dataset_embedding 대상).
 *
 * <p>벡터 바인딩·트라이그램 임계값 설정 방식은 {@code DocumentChunkRepository} 패턴을 그대로 복제한다. embedding 은
 * '[..]'::vector 텍스트 리터럴 캐스팅으로 바인딩하고, 트라이그램은 {@code source_text %> ?} 교환 연산자로 GIN 인덱스를 태우며 같은
 * 트랜잭션에서 SET LOCAL 로 임계값을 0.1 로 낮춘다.
 *
 * <p>코사인 검색은 현재 공간의 차원 테이블만 보고(모델 필터), 트라이그램은 부모 source_text 그대로라 벡터가 없는 행도 키워드로 보인다(#713).
 */
@Repository
@RequiredArgsConstructor
public class DatasetSearchRepository {

  private final DSLContext dsl;

  /**
   * 코사인 top-K 데이터셋. 현재 공간의 차원 테이블 한 개 + 현재 모델 벡터만(#392). storageType 이 null 이면 필터 없음. iterative
   * scan + 바깥 재정렬({@link HnswSearch}).
   */
  public List<DatasetSearchHit> searchByCosine(
      EmbeddingSpace space,
      float[] queryEmbedding,
      String storageType,
      int topK,
      String visibilitySql) {
    List<Object> params = new java.util.ArrayList<>();
    String sql =
        semanticSql(
            space,
            storageType,
            VectorLiterals.toVectorLiteral(queryEmbedding),
            topK,
            params,
            visibilitySql);
    return HnswSearch.search(
        dsl, sql, params, DatasetSearchRepository::toHit, DatasetSearchHit::score);
  }

  /** 의미 검색 SQL. package-private — EXPLAIN 단언 테스트가 쓴다. */
  String semanticSql(
      EmbeddingSpace space,
      String storageType,
      String vector,
      int topK,
      List<Object> params,
      String visibilitySql) {
    StringBuilder sql =
        new StringBuilder(
                "SELECT d.id, d.name, d.description, d.storage_type, d.origin_type, d.table_name,"
                    + " c.name AS category_name, 1 - (v.embedding <=> ?::vector) AS score FROM ")
            .append(space.dimension().datasetTable())
            .append(" v JOIN dataset d ON d.id = v.dataset_id")
            .append(" LEFT JOIN dataset_category c ON c.id = d.category_id")
            .append(" WHERE v.embedding_model = ?");
    params.add(vector);
    params.add(space.model());
    // 가시성(보안 등급) — DatasetAccessGuard.visibleSql 이 인라인 렌더한 조건. 별칭은 d.
    sql.append(" AND ").append(visibilitySql);
    if (storageType != null) {
      sql.append(" AND d.storage_type = ?");
      params.add(storageType);
    }
    sql.append(" ORDER BY v.embedding <=> ?::vector LIMIT ?");
    params.add(vector);
    params.add(topK);
    return sql.toString();
  }

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
  public List<DatasetSearchHit> searchByTrigram(
      String query, String storageType, int topK, String visibilitySql) {
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
    // 가시성(보안 등급) — DatasetAccessGuard.visibleSql 이 인라인 렌더한 조건. 별칭은 d.
    sql.append(" AND ").append(visibilitySql);
    if (storageType != null) {
      sql.append(" AND d.storage_type = ?");
      params.add(storageType);
    }
    sql.append(" ORDER BY score DESC LIMIT ?");
    params.add(topK);

    String finalSql = sql.toString();
    Object[] finalParams = params.toArray();
    // SET LOCAL 과 조회를 같은 커넥션/트랜잭션에서 실행해야 임계값이 적용된다.
    return dsl.transactionResult(
        cfg -> {
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
