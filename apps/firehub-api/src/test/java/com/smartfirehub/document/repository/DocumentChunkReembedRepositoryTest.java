package com.smartfirehub.document.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.repository.DocumentChunkRepository.ChunkContent;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** 재임베딩 경로용 조회/배치갱신/집계 메서드 검증. */
@Transactional
class DocumentChunkReembedRepositoryTest extends IntegrationTestBase {

  @Autowired private DocumentChunkRepository chunkRepository;
  @Autowired private DSLContext dsl;

  /** 1024 차원 임베딩 벡터 생성(앞 2개만 의미값). */
  private float[] vec(float a, float b) {
    float[] v = new float[1024];
    v[0] = a;
    v[1] = b;
    return v;
  }

  /** user → dataset → document_file → 청크 2개를 시드하고 dataset id 를 반환. */
  private long seedDataset(String slug) {
    Long userId = dsl.fetchOne(
        "INSERT INTO \"user\"(username, password, name, email) VALUES"
            + " (?, 'x', ?, ?) RETURNING id",
        slug, slug, slug + "@example.com").get(0, Long.class);
    Long datasetId = dsl.fetchOne(
        "INSERT INTO dataset(name, table_name, storage_type, origin_type, created_by) VALUES"
            + " (?, ?, 'DOCUMENT', 'SOURCE', ?) RETURNING id",
        slug + "-set", "data." + slug + "_set", userId).get(0, Long.class);
    Long fileId = dsl.fetchOne(
        "INSERT INTO document_file(dataset_id, original_name, mime_type, file_size,"
            + " storage_path, status, uploaded_by) VALUES (?, 'a.txt','text/plain',3,'/tmp/a','COMPLETED', ?)"
            + " RETURNING id", datasetId, userId).get(0, Long.class);

    chunkRepository.insertBatch(
        fileId, datasetId,
        List.of(new Chunk(0, "near", 1), new Chunk(1, "far", 1)),
        List.of(vec(1f, 0f), vec(0f, 1f)),
        "bge-m3");
    return datasetId;
  }

  @Test
  void findDocumentDatasetIdsReturnsNonNull() {
    seedDataset("reembedids");
    List<Long> ids = chunkRepository.findDocumentDatasetIds();
    assertThat(ids).isNotNull();
    assertThat(ids).isNotEmpty();
  }

  @Test
  void findChunkContentsByDatasetReturnsRows() {
    long datasetId = seedDataset("reembedcontents");
    List<ChunkContent> rows = chunkRepository.findChunkContentsByDataset(datasetId);
    assertThat(rows).hasSize(2);
    assertThat(rows).allSatisfy(r -> {
      assertThat(r.chunkId()).isPositive();
      assertThat(r.content()).isNotBlank();
    });
    // id 오름차순 보장
    assertThat(rows.get(0).chunkId()).isLessThan(rows.get(1).chunkId());
  }

  @Test
  void updateEmbeddingBatchUpdatesEmbeddingAndModel() {
    long datasetId = seedDataset("reembedupdate");
    List<ChunkContent> rows = chunkRepository.findChunkContentsByDataset(datasetId);
    List<Long> chunkIds = rows.stream().map(ChunkContent::chunkId).toList();
    List<float[]> embeddings = List.of(vec(0.5f, 0.5f), vec(0.25f, 0.75f));

    chunkRepository.updateEmbeddingBatch(chunkIds, embeddings, "test-model");

    long embedded = chunkRepository.countEmbeddedByModel("test-model");
    assertThat(embedded).isGreaterThanOrEqualTo(2);
  }

  @Test
  void countAllChunksAndCountEmbeddedByModelAreNonNegative() {
    seedDataset("reembedcount");
    assertThat(chunkRepository.countAllChunks()).isGreaterThanOrEqualTo(0);
    assertThat(chunkRepository.countEmbeddedByModel("bge-m3")).isGreaterThanOrEqualTo(0);
  }
}
