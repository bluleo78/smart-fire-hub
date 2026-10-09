package com.smartfirehub.document.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.HnswSearch;
import com.smartfirehub.embedding.VectorTables;
import com.smartfirehub.global.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * document_chunk 본문 + 차원별 벡터 테이블(document_chunk_vec_N) 적재·검색(#713).
 *
 * <p>벡터는 '[..]'::vector 문자열 캐스팅으로 바인딩한다. 테이블명은 {@link EmbeddingDimension} 에서만 나온다.
 */
@Repository
@RequiredArgsConstructor
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Transactional
public class DocumentChunkRepository {

  private final DSLContext dsl;
  private static final int BATCH_SIZE = 200;

  /** 재임베딩 대상 청크의 (id, content) 쌍. content 만 임베딩 모델에 재투입한다. */
  public record ChunkContent(long chunkId, String content) {}

  /** 해당 문서의 기존 청크를 모두 삭제(잡 재시도 시 중복 방지). */
  public void deleteByDocumentFileId(Long documentFileId) {
    dsl.deleteFrom(table(name("document_chunk")))
        .where(field(name("document_chunk", "document_file_id"), Long.class).eq(documentFileId))
        .execute();
  }

  /**
   * 청크 본문은 부모 테이블에, 벡터는 {@code space} 의 차원 테이블에 넣는다(한 트랜잭션 — 클래스 레벨 @Transactional). 새 청크라 다른 차원 행이
   * 있을 수 없으므로 삭제 없이 INSERT 한다. 벡터 행은 {@code RETURNING id, chunk_index} 의 <b>chunk_index 로</b>
   * 매핑한다(RETURNING 순서는 VALUES 순서를 보장하지 않는다).
   */
  public void insertBatch(
      Long documentFileId,
      Long datasetId,
      List<Chunk> chunks,
      List<float[]> embeddings,
      EmbeddingSpace space) {
    if (chunks.size() != embeddings.size()) {
      throw new IllegalArgumentException(
          "청크 수와 임베딩 수 불일치: " + chunks.size() + " vs " + embeddings.size());
    }
    for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
      int end = Math.min(start + BATCH_SIZE, chunks.size());
      StringBuilder sql =
          new StringBuilder(
              "INSERT INTO document_chunk(document_file_id, dataset_id, chunk_index, content, token_count) VALUES ");
      List<Object> params = new ArrayList<>();
      for (int i = start; i < end; i++) {
        if (i > start) sql.append(',');
        sql.append("(?,?,?,?,?)");
        Chunk c = chunks.get(i);
        params.add(documentFileId);
        params.add(datasetId);
        params.add(c.index());
        params.add(c.content());
        params.add(c.tokenCount());
      }
      sql.append(" RETURNING id, chunk_index");
      Map<Integer, Long> idByIndex = new HashMap<>();
      dsl.fetch(sql.toString(), params.toArray())
          .forEach(
              r -> idByIndex.put(r.get("chunk_index", Integer.class), r.get("id", Long.class)));

      StringBuilder vsql =
          new StringBuilder("INSERT INTO ")
              .append(space.dimension().chunkTable())
              .append(" (chunk_id, dataset_id, embedding, embedding_model) VALUES ");
      List<Object> vparams = new ArrayList<>();
      for (int i = start; i < end; i++) {
        if (i > start) vsql.append(',');
        vsql.append("(?,?,?::vector,?)");
        vparams.add(idByIndex.get(chunks.get(i).index()));
        vparams.add(datasetId);
        vparams.add(toVectorLiteral(embeddings.get(i)));
        vparams.add(space.model());
      }
      dsl.execute(vsql.toString(), vparams.toArray());
    }
  }

  /**
   * 벡터 없이 청크 본문만 넣는다 — 등급이 임베딩 공급자를 허용하지 않는 문서(S3 §4.3). 본문을 외부 공급자로 보내지 않으며, 이후 허용으로 바뀌면 재임베딩
   * 판정식(countMissing/findMissing)이 이 청크들을 집어 벡터를 채운다.
   */
  public void insertChunksOnly(Long documentFileId, Long datasetId, List<Chunk> chunks) {
    for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
      int end = Math.min(start + BATCH_SIZE, chunks.size());
      StringBuilder sql =
          new StringBuilder(
              "INSERT INTO document_chunk(document_file_id, dataset_id, chunk_index, content, token_count) VALUES ");
      List<Object> params = new ArrayList<>();
      for (int i = start; i < end; i++) {
        if (i > start) sql.append(',');
        sql.append("(?,?,?,?,?)");
        Chunk c = chunks.get(i);
        params.add(documentFileId);
        params.add(datasetId);
        params.add(c.index());
        params.add(c.content());
        params.add(c.tokenCount());
      }
      dsl.execute(sql.toString(), params.toArray());
    }
  }

  /**
   * 기존 청크의 벡터를 {@code space} 로 옮긴다: 다른 차원 테이블의 같은 청크 행 DELETE + 현재 차원 테이블 UPSERT (같은 차원 안의 모델 교체도
   * ON CONFLICT 로 처리). 불변식 "한 청크의 벡터는 차원 테이블 전체에서 최대 1행"을 이 트랜잭션이 지킨다. 부모가 사라진 id 는 INSERT…SELECT 가
   * 0행이라 조용히 건너뛴다(재임베딩 중 문서 삭제 경합).
   */
  public void upsertEmbeddings(
      EmbeddingSpace space, List<Long> chunkIds, List<float[]> embeddings) {
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

  /**
   * 코사인 top-K. 한 차원 테이블만 쓰고 현재 모델 벡터만 본다(#392). COMPLETED 문서만. score = 1 - 거리. iterative scan 을
   * 켜고({@link HnswSearch}) relaxed_order 라 바깥에서 점수로 재정렬한다.
   */
  public List<DocumentSearchHit> searchByCosine(
      EmbeddingSpace space,
      float[] queryEmbedding,
      List<Long> datasetIds,
      int topK,
      String visibilitySql) {
    List<Object> params = new ArrayList<>();
    String sql =
        semanticSql(
            space, datasetIds, toVectorLiteral(queryEmbedding), topK, params, visibilitySql);
    return HnswSearch.search(
        dsl, sql, params, DocumentChunkRepository::toHit, DocumentSearchHit::score);
  }

  /** 의미 검색 SQL. package-private — 실행 계획(EXPLAIN) 단언 테스트가 쓴다. */
  String semanticSql(
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
    // 가시성(보안 등급) — DatasetAccessGuard.visibleSql 이 인라인 렌더한 조건. 별칭은 d.
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
  public List<DocumentSearchHit> searchByTrigram(
      String query, List<Long> datasetIds, int topK, String visibilitySql) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT dc.id, dc.document_file_id, dc.dataset_id, df.original_name,"
                + " dc.chunk_index, dc.content, word_similarity(?, dc.content) AS score"
                + " FROM document_chunk dc"
                + " JOIN document_file df ON df.id = dc.document_file_id"
                + " JOIN dataset d ON d.id = dc.dataset_id"
                + " WHERE df.status = 'COMPLETED'");
    List<Object> params = new java.util.ArrayList<>();
    params.add(query); // SELECT 의 word_similarity 첫 인자
    // 가시성(보안 등급) — DatasetAccessGuard.visibleSql 이 인라인 렌더한 조건. 별칭은 d.
    sql.append(" AND ").append(visibilitySql);
    if (datasetIds != null && !datasetIds.isEmpty()) {
      sql.append(" AND dc.dataset_id IN (")
          .append(
              datasetIds.stream().map(x -> "?").collect(java.util.stream.Collectors.joining(",")))
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
          return tx.fetch(finalSql, finalParams)
              .map(
                  r ->
                      new DocumentSearchHit(
                          r.get("id", Long.class),
                          r.get("document_file_id", Long.class),
                          r.get("dataset_id", Long.class),
                          r.get("original_name", String.class),
                          r.get("chunk_index", Integer.class),
                          r.get("content", String.class),
                          r.get("score", Double.class)));
        });
  }

  /** 해당 데이터셋의 청크 (id, content) 를 id 오름차순으로 조회. 검수·청크 목록·데이터셋 단위 재임베딩이 쓴다. */
  public List<ChunkContent> findChunkContentsByDataset(long datasetId) {
    return dsl.fetch(
            "SELECT id, content FROM document_chunk WHERE dataset_id = ? ORDER BY id", datasetId)
        .map(r -> new ChunkContent(r.get("id", Long.class), r.get("content", String.class)));
  }

  /**
   * 주어진 청크 id 들 중 해당 데이터셋에 속한 것의 개수(중복 id 는 한 번만 센다). 검수 항목 등록이 클라이언트가 보낸 근거 청크가 정말 그 데이터셋의 것인지 확인할
   * 때 쓴다 — 없는 청크와 다른 데이터셋(또는 다른 테넌트, RLS)의 청크를 구분하지 않는다.
   */
  public long countChunksInDataset(long datasetId, Collection<Long> chunkIds) {
    if (chunkIds.isEmpty()) return 0;
    return dsl.fetchOne(
            "SELECT count(*) FROM document_chunk WHERE dataset_id = ? AND id = ANY(?)",
            datasetId,
            chunkIds.toArray(Long[]::new))
        .get(0, Long.class);
  }

  /**
   * 전체 청크 수. 재임베딩 진행률 계산의 분모 — 판정식과 같은 모집단이어야 하므로 {@code allowedSql}(EmbeddingAiGate
   * #allowedDatasetSql("c.dataset_id"))로 등급이 임베딩 공급자를 허용하지 않는 데이터셋의 청크를 뺀다(S3 §4.3).
   */
  public long countAllChunks(String allowedSql) {
    return dsl.fetchOne("SELECT COUNT(*) FROM document_chunk c WHERE " + allowedSql)
        .get(0, Long.class);
  }

  /** {@code space} 로 임베딩된 청크 수(RLS 로 현재 테넌트). 진행률 분자. */
  public long countEmbedded(EmbeddingSpace space) {
    return dsl.fetchOne(
            "SELECT count(*) FROM " + space.dimension().chunkTable() + " WHERE embedding_model = ?",
            space.model())
        .get(0, Long.class);
  }

  /**
   * 재임베딩 판정식: 현재 차원 테이블에 현재 모델 벡터가 없고 <b>등급이 임베딩 공급자를 허용하는</b> 데이터셋의 청크 수. 영향도·저장 시 투입·잡이 같은 식을 쓴다.
   * {@code allowedSql} 은 EmbeddingAiGate#allowedDatasetSql("c.dataset_id") — 정책 없는 오버로드는 두지 않는다.
   */
  public long countMissing(EmbeddingSpace space, String allowedSql) {
    return dsl.fetchOne(
            "SELECT count(*) FROM document_chunk c WHERE "
                + missingPredicate(space)
                + " AND "
                + allowedSql,
            space.model())
        .get(0, Long.class);
  }

  /** 판정식(등급 허용 포함)을 만족하는 청크를 id 순으로 {@code limit} 건(키셋 {@code afterChunkId} 이후). */
  public List<ChunkContent> findMissing(
      EmbeddingSpace space, long afterChunkId, int limit, String allowedSql) {
    return dsl.fetch(
            "SELECT c.id, c.content FROM document_chunk c WHERE c.id > ? AND "
                + missingPredicate(space)
                + " AND "
                + allowedSql
                + " ORDER BY c.id LIMIT ?",
            afterChunkId,
            space.model(),
            limit)
        .map(r -> new ChunkContent(r.get("id", Long.class), r.get("content", String.class)));
  }

  /**
   * 현재 차원이 아닌 테이블의 이 테넌트 벡터를 지운다(재임베딩 완료 뒤 잔여 정리). RLS 만으로도 테넌트 범위지만 {@code WHERE tenant_id = ?} 를
   * 명시한다 — 소유자 커넥션에서 불려도 남의 행을 지우지 않게(조건 없는 DELETE 금지 규율).
   */
  public int deleteOtherDimensions(EmbeddingDimension keep) {
    return VectorTables.deleteOtherDimensions(
        dsl, keep, EmbeddingDimension::chunkTable, TenantContext.require("다른 차원 벡터 정리"));
  }

  /**
   * 여러 데이터셋의 청크 벡터를 모든 차원에서 지운다(청크 본문 content 는 남겨 키워드 검색 유지). 정책 위반 외부 벡터 정리(AiVectorPurgeService,
   * S3 §4.3)용 — 지운 행 수를 돌려준다. {@code tenant_id} 를 명시한다(조건 없는 DELETE 금지 규율).
   */
  public int deleteVectorsOf(List<Long> datasetIds) {
    if (datasetIds.isEmpty()) return 0;
    long tenantId = TenantContext.require("청크 벡터 정리");
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

  private static String missingPredicate(EmbeddingSpace space) {
    return "NOT EXISTS (SELECT 1 FROM "
        + space.dimension().chunkTable()
        + " v WHERE v.chunk_id = c.id AND v.embedding_model = ?)";
  }

  private static String placeholders(int n) {
    return java.util.stream.IntStream.range(0, n)
        .mapToObj(i -> "?")
        .collect(Collectors.joining(","));
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
  private String toVectorLiteral(float[] v) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < v.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(v[i]);
    }
    return sb.append(']').toString();
  }
}
