package com.smartfirehub.vectorstore.qdrant;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.support.EmbeddingTestFixtures;
import com.smartfirehub.support.EmbeddingTestFixtures.DocFixture;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.vectorstore.ChunkRef;
import com.smartfirehub.vectorstore.KeywordSource;
import com.smartfirehub.vectorstore.repository.jooq.JooqSearchCandidateRepository;
import io.qdrant.client.QdrantClient;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Qdrant 색인 구현만의 계약(WD-720): 테넌트 교차 0건, 모델 필터, dense 만 지우기, 다른 차원 정리, 전부 지우기, 문서 상태 사후 확인, 테넌트 문맥
 * 강제.
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다 — Qdrant 삭제는 아웃박스에 쌓였다가 워커가 반영하므로(WD-721) 쌓은 행이 커밋돼야 워커가
 * 읽는다. 시험은 삭제 뒤 워커를 직접 한 번 돌린다. 플랫폼 DB 시드는 테넌트를 새로 만들어 커밋하고 끝나면 지운다.
 */
class QdrantVectorIndexIsolationTest extends IntegrationTestBase {

  private static final EmbeddingSpace S1024 =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");
  private static final EmbeddingSpace S1536 = new EmbeddingSpace(EmbeddingDimension.D1536, "m2");
  private static final DatasetVisibility ALL = DatasetVisibility.UNRESTRICTED;

  private static QdrantProperties props;
  private static QdrantClient client;

  @Autowired private JooqSearchCandidateRepository candidates;
  @Autowired private com.smartfirehub.vectorstore.repository.jooq.JooqVectorOutboxRepository outbox;

  @Autowired
  private com.smartfirehub.vectorstore.repository.jooq.JooqVectorMarkerRepository markers;

  @Autowired private DatasetEmbeddingRepository datasets;
  @Autowired private DocumentChunkRepository chunks;
  @Autowired private DSLContext dsl;

