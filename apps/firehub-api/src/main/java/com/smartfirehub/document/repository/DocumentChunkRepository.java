package com.smartfirehub.document.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.dto.DocumentSearchHit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** document_chunk 벡터 배치 적재. embedding 은 '[..]'::vector 문자열 캐스팅으로 바인딩한다. */
@Repository
@RequiredArgsConstructor
public class DocumentChunkRepository {

  private final DSLContext dsl;
  private static final int BATCH_SIZE = 200;

  /** 해당 문서의 기존 청크를 모두 삭제(잡 재시도 시 중복 방지). */
  public void deleteByDocumentFileId(Long documentFileId) {
    dsl.deleteFrom(table(name("document_chunk")))
        .where(field(name("document_chunk", "document_file_id"), Long.class).eq(documentFileId))
        .execute();
  }

  /** 청크와 임베딩을 같은 순서로 매칭해 배치 insert. chunks.size() == embeddings.size() 전제. */
  public void insertBatch(
      Long documentFileId, Long datasetId, List<Chunk> chunks,
      List<float[]> embeddings, String embeddingModel) {
    if (chunks.size() != embeddings.size()) {
      throw new IllegalArgumentException(
          "청크 수와 임베딩 수 불일치: " + chunks.size() + " vs " + embeddings.size());
    }
    for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
      int end = Math.min(start + BATCH_SIZE, chunks.size());
      StringBuilder sql =
          new StringBuilder(
              "INSERT INTO document_chunk(document_file_id, dataset_id, chunk_index,"
                  + " content, token_count, embedding, embedding_model) VALUES ");
      List<Object> params = new java.util.ArrayList<>();
      for (int i = start; i < end; i++) {
        if (i > start) sql.append(',');
        sql.append("(?,?,?,?,?,?::vector,?)");
        Chunk c = chunks.get(i);
        params.add(documentFileId);
        params.add(datasetId);
        params.add(c.index());
        params.add(c.content());
        params.add(c.tokenCount());
        params.add(toVectorLiteral(embeddings.get(i)));
        params.add(embeddingModel);
      }
      dsl.execute(sql.toString(), params.toArray());
    }
  }

  /**
   * 쿼리 벡터와의 코사인 거리 기준 top-K 청크 조회. 완료된 문서(status='COMPLETED')만 검색한다.
   * datasetIds 가 비어있으면 전체 DOCUMENT 청크를 대상으로 한다(전역 검색).
   * score = 1 - (embedding <=> query) (코사인 유사도, 1에 가까울수록 유사).
   */
  public List<DocumentSearchHit> searchByCosine(
      float[] queryEmbedding, List<Long> datasetIds, int topK) {
    String vectorLiteral = toVectorLiteral(queryEmbedding);
    StringBuilder sql =
        new StringBuilder(
            "SELECT dc.id, dc.document_file_id, dc.dataset_id, df.original_name,"
                + " dc.chunk_index, dc.content, 1 - (dc.embedding <=> ?::vector) AS score"
                + " FROM document_chunk dc"
                + " JOIN document_file df ON df.id = dc.document_file_id"
                + " WHERE df.status = 'COMPLETED'");
    List<Object> params = new java.util.ArrayList<>();
    params.add(vectorLiteral);
    if (datasetIds != null && !datasetIds.isEmpty()) {
      sql.append(" AND dc.dataset_id IN (")
          .append(datasetIds.stream().map(x -> "?").collect(java.util.stream.Collectors.joining(",")))
          .append(")");
      params.addAll(datasetIds);
    }
    sql.append(" ORDER BY dc.embedding <=> ?::vector LIMIT ?");
    params.add(vectorLiteral);
    params.add(topK);

    return dsl.fetch(sql.toString(), params.toArray())
        .map(r -> new DocumentSearchHit(
            r.get("id", Long.class),
            r.get("document_file_id", Long.class),
            r.get("dataset_id", Long.class),
            r.get("original_name", String.class),
            r.get("chunk_index", Integer.class),
            r.get("content", String.class),
            r.get("score", Double.class)));
  }

  /**
   * pg_trgm word_similarity 기준 키워드 top-K 청크 조회. 완료된 문서만 검색한다.
   * word_similarity(query, content) 는 짧은 질의를 긴 본문의 일부와 매칭해 0~1 점수를 준다.
   * 임베딩이 필요 없어 임베딩 서비스 장애 시에도 동작한다(KEYWORD/HYBRID 회복탄력성).
   *
   * <p>필터를 {@code content %> query} 연산자로 표현해 content 의 GIN trigram 인덱스
   * (idx_document_chunk_content_trgm)를 Bitmap Index Scan 으로 사용하도록 한다(함수형
   * word_similarity()>0.1 은 인덱스를 못 타 대규모에서 seq scan — 실측 5.5만 행 298ms vs
   * 인덱스 0.9ms). 인덱스를 타려면 컬럼이 좌변이어야 하므로 교환 연산자 {@code %>} 를 쓴다
   * ({@code content %> q} ≡ {@code q <% content} ≡ {@code word_similarity(q, content) > 임계값}).
   * {@code %>} 는 pg_trgm.word_similarity_threshold GUC(기본 0.6)를 임계값으로 쓰므로, 기존
   * 0.1 floor 를 유지하려면 같은 트랜잭션에서 SET LOCAL 로 0.1 로 낮춰야 한다.
   */
  public List<DocumentSearchHit> searchByTrigram(
      String query, List<Long> datasetIds, int topK) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT dc.id, dc.document_file_id, dc.dataset_id, df.original_name,"
                + " dc.chunk_index, dc.content, word_similarity(?, dc.content) AS score"
                + " FROM document_chunk dc"
                + " JOIN document_file df ON df.id = dc.document_file_id"
                + " WHERE df.status = 'COMPLETED'");
    List<Object> params = new java.util.ArrayList<>();
    params.add(query); // SELECT 의 word_similarity 첫 인자
    if (datasetIds != null && !datasetIds.isEmpty()) {
      sql.append(" AND dc.dataset_id IN (")
          .append(datasetIds.stream().map(x -> "?").collect(java.util.stream.Collectors.joining(",")))
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
    return dsl.transactionResult(cfg -> {
      DSLContext tx = DSL.using(cfg);
      tx.execute("SET LOCAL pg_trgm.word_similarity_threshold = 0.1");
      return tx.fetch(finalSql, finalParams)
          .map(r -> new DocumentSearchHit(
              r.get("id", Long.class),
              r.get("document_file_id", Long.class),
              r.get("dataset_id", Long.class),
              r.get("original_name", String.class),
              r.get("chunk_index", Integer.class),
              r.get("content", String.class),
              r.get("score", Double.class)));
    });
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
