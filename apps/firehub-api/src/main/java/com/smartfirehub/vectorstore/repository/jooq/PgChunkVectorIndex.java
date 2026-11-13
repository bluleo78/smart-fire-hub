package com.smartfirehub.vectorstore.repository.jooq;

import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.repository.jooq.HnswSearch;
import com.smartfirehub.embedding.repository.jooq.VectorTables;
import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository;
import com.smartfirehub.vectorstore.ChunkVectorIndex;
import com.smartfirehub.vectorstore.ConditionalOnPlatformVectorStore;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ChunkVectorIndex} 의 pgvector 구현(ADR 0002 결정 2). platform+PostgreSQL 조합에서만 뜬다.
 *
 * <p>예전 {@code JooqDocumentChunkRepository} 의 벡터·트라이그램 SQL 을 그대로 옮겼다. SQL 은 바꾸지 않았다. 차원 테이블 이름은
 * {@link EmbeddingDimension} 에서만 나온다. 벡터는 {@code '[..]'::vector} 문자열 캐스팅으로 바인딩한다.
 *
 * <p>클래스 레벨 {@code @Transactional}(REQUIRED)이다(예전 리포지토리와 같다). 플랫폼 리포지토리가 연 트랜잭션에 합류하므로 청크·벡터·표시 열이
 * 한 트랜잭션으로 묶인다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
@ConditionalOnPlatformVectorStore
@RequiredArgsConstructor
// 예전 JooqDocumentChunkRepository 처럼 클래스 레벨 트랜잭션이다 — 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이
// 없고 RLS GUC 는 트랜잭션 시작 때만 주입되므로, 검색·쓰기 모두 트랜잭션 안에서 돈다. REQUIRED 라 리포지토리가 연 트랜잭션에 합류한다.
@Transactional
public class PgChunkVectorIndex implements ChunkVectorIndex {

  private static final int BATCH_SIZE = 200;

  private final DSLContext dsl;

  // 가시성 술어 렌더(WD-485 T10) — dataset 별칭 d 는 이 구현의 SQL 이 소유한다.
  private final DatasetAccessRepository accessRepository;

  /**
   * 코사인 top-K. 한 차원 테이블만 쓰고 현재 모델 벡터만 본다(#392). COMPLETED 문서만. score = 1 - 거리. iterative scan 을
   * 켜고({@link HnswSearch}) relaxed_order 라 바깥에서 점수로 재정렬한다.
   */
  @Override
  public List<DocumentSearchHit> semantic(
      EmbeddingSpace space,
      float[] queryEmbedding,
      List<Long> datasetIds,
      int topK,
      DatasetVisibility visibility) {
    List<Object> params = new ArrayList<>();
    String sql =
        semanticSql(
            space,
            datasetIds,
            toVectorLiteral(queryEmbedding),
            topK,
            params,
            visibilitySql(visibility));
    return HnswSearch.search(dsl, sql, params, PgChunkVectorIndex::toHit, DocumentSearchHit::score);
  }

  /**
   * 의미 검색 SQL. 실행 계획(EXPLAIN) 단언 테스트(다른 패키지)가 쓰므로 public 이다. 구현 내부 세부라 이미 렌더한 가시성 조각({@link
   * #visibilitySql})을 받는다(포트에는 문자열 SQL 이 없다).
   */
  public String semanticSql(
      EmbeddingSpace space,
      List<Long> datasetIds,
      String vector,
      int topK,
      List<Object> params,
      String visibilitySql) {
    StringBuilder sql =
        new StringBuilder(
                "SELECT c.id, c.document_file_id, c.dataset_id, df.original_name, c.chunk_index, c.content,"
                    + " 1 - (v.embedding <=> ?::vector) AS score FROM ")
            .append(space.dimension().chunkTable())
            .append(" v JOIN document_chunk c ON c.id = v.chunk_id")
            .append(" JOIN document_file df ON df.id = c.document_file_id")
            .append(" JOIN dataset d ON d.id = c.dataset_id")
            .append(" WHERE df.status = 'COMPLETED' AND v.embedding_model = ?");
    params.add(vector);
    params.add(space.model());
    // 가시성(보안 등급) — 공개 메서드가 별칭 d 로 인라인 렌더한 조건.
    sql.append(" AND ").append(visibilitySql);
    if (datasetIds != null && !datasetIds.isEmpty()) {
      sql.append(" AND v.dataset_id IN (").append(placeholders(datasetIds.size())).append(")");
      params.addAll(datasetIds);
    }
    sql.append(" ORDER BY v.embedding <=> ?::vector LIMIT ?");
    params.add(vector);
    params.add(topK);
    return sql.toString();
  }

