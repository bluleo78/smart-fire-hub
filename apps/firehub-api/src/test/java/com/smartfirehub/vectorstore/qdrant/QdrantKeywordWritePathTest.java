package com.smartfirehub.vectorstore.qdrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository.ChunkSource;
import com.smartfirehub.embedding.SourceHash;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.support.EmbeddingTestFixtures;
import com.smartfirehub.support.EmbeddingTestFixtures.DocFixture;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.vectorstore.KeywordIndexService;
import com.smartfirehub.vectorstore.repository.jooq.JooqSearchCandidateRepository;
import io.qdrant.client.QdrantClient;
import java.time.Duration;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 키워드 쓰기 경로(ADR 0002 결정 4·8, WD-720): 커밋 뒤 쓰기, 키워드 표시(「규칙:원문 해시」), 실패·원문 변경·규칙 변경을 백로그 스윕이 따라잡기.
 *
 * <p>플랫폼 DB 리포지토리는 실제 jOOQ 빈(키워드 표시 SQL), 색인은 Testcontainers Qdrant 구현을 손으로 엮는다. 트랜잭션 경계를 실제처럼 보려고
 * 클래스에 {@code @Transactional} 을 붙이지 않는다.
 */
class QdrantKeywordWritePathTest extends IntegrationTestBase {

  private static final DatasetVisibility ALL = DatasetVisibility.UNRESTRICTED;

  private static QdrantProperties props;
  private static QdrantClient client;

  @Autowired private JooqSearchCandidateRepository candidates;
  @Autowired private DatasetEmbeddingRepository datasets;
  @Autowired private DocumentChunkRepository chunks;
  @Autowired private DSLContext dsl;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

  private QdrantDatasetVectorIndex datasetIndex;
  private QdrantChunkVectorIndex chunkIndex;
  private KeywordIndexService keywordIndex;
  private long tenant;
  private DocFixture doc;

  @BeforeAll
  static void startClient() {
    props = QdrantTestContainer.properties(2, true);
    client = QdrantClientConfig.create(props);
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  @BeforeEach
  void seed() throws Exception {
    QdrantTestContainer.deleteAllCollections(client);
    new QdrantVectorStoreLifecycle(client, props).prepare();
    datasetIndex = new QdrantDatasetVectorIndex(client, props, candidates);
    chunkIndex = new QdrantChunkVectorIndex(client, props, candidates);
    keywordIndex =
        new KeywordIndexService(datasetIndex, chunkIndex, datasets, chunks, transactionManager);
    tenant = TenantRlsTestSupport.createActiveTenant(dsl, "qkw");
    doc = inTenantFixture(tenant, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "qkw"));
    TenantContext.set(tenant);
  }

