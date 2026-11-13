package com.smartfirehub.vectorstore.repository.jooq;

import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.repository.VectorLiterals;
import com.smartfirehub.embedding.repository.jooq.HnswSearch;
import com.smartfirehub.embedding.repository.jooq.VectorTables;
import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository;
import com.smartfirehub.vectorstore.ConditionalOnPlatformVectorStore;
import com.smartfirehub.vectorstore.DatasetVectorIndex;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DatasetVectorIndex} 의 pgvector 구현(ADR 0002 결정 2). platform+PostgreSQL 조합에서만 뜬다.
 *
 * <p>예전 {@code JooqDatasetSearchRepository}(검색)와 {@code JooqDatasetEmbeddingRepository} 의 벡터
 * SQL(쓰기·지우기·차원 정리)을 그대로 옮겼다. SQL 은 바꾸지 않았다. 표시 열({@code embedded_dim}·{@code embedded_model})은 여기서
 * 건드리지 않는다. 호출한 플랫폼 리포지토리가 같은 트랜잭션에서 맞춘다.
 *
 * <p>쓰기 메서드만 {@code @Transactional}(REQUIRED)이다. 리포지토리가 연 트랜잭션에 합류하므로 벡터 행과 표시 열이 한 트랜잭션으로 묶이는 동작이
 * 예전과 같다. 검색 메서드는 예전처럼 트랜잭션을 열지 않는다(SET LOCAL 이 필요한 조회는 안에서 따로 연다).
 *
 * <p>벡터 바인딩·트라이그램 임계값 설정 방식은 {@code DocumentChunkRepository} 패턴을 그대로 복제한다. embedding 은
 * '[..]'::vector 텍스트 리터럴 캐스팅으로 바인딩하고, 트라이그램은 {@code source_text %> ?} 교환 연산자로 GIN 인덱스를 태우며 같은
 * 트랜잭션에서 SET LOCAL 로 임계값을 0.1 로 낮춘다.
 *
 * <p>코사인 검색은 현재 공간의 차원 테이블만 보고(모델 필터), 트라이그램은 부모 source_text 그대로라 벡터가 없는 행도 키워드로 보인다(#713).
 */
@Repository
@ConditionalOnPostgresPlatformDb
@ConditionalOnPlatformVectorStore
@RequiredArgsConstructor
public class PgDatasetVectorIndex implements DatasetVectorIndex {

  private final DSLContext dsl;

  // 가시성 술어 렌더(WD-485 T10) — dataset 별칭 d 는 이 구현의 SQL 이 소유한다(예전 서비스가 넘기던 visibleSql(c, "d") 와 같은
  // 렌더).
  private final DatasetAccessRepository accessRepository;

  /**
   * 코사인 top-K 데이터셋. 현재 공간의 차원 테이블 한 개 + 현재 모델 벡터만(#392). storageType 이 null 이면 필터 없음. iterative
   * scan + 바깥 재정렬({@link HnswSearch}).
   */
  @Override
  public List<DatasetSearchHit> semantic(
      EmbeddingSpace space,
      float[] queryEmbedding,
      String storageType,
      int topK,
      DatasetVisibility visibility) {
    List<Object> params = new java.util.ArrayList<>();
    String sql =
        semanticSql(
            space,
            storageType,
            VectorLiterals.toVectorLiteral(queryEmbedding),
            topK,
            params,
            visibilitySql(visibility));
    return HnswSearch.search(
        dsl, sql, params, PgDatasetVectorIndex::toHit, DatasetSearchHit::score);
  }

  /**
   * 의미 검색 SQL. EXPLAIN 단언 테스트(다른 패키지)가 쓰므로 public 이다. 구현 내부 세부라 이미 렌더한 가시성 조각({@link
   * #visibilitySql})을 받는다(인터페이스에는 문자열 SQL 이 없다).
   */
  public String semanticSql(
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
    // 가시성(보안 등급) — 공개 메서드가 별칭 d 로 인라인 렌더한 조건.
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
  @Override
  public List<DatasetSearchHit> keyword(
      String query, String storageType, int topK, DatasetVisibility visibility) {
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
    // 가시성(보안 등급) — 캡처된 가시성을 별칭 d 로 인라인 렌더한 조건.
    sql.append(" AND ").append(visibilitySql(visibility));
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
          return tx.fetch(finalSql, finalParams).map(PgDatasetVectorIndex::toHit);
        });
  }

  /**
   * dense 벡터 여러 건을 {@code space} 로 쓴다. 다른 차원 행 DELETE(차원마다 {@code dataset_id = ANY(?)} 1회)와 현재 차원
   * UPSERT(JDBC 배치 1회)를 한 트랜잭션에서 한다. 부모 dataset_embedding 행이 없으면(삭제 경합) INSERT…SELECT 가 0행이라 그 건만
   * 조용히 건너뛴다. 예전 {@code JooqDatasetEmbeddingRepository.upsertEmbeddings} 의 벡터 SQL 그대로다.
   */
  @Override
  @Transactional
  public void upsertDense(EmbeddingSpace space, List<Long> datasetIds, List<float[]> embeddings) {
    if (datasetIds.size() != embeddings.size()) {
      throw new IllegalArgumentException(
          "데이터셋 수와 임베딩 수 불일치: " + datasetIds.size() + " vs " + embeddings.size());
    }
    if (datasetIds.isEmpty()) return;
    Long[] ids = datasetIds.toArray(Long[]::new);
    for (EmbeddingDimension d : EmbeddingDimension.values()) {
      if (d == space.dimension()) continue;
      dsl.execute("DELETE FROM " + d.datasetTable() + " WHERE dataset_id = ANY(?)", (Object) ids);
    }
    org.jooq.BatchBindStep batch =
        dsl.batch(
            "INSERT INTO "
                + space.dimension().datasetTable()
                + " (dataset_id, embedding, embedding_model, updated_at)"
                + " SELECT de.dataset_id, ?::vector, ?, now() FROM dataset_embedding de WHERE de.dataset_id = ?"
                + " ON CONFLICT (dataset_id) DO UPDATE SET embedding = EXCLUDED.embedding,"
                + " embedding_model = EXCLUDED.embedding_model, updated_at = now()");
    for (int i = 0; i < datasetIds.size(); i++) {
      batch =
          batch.bind(
              VectorLiterals.toVectorLiteral(embeddings.get(i)), space.model(), datasetIds.get(i));
    }
    batch.execute();
  }

  /** 모든 차원에서 이 테넌트 데이터셋들의 벡터를 지운다. RLS 와 별개로 {@code tenant_id} 를 명시한다. */
  @Override
  @Transactional
  public int deleteDense(long tenantId, List<Long> datasetIds) {
    if (datasetIds.isEmpty()) return 0;
    Long[] ids = datasetIds.toArray(Long[]::new);
    int deleted = 0;
    for (EmbeddingDimension d : EmbeddingDimension.values()) {
      deleted +=
          dsl.execute(
              "DELETE FROM " + d.datasetTable() + " WHERE tenant_id = ? AND dataset_id = ANY(?)",
              tenantId,
              ids);
    }
    return deleted;
  }

  /** 현재 차원이 아닌 테이블의 이 테넌트 카탈로그 벡터를 지운다(WHERE tenant_id 명시 — 문서 청크 쪽과 같은 규율). */
  @Override
  @Transactional
  public int deleteOtherDimensions(long tenantId, EmbeddingDimension keep) {
    return VectorTables.deleteOtherDimensions(
        dsl, keep, EmbeddingDimension::datasetTable, tenantId);
  }

  /** 차원 테이블은 카탈로그 행 FK CASCADE, 키워드는 부모 원문 열이라 데이터셋 삭제와 함께 사라진다 — 할 일이 없다. */
  @Override
  public void deleteAll(long tenantId, List<Long> datasetIds) {}

  /**
   * 가시성 술어 — 예전 서비스가 넘기던 {@code datasetAccessGuard.visibleSql(clearance, "d")} 와 바이트 동일한 렌더다(그 메서드도
   * {@code accessRepository.visibleSql(visibility(c), "d")} 였다). 별칭 d 는 이 클래스 SQL 의 {@code JOIN
   * dataset d} 다.
   */
  private String visibilitySql(DatasetVisibility visibility) {
    return accessRepository.visibleSql(visibility, "d");
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