  /**
   * pg_trgm word_similarity 기준 키워드 top-K 청크 조회. 완료된 문서만 검색한다. word_similarity(query, content) 는 짧은
   * 질의를 긴 본문의 일부와 매칭해 0~1 점수를 준다. 임베딩이 필요 없어 임베딩 서비스 장애 시에도 동작한다(KEYWORD/HYBRID 회복탄력성).
   *
   * <p>필터를 {@code content %> query} 연산자로 표현해 content 의 GIN trigram 인덱스
   * (idx_document_chunk_content_trgm)를 Bitmap Index Scan 으로 사용하도록 한다(함수형 word_similarity()>0.1 은
   * 인덱스를 못 타 대규모에서 seq scan — 실측 5.5만 행 298ms vs 인덱스 0.9ms). 인덱스를 타려면 컬럼이 좌변이어야 하므로 교환 연산자 {@code
   * %>} 를 쓴다 ({@code content %> q} ≡ {@code q <% content} ≡ {@code word_similarity(q, content) >
   * 임계값}). {@code %>} 는 pg_trgm.word_similarity_threshold GUC(기본 0.6)를 임계값으로 쓰므로, 기존 0.1 floor 를
   * 유지하려면 같은 트랜잭션에서 SET LOCAL 로 0.1 로 낮춰야 한다.
   */
  @Override
  public List<DocumentSearchHit> keyword(
      String query, List<Long> datasetIds, int topK, DatasetVisibility visibility) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT dc.id, dc.document_file_id, dc.dataset_id, df.original_name,"
                + " dc.chunk_index, dc.content, word_similarity(?, dc.content) AS score"
                + " FROM document_chunk dc"
                + " JOIN document_file df ON df.id = dc.document_file_id"
                + " JOIN dataset d ON d.id = dc.dataset_id"
                + " WHERE df.status = 'COMPLETED'");
    List<Object> params = new ArrayList<>();
    params.add(query); // SELECT 의 word_similarity 첫 인자
    // 가시성(보안 등급) — 캡처된 가시성을 별칭 d 로 인라인 렌더한 조건.
    sql.append(" AND ").append(visibilitySql(visibility));
    if (datasetIds != null && !datasetIds.isEmpty()) {
      sql.append(" AND dc.dataset_id IN (")
          .append(datasetIds.stream().map(x -> "?").collect(Collectors.joining(",")))
          .append(")");
      params.addAll(datasetIds);
    }
    // content %> query 는 GIN trigram 인덱스를 탄다(컬럼 좌변). 임계값은 아래 SET LOCAL 의 0.1.
    sql.append(" AND dc.content %> ?");
    params.add(query); // %> 우변(질의)
    sql.append(" ORDER BY score DESC LIMIT ?");
    params.add(topK);

    String finalSql = sql.toString();
    Object[] finalParams = params.toArray();
    // SET LOCAL 과 조회를 같은 커넥션/트랜잭션에서 실행해야 임계값이 적용된다(LOCAL 은 tx 종료 시 자동 복원).
    return dsl.transactionResult(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          tx.execute("SET LOCAL pg_trgm.word_similarity_threshold = 0.1");
          return tx.fetch(finalSql, finalParams).map(PgChunkVectorIndex::toHit);
        });
  }

  /**
   * 새 청크의 벡터를 {@code space} 의 차원 테이블에 INSERT 한다. 새 청크라 다른 차원 행이 있을 수 없으므로 지우지 않는다. 예전 {@code
   * insertBatch} 의 벡터 INSERT 그대로다(배치 {@value #BATCH_SIZE}건씩).
   */
  @Override
  public void insertDense(
      EmbeddingSpace space, long datasetId, List<Long> chunkIds, List<float[]> embeddings) {
    if (chunkIds.size() != embeddings.size()) {
      throw new IllegalArgumentException(
          "청크 수와 임베딩 수 불일치: " + chunkIds.size() + " vs " + embeddings.size());
    }
    for (int start = 0; start < chunkIds.size(); start += BATCH_SIZE) {
      int end = Math.min(start + BATCH_SIZE, chunkIds.size());
      StringBuilder vsql =
          new StringBuilder("INSERT INTO ")
              .append(space.dimension().chunkTable())
              .append(" (chunk_id, dataset_id, embedding, embedding_model) VALUES ");
      List<Object> vparams = new ArrayList<>();
      for (int i = start; i < end; i++) {
        if (i > start) vsql.append(',');
        vsql.append("(?,?,?::vector,?)");
        vparams.add(chunkIds.get(i));
        vparams.add(datasetId);
        vparams.add(toVectorLiteral(embeddings.get(i)));
        vparams.add(space.model());
      }
      dsl.execute(vsql.toString(), vparams.toArray());
    }
  }

  /**
   * 기존 청크의 벡터를 {@code space} 로 옮긴다: 다른 차원 테이블의 같은 청크 행 DELETE + 현재 차원 테이블 UPSERT (같은 차원 안의 모델 교체도
   * ON CONFLICT 로 처리). 불변식 "한 청크의 벡터는 차원 테이블 전체에서 최대 1행"을 이 트랜잭션이 지킨다. 부모가 사라진 id 는 INSERT…SELECT 가
   * 0행이라 조용히 건너뛴다(재임베딩 중 문서 삭제 경합).
   */
  @Override
  public void upsertDense(EmbeddingSpace space, List<Long> chunkIds, List<float[]> embeddings) {
    if (chunkIds.size() != embeddings.size()) {
      throw new IllegalArgumentException(
          "청크 수와 임베딩 수 불일치: " + chunkIds.size() + " vs " + embeddings.size());
    }
    if (chunkIds.isEmpty()) return;
    for (EmbeddingDimension d : EmbeddingDimension.values()) {
      if (d == space.dimension()) continue;
      dsl.execute(
          "DELETE FROM "
              + d.chunkTable()
              + " WHERE chunk_id IN ("
              + placeholders(chunkIds.size())
              + ")",
          chunkIds.toArray());
    }
    String sql =
        "INSERT INTO "
            + space.dimension().chunkTable()
            + " (chunk_id, dataset_id, embedding, embedding_model)"
            + " SELECT c.id, c.dataset_id, ?::vector, ? FROM document_chunk c WHERE c.id = ?"
            + " ON CONFLICT (chunk_id) DO UPDATE SET embedding = EXCLUDED.embedding,"
            + " embedding_model = EXCLUDED.embedding_model, created_at = now()";
    for (int start = 0; start < chunkIds.size(); start += BATCH_SIZE) {
      int end = Math.min(start + BATCH_SIZE, chunkIds.size());
      org.jooq.BatchBindStep batch = dsl.batch(sql);
      for (int i = start; i < end; i++) {
        batch = batch.bind(toVectorLiteral(embeddings.get(i)), space.model(), chunkIds.get(i));
      }
      batch.execute();
    }
  }

  /** 모든 차원에서 이 테넌트 데이터셋들의 청크 벡터를 지운다. {@code tenant_id} 를 명시한다(조건 없는 DELETE 금지 규율). */
  @Override
  public int deleteDense(long tenantId, List<Long> datasetIds) {
    if (datasetIds.isEmpty()) return 0;
    Long[] ids = datasetIds.toArray(Long[]::new);
    int deleted = 0;
    for (EmbeddingDimension d : EmbeddingDimension.values()) {
      deleted +=
          dsl.execute(
              "DELETE FROM " + d.chunkTable() + " WHERE tenant_id = ? AND dataset_id = ANY(?)",
              tenantId,
              ids);
    }
    return deleted;
  }

  /**
   * 현재 차원이 아닌 테이블의 이 테넌트 청크 벡터를 지운다(재임베딩 완료 뒤 잔여 정리). RLS 만으로도 테넌트 범위지만 {@code WHERE tenant_id = ?}
   * 를 명시한다 — 소유자 커넥션에서 불려도 남의 행을 지우지 않게.
   */
  @Override
  public int deleteOtherDimensions(long tenantId, EmbeddingDimension keep) {
    return VectorTables.deleteOtherDimensions(dsl, keep, EmbeddingDimension::chunkTable, tenantId);
  }

  /** 청크 벡터는 청크 행 FK CASCADE, 키워드는 청크 본문 열이라 청크와 함께 사라진다 — 할 일이 없다. */
  @Override
  public void deleteAllOfDocument(long tenantId, long documentFileId) {}

  /** {@link #deleteAllOfDocument} 와 같은 이유로 할 일이 없다. */
  @Override
  public void deleteAllOfDatasets(long tenantId, List<Long> datasetIds) {}

  private static String placeholders(int n) {
    return IntStream.range(0, n).mapToObj(i -> "?").collect(Collectors.joining(","));
  }

  private static DocumentSearchHit toHit(org.jooq.Record r) {
    return new DocumentSearchHit(
        r.get("id", Long.class),
        r.get("document_file_id", Long.class),
        r.get("dataset_id", Long.class),
        r.get("original_name", String.class),
        r.get("chunk_index", Integer.class),
        r.get("content", String.class),
        r.get("score", Double.class));
  }

  /** float[] → pgvector 텍스트 리터럴 "[v1,v2,...]". */
  private static String toVectorLiteral(float[] v) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < v.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(v[i]);
    }
    return sb.append(']').toString();
  }

  /**
   * 가시성 술어 — 예전 서비스가 넘기던 {@code datasetAccessGuard.visibleSql(clearance, "d")} 와 바이트 동일한 렌더다. 별칭 d
   * 는 검색 SQL 의 {@code JOIN dataset d} 다.
   */
  private String visibilitySql(DatasetVisibility visibility) {
    return accessRepository.visibleSql(visibility, "d");
  }
}
