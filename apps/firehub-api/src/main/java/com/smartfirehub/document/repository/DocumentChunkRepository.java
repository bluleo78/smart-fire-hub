package com.smartfirehub.document.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.document.dto.Chunk;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
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
