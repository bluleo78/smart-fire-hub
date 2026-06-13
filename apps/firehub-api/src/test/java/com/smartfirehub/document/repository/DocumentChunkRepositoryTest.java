package com.smartfirehub.document.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** 벡터 배치 insert + 코사인 검색이 동작하는지 검증. */
@Transactional
class DocumentChunkRepositoryTest extends IntegrationTestBase {

  @Autowired private DocumentChunkRepository chunkRepository;
  @Autowired private DSLContext dsl;

  private float[] vec(float a, float b) {
    float[] v = new float[1024];
    v[0] = a;
    v[1] = b;
    return v;
  }

  private static String probe() {
    StringBuilder sb = new StringBuilder("[1,0");
    for (int i = 2; i < 1024; i++) sb.append(",0");
    return sb.append("]").toString();
  }

  @Test
  void insertsChunksAndCosineSearchRanksNearestFirst() {
    Long userId = dsl.fetchOne(
        "INSERT INTO \"user\"(username, password, name, email) VALUES"
            + " ('docrepo','x','Doc Repo','docrepo@example.com') RETURNING id").get(0, Long.class);
    Long datasetId = dsl.fetchOne(
        "INSERT INTO dataset(name, table_name, dataset_type, created_by) VALUES"
            + " ('docrepo-set','data.docrepo_set','DOCUMENT', ?) RETURNING id", userId).get(0, Long.class);
    Long fileId = dsl.fetchOne(
        "INSERT INTO document_file(dataset_id, original_name, mime_type, file_size,"
            + " storage_path, status, uploaded_by) VALUES (?, 'a.txt','text/plain',3,'/tmp/a','COMPLETED', ?)"
            + " RETURNING id", datasetId, userId).get(0, Long.class);

    chunkRepository.insertBatch(
        fileId, datasetId,
        List.of(new Chunk(0, "near", 1), new Chunk(1, "far", 1)),
        List.of(vec(1f, 0f), vec(0f, 1f)),
        "bge-m3");

    String nearest = dsl.fetchOne(
        "SELECT content FROM document_chunk WHERE dataset_id = ? ORDER BY embedding <=> ?::vector LIMIT 1",
        datasetId, probe()).get(0, String.class);
    assertThat(nearest).isEqualTo("near");

    int count = dsl.fetchCount(dsl.selectFrom("document_chunk").where("dataset_id = ?", datasetId));
    assertThat(count).isEqualTo(2);
  }

  @Test
  void insertsAcrossMultipleBatches() {
    Long userId = dsl.fetchOne(
        "INSERT INTO \"user\"(username, password, name, email) VALUES"
            + " ('docrepo2','x','Doc Repo2','docrepo2@example.com') RETURNING id").get(0, Long.class);
    Long datasetId = dsl.fetchOne(
        "INSERT INTO dataset(name, table_name, dataset_type, created_by) VALUES"
            + " ('docrepo2-set','data.docrepo2_set','DOCUMENT', ?) RETURNING id", userId).get(0, Long.class);
    Long fileId = dsl.fetchOne(
        "INSERT INTO document_file(dataset_id, original_name, mime_type, file_size,"
            + " storage_path, status, uploaded_by) VALUES (?, 'b.txt','text/plain',3,'/tmp/b','COMPLETED', ?)"
            + " RETURNING id", datasetId, userId).get(0, Long.class);

    int n = 201;
    var chunks = new java.util.ArrayList<Chunk>();
    var embeddings = new java.util.ArrayList<float[]>();
    for (int i = 0; i < n; i++) {
      chunks.add(new Chunk(i, "c" + i, 1));
      embeddings.add(vec(1f, 0f));
    }
    chunkRepository.insertBatch(fileId, datasetId, chunks, embeddings, "bge-m3");

    int count = dsl.fetchCount(dsl.selectFrom("document_chunk").where("dataset_id = ?", datasetId));
    assertThat(count).isEqualTo(n);
  }
}