  @AfterEach
  void cleanup() {
    TenantContext.clear();
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenant);
    TenantRlsTestSupport.deleteTenants(dsl, tenant);
    TenantRlsTestSupport.deleteUser(dsl, doc.userId());
  }

  /** 편집 트랜잭션 흉내 — 원문 저장과 키워드 예약을 한 트랜잭션에서 하고 커밋(또는 롤백)한다. */
  private void editSourceText(String text, boolean commit) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        tenant,
        () -> {
          datasets.upsertSourceText(doc.datasetId(), text);
          keywordIndex.indexDatasetAfterCommit(doc.datasetId());
          if (!commit) {
            throw new RollbackSignal();
          }
        });
  }

  private static final class RollbackSignal extends RuntimeException {}

  private List<Long> keyword(String q) {
    return datasetIndex.keyword(q, null, 10, ALL).stream()
        .map(DatasetSearchHit::datasetId)
        .toList();
  }

  private String marker() {
    return inTenantFixture(
        tenant,
        () ->
            dsl.fetchOne(
                    "SELECT keyword_marker FROM dataset_embedding WHERE dataset_id = ?",
                    doc.datasetId())
                .get(0, String.class));
  }

  @Test
  void editWritesKeywordAfterCommit_andFillsMarker_rollbackWritesNothing() {
    try {
      editSourceText("화재 통계", false);
    } catch (RollbackSignal expected) {
      // 롤백 — 원문도 키워드도 남지 않는다.
    }
    assertThat(keyword("화재")).isEmpty();

    editSourceText("화재 통계", true);

    assertThat(keyword("화재")).containsExactly(doc.datasetId());
    assertThat(marker()).isEqualTo("bigram-v1:" + SourceHash.of("화재 통계"));
    assertThat(datasets.findKeywordMissing("bigram-v1", 0, 10)).isEmpty();
  }

  @Test
  void failedWriteIsCaughtUpBySweep_andChangedTextIsRewritten() {
    // Qdrant 가 응답하지 않는 색인으로 편집한다 — 편집은 성공하고 키워드만 실패해 표시가 빈다.
    QdrantProperties dead =
        new QdrantProperties("http://127.0.0.1:1", "x", 2, true, Duration.ofMillis(300));
    try (QdrantClient deadClient = QdrantClientConfig.create(dead)) {
      KeywordIndexService broken =
          new KeywordIndexService(
              new QdrantDatasetVectorIndex(deadClient, dead, candidates),
              chunkIndex,
              datasets,
              chunks,
              transactionManager);
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          tenant,
          () -> {
            datasets.upsertSourceText(doc.datasetId(), "소방 점검");
            broken.indexDatasetAfterCommit(doc.datasetId());
          });
    }
    assertThat(marker()).isNull();
    assertThat(keyword("소방")).isEmpty();

    // 백로그 스윕이 따라잡는다.
    assertThat(keywordIndex.sweepCurrentTenant()).isEqualTo(1);
    assertThat(keyword("소방")).containsExactly(doc.datasetId());
    assertThat(keywordIndex.sweepCurrentTenant()).isZero();

    // 원문이 바뀌면(편집 경로를 거치지 않은 쓰기라도) 표시가 어긋나 스윕이 새 원문으로 덮어쓴다.
    datasets.upsertSourceText(doc.datasetId(), "구조 출동");
    assertThat(datasets.findKeywordMissing("bigram-v1", 0, 10)).hasSize(1);
    assertThat(keywordIndex.sweepCurrentTenant()).isEqualTo(1);
    assertThat(keyword("구조")).containsExactly(doc.datasetId());
    assertThat(keyword("소방")).isEmpty();

    // 규칙이 바뀌면 모든 표시가 어긋난다(다른 규칙 표지로 물으면 백로그다).
    assertThat(datasets.findKeywordMissing("bigram-v2", 0, 10)).hasSize(1);
  }

  @Test
  void chunkKeywordsAreWrittenOnIngest_andSweptWhenMissing() {
    List<Long> ids =
        chunks.insertChunks(
            doc.fileId(),
            doc.datasetId(),
            List.of(new Chunk(0, "소방 점검 결과 보고", 1), new Chunk(1, "화재 예방 계획", 1)));
    // 첫 청크만 적재 경로로 쓴다 — 둘째는 실패한 셈(표시 없음).
    keywordIndex.indexChunks(
        List.of(new ChunkSource(ids.get(0), doc.datasetId(), doc.fileId(), "소방 점검 결과 보고")));

    assertThat(chunkKeyword("소방 점검")).containsExactly(ids.get(0));
    assertThat(chunkKeyword("화재 예방")).isEmpty();
    assertThat(chunks.findKeywordMissing("bigram-v1", 0, 10))
        .extracting(ChunkSource::chunkId)
        .containsExactly(ids.get(1));

    assertThat(keywordIndex.sweepCurrentTenant()).isEqualTo(1);
    assertThat(chunkKeyword("화재 예방")).containsExactly(ids.get(1));
    assertThat(chunks.findKeywordMissing("bigram-v1", 0, 10)).isEmpty();
  }

  private List<Long> chunkKeyword(String q) {
    return chunkIndex.keyword(q, null, 10, ALL).stream().map(DocumentSearchHit::chunkId).toList();
  }
}
