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

  @Test
  void searchByCosineReturnsNearestCompletedChunks() {
    Long userId = dsl.fetchOne(
        "INSERT INTO \"user\"(username, password, name, email) VALUES"
            + " ('docsearch','x','Doc Search','docsearch@example.com') RETURNING id").get(0, Long.class);
    Long datasetId = dsl.fetchOne(
        "INSERT INTO dataset(name, table_name, dataset_type, created_by) VALUES"
            + " ('docsearch-set','data.docsearch_set','DOCUMENT', ?) RETURNING id", userId).get(0, Long.class);
    Long fileId = dsl.fetchOne(
        "INSERT INTO document_file(dataset_id, original_name, mime_type, file_size,"
            + " storage_path, status, uploaded_by) VALUES (?, 'doc.txt','text/plain',3,'/tmp/d','COMPLETED', ?)"
            + " RETURNING id", datasetId, userId).get(0, Long.class);

    chunkRepository.insertBatch(
        fileId, datasetId,
        List.of(new Chunk(0, "near", 1), new Chunk(1, "far", 1)),
        List.of(vec(1f, 0f), vec(0f, 1f)),
        "bge-m3");

    var hits = chunkRepository.searchByCosine(vec(1f, 0f), List.of(datasetId), 5);

    assertThat(hits).isNotEmpty();
    assertThat(hits.get(0).content()).isEqualTo("near");
    assertThat(hits.get(0).fileName()).isEqualTo("doc.txt");
    assertThat(hits.get(0).score()).isGreaterThan(hits.get(hits.size() - 1).score());
  }

  @Test
  void searchByCosineExcludesNonCompletedFiles() {
    Long userId = dsl.fetchOne(
        "INSERT INTO \"user\"(username, password, name, email) VALUES"
            + " ('docsearch2','x','Doc Search2','docsearch2@example.com') RETURNING id").get(0, Long.class);
    Long datasetId = dsl.fetchOne(
        "INSERT INTO dataset(name, table_name, dataset_type, created_by) VALUES"
            + " ('docsearch2-set','data.docsearch2_set','DOCUMENT', ?) RETURNING id", userId).get(0, Long.class);
    Long fileId = dsl.fetchOne(
        "INSERT INTO document_file(dataset_id, original_name, mime_type, file_size,"
            + " storage_path, status, uploaded_by) VALUES (?, 'p.txt','text/plain',3,'/tmp/p','PARSING', ?)"
            + " RETURNING id", datasetId, userId).get(0, Long.class);
    chunkRepository.insertBatch(fileId, datasetId,
        List.of(new Chunk(0, "hidden", 1)), List.of(vec(1f, 0f)), "bge-m3");

    var hits = chunkRepository.searchByCosine(vec(1f, 0f), List.of(datasetId), 5);
    assertThat(hits).isEmpty();
  }

  @Test
  void searchByTrigramFindsExactTermChunk() {
    Long userId = dsl.fetchOne(
        "INSERT INTO \"user\"(username, password, name, email) VALUES"
            + " ('doctrgm','x','Doc Trgm','doctrgm@example.com') RETURNING id").get(0, Long.class);
    Long datasetId = dsl.fetchOne(
        "INSERT INTO dataset(name, table_name, dataset_type, created_by) VALUES"
            + " ('doctrgm-set','data.doctrgm_set','DOCUMENT', ?) RETURNING id", userId).get(0, Long.class);
    Long fileId = dsl.fetchOne(
        "INSERT INTO document_file(dataset_id, original_name, mime_type, file_size,"
            + " storage_path, status, uploaded_by) VALUES (?, 't.txt','text/plain',3,'/tmp/t','COMPLETED', ?)"
            + " RETURNING id", datasetId, userId).get(0, Long.class);

    // 고유 식별자가 든 청크와 무관한 청크 — 키워드 검색은 식별자 청크를 찾아야 한다.
    chunkRepository.insertBatch(
        fileId, datasetId,
        List.of(new Chunk(0, "재난번호 UR4206974320 강릉 산불 피해", 1),
                new Chunk(1, "전혀 무관한 일반 텍스트입니다", 1)),
        List.of(vec(1f, 0f), vec(0f, 1f)),
        "bge-m3");

    var hits = chunkRepository.searchByTrigram("UR4206974320", List.of(datasetId), 5);

    assertThat(hits).isNotEmpty();
    assertThat(hits.get(0).content()).contains("UR4206974320");
  }

  @Test
  void searchByTrigramReturnsModerateSimilarityBelowDefaultThreshold() {
    // word_similarity('fire statistics report', 'annual wildfire damage summary 2026') ≈ 0.133.
    // 0.1 floor 위지만 pg_trgm 기본 word_similarity_threshold(0.6) 아래의 "중간 유사도" 케이스.
    // searchByTrigram 이 SET LOCAL 로 임계값을 0.1 로 낮춰야만 <% 가 이 청크를 통과시킨다.
    // 만약 기본 0.6 이 적용되면 누락되므로, 이 테스트는 GUC 보존 회귀를 잡아낸다.
    Long userId = dsl.fetchOne(
        "INSERT INTO \"user\"(username, password, name, email) VALUES"
            + " ('doctrgm3','x','Doc Trgm3','doctrgm3@example.com') RETURNING id").get(0, Long.class);
    Long datasetId = dsl.fetchOne(
        "INSERT INTO dataset(name, table_name, dataset_type, created_by) VALUES"
            + " ('doctrgm3-set','data.doctrgm3_set','DOCUMENT', ?) RETURNING id", userId).get(0, Long.class);
    Long fileId = dsl.fetchOne(
        "INSERT INTO document_file(dataset_id, original_name, mime_type, file_size,"
            + " storage_path, status, uploaded_by) VALUES (?, 'm.txt','text/plain',3,'/tmp/m','COMPLETED', ?)"
            + " RETURNING id", datasetId, userId).get(0, Long.class);

    chunkRepository.insertBatch(
        fileId, datasetId,
        List.of(new Chunk(0, "annual wildfire damage summary 2026", 1)),
        List.of(vec(1f, 0f)),
        "bge-m3");

    var hits = chunkRepository.searchByTrigram("fire statistics report", List.of(datasetId), 5);

    assertThat(hits).isNotEmpty();
    assertThat(hits.get(0).content()).isEqualTo("annual wildfire damage summary 2026");
  }

  @Test
  void searchByTrigramExcludesNonCompletedFiles() {
    Long userId = dsl.fetchOne(
        "INSERT INTO \"user\"(username, password, name, email) VALUES"
            + " ('doctrgm2','x','Doc Trgm2','doctrgm2@example.com') RETURNING id").get(0, Long.class);
    Long datasetId = dsl.fetchOne(
        "INSERT INTO dataset(name, table_name, dataset_type, created_by) VALUES"
            + " ('doctrgm2-set','data.doctrgm2_set','DOCUMENT', ?) RETURNING id", userId).get(0, Long.class);
    Long fileId = dsl.fetchOne(
        "INSERT INTO document_file(dataset_id, original_name, mime_type, file_size,"
            + " storage_path, status, uploaded_by) VALUES (?, 'pp.txt','text/plain',3,'/tmp/pp','PARSING', ?)"
            + " RETURNING id", datasetId, userId).get(0, Long.class);
    chunkRepository.insertBatch(fileId, datasetId,
        List.of(new Chunk(0, "UR4206974320 숨김", 1)), List.of(vec(1f, 0f)), "bge-m3");

    var hits = chunkRepository.searchByTrigram("UR4206974320", List.of(datasetId), 5);
    assertThat(hits).isEmpty();
  }
}