  private QdrantDatasetVectorIndex datasetIndex;
  private QdrantChunkVectorIndex chunkIndex;
  private VectorOutboxWorker worker;
  private long tenantA;
  private long tenantB;
  private DocFixture docA;
  private DocFixture docB;

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
    datasetIndex = new QdrantDatasetVectorIndex(client, props, candidates, outbox, markers);
    chunkIndex = new QdrantChunkVectorIndex(client, props, candidates, outbox, markers);
    worker = new VectorOutboxWorker(null, outbox, new QdrantOutboxApplier(client, props), false);
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "qiso-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "qiso-b");
    docA =
        inTenantFixture(tenantA, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "qisoa"));
    docB =
        inTenantFixture(tenantB, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "qisob"));
    // 두 테넌트에 같은 원문 — 교차가 새면 바로 보인다.
    TenantContext.runScoped(tenantA, () -> datasets.upsertSourceText(docA.datasetId(), "화재 통계"));
    TenantContext.runScoped(tenantB, () -> datasets.upsertSourceText(docB.datasetId(), "화재 통계"));
    TenantContext.clear();
  }

  @AfterEach
  void cleanup() {
    TenantContext.clear();
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenantA);
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenantB);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
    TenantRlsTestSupport.deleteUser(dsl, docA.userId());
    TenantRlsTestSupport.deleteUser(dsl, docB.userId());
  }

  /**
   * 테넌트 범위에서 삭제를 부르고(아웃박스에 쌓임, WD-721) 워커로 Qdrant 에 반영한 뒤 결과를 돌려준다. 포트의 삭제는 현재 테넌트에 쌓이므로 테넌트 문맥이
   * 있어야 한다.
   */
  private <T> T deleteIn(long tenant, java.util.function.Supplier<T> delete) {
    T result = TenantContext.runScopedGet(tenant, delete);
    drain(tenant);
    return result;
  }

  private void drain(long tenant) {
    TenantContext.runScopedGet(tenant, worker::drainCurrentTenant);
  }

  /** 테넌트 범위에서 데이터셋 dense·키워드를 쓰고 표시 열을 그 공간으로 채운다. */
  private void indexDataset(long tenant, long datasetId, EmbeddingSpace space, float[] v) {
    TenantContext.runScoped(
        tenant,
        () -> {
          datasetIndex.upsertDense(space, List.of(datasetId), List.of(v));
          datasetIndex.upsertKeyword(tenant, List.of(KeywordSource.dataset(datasetId, "화재 통계")));
        });
    marker("dataset_embedding", "dataset_id", tenant, datasetId, space);
  }

  private void marker(String table, String idCol, long tenant, long id, EmbeddingSpace space) {
    inTenantFixture(
        tenant,
        () ->
            dsl.execute(
                "UPDATE "
                    + table
                    + " SET embedded_dim = ?, embedded_model = ? WHERE "
                    + idCol
                    + " = ?",
                space.dimension().size(),
                space.model(),
                id));
  }

  private List<Long> semanticIds(long tenant, EmbeddingSpace space, float[] q) {
    return TenantContext.runScopedGet(tenant, () -> datasetIndex.semantic(space, q, null, 10, ALL))
        .stream()
        .map(DatasetSearchHit::datasetId)
        .toList();
  }

  private List<Long> keywordIds(long tenant) {
    return TenantContext.runScopedGet(tenant, () -> datasetIndex.keyword("화재", null, 10, ALL))
        .stream()
        .map(DatasetSearchHit::datasetId)
        .toList();
  }

  @Test
  void datasetSearchNeverCrossesTenants() {
    indexDataset(tenantA, docA.datasetId(), S1024, axis(1024, 0));
    indexDataset(tenantB, docB.datasetId(), S1024, axis(1024, 0));

    assertThat(semanticIds(tenantA, S1024, axis(1024, 0))).containsExactly(docA.datasetId());
    assertThat(semanticIds(tenantB, S1024, axis(1024, 0))).containsExactly(docB.datasetId());
    assertThat(keywordIds(tenantA)).containsExactly(docA.datasetId());
    assertThat(keywordIds(tenantB)).containsExactly(docB.datasetId());
  }

  @Test
  void semanticFiltersByModel_andRequiresTenantContext() {
    indexDataset(tenantA, docA.datasetId(), S1024, axis(1024, 0));

    // 같은 차원 다른 모델 공간 — 벡터를 비교할 수 없어 0건(#392).
    assertThat(
            semanticIds(tenantA, new EmbeddingSpace(EmbeddingDimension.D1024, "x"), axis(1024, 0)))
        .isEmpty();
    // 테넌트 문맥 없이 부르면 예외 — 필터 없는 공유 컬렉션 검색을 막는다.
    TenantContext.clear();
    assertThatThrownBy(() -> datasetIndex.semantic(S1024, axis(1024, 0), null, 10, ALL))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> datasetIndex.keyword("화재", null, 10, ALL))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void deleteDenseKeepsKeyword_otherDimensionsAndDeleteAllClean() {
    indexDataset(tenantA, docA.datasetId(), S1024, axis(1024, 0));
    indexDataset(tenantB, docB.datasetId(), S1024, axis(1024, 0));

    // dense 만 지운다 — 키워드는 남는다. 남의 테넌트는 그대로다.
    assertThat(
            deleteIn(tenantA, () -> datasetIndex.deleteDense(tenantA, List.of(docA.datasetId()))))
        .isEqualTo(1);
    assertThat(semanticIds(tenantA, S1024, axis(1024, 0))).isEmpty();
    assertThat(keywordIds(tenantA)).containsExactly(docA.datasetId());
    assertThat(semanticIds(tenantB, S1024, axis(1024, 0))).containsExactly(docB.datasetId());

    // 새 차원으로 쓰면 옛 차원 점이 지워지고(표시가 옛 차원이라 아웃박스에 쌓인다), 다른 차원 정리는 남은 표시를 센다.
    indexDataset(tenantA, docA.datasetId(), S1024, axis(1024, 0));
    indexDataset(tenantA, docA.datasetId(), S1536, axis(1536, 0));
    drain(tenantA);
    assertThat(semanticIds(tenantA, S1024, axis(1024, 0))).isEmpty();
    assertThat(
            deleteIn(
                tenantA,
                () -> datasetIndex.deleteOtherDimensions(tenantA, EmbeddingDimension.D1536)))
        .isZero();
    assertThat(semanticIds(tenantA, S1536, axis(1536, 0))).containsExactly(docA.datasetId());
    assertThat(
            deleteIn(
                tenantA,
                () -> datasetIndex.deleteOtherDimensions(tenantA, EmbeddingDimension.D1024)))
        .isEqualTo(1);
    assertThat(semanticIds(tenantA, S1536, axis(1536, 0))).isEmpty();

    // 데이터셋 삭제 — dense·키워드 모두 지운다.
    indexDataset(tenantA, docA.datasetId(), S1024, axis(1024, 0));
    deleteIn(
        tenantA,
        () -> {
          datasetIndex.deleteAll(tenantA, List.of(docA.datasetId()));
          return null;
        });
    assertThat(semanticIds(tenantA, S1024, axis(1024, 0))).isEmpty();
    assertThat(keywordIds(tenantA)).isEmpty();
    assertThat(keywordIds(tenantB)).containsExactly(docB.datasetId());
  }

  @Test
  void chunkSearchShowsCompletedDocumentsOnly_crossesNoTenant_andDocumentDeleteCleans() {
    long parsingFile =
        inTenantFixture(
            tenantA,
            () ->
                dsl.fetchOne(
                        "INSERT INTO document_file(dataset_id, original_name, mime_type, file_size,"
                            + " storage_path, status, uploaded_by) VALUES (?, 'p.txt', 'text/plain', 1,"
                            + " '/tmp/p', 'PARSING', ?) RETURNING id",
                        docA.datasetId(),
                        docA.userId())
                    .get(0, Long.class));
    long done = indexChunk(tenantA, docA.datasetId(), docA.fileId(), "소방 점검 완료 보고");
    indexChunk(tenantA, docA.datasetId(), parsingFile, "소방 점검 적재 중");
    long other = indexChunk(tenantB, docB.datasetId(), docB.fileId(), "소방 점검 완료 보고");

    assertThat(chunkKeyword(tenantA, null)).containsExactly(done);
    assertThat(chunkSemantic(tenantA, null)).containsExactly(done);
    assertThat(chunkKeyword(tenantB, null)).containsExactly(other);
    // 요청 데이터셋으로 좁힌다 — 남의 데이터셋을 넣어도 나오지 않는다.
    assertThat(chunkKeyword(tenantA, List.of(docB.datasetId()))).isEmpty();

    deleteIn(
        tenantA,
        () -> {
          chunkIndex.deleteAllOfDocument(tenantA, docA.fileId());
          return null;
        });
    assertThat(chunkKeyword(tenantA, null)).isEmpty();
    assertThat(chunkSemantic(tenantA, null)).isEmpty();
    assertThat(chunkKeyword(tenantB, null)).containsExactly(other);
  }

  private long indexChunk(long tenant, long datasetId, long fileId, String content) {
    long id =
        TenantContext.runScopedGet(
                tenant,
                () -> chunks.insertChunks(fileId, datasetId, List.of(new Chunk(0, content, 1))))
            .get(0);
    ChunkRef ref = new ChunkRef(id, datasetId, fileId);
    TenantContext.runScoped(
        tenant,
        () -> {
          chunkIndex.upsertDense(S1024, List.of(ref), List.of(axis(1024, 3)));
          chunkIndex.upsertKeyword(tenant, List.of(KeywordSource.chunk(ref, content)));
        });
    marker("document_chunk", "id", tenant, id, S1024);
    return id;
  }

  private List<Long> chunkKeyword(long tenant, List<Long> datasetIds) {
    return TenantContext.runScopedGet(
            tenant, () -> chunkIndex.keyword("소방 점검", datasetIds, 10, ALL))
        .stream()
        .map(DocumentSearchHit::chunkId)
        .toList();
  }

  private List<Long> chunkSemantic(long tenant, List<Long> datasetIds) {
    return TenantContext.runScopedGet(
            tenant, () -> chunkIndex.semantic(S1024, axis(1024, 3), datasetIds, 10, ALL))
        .stream()
        .map(DocumentSearchHit::chunkId)
        .toList();
  }
}
