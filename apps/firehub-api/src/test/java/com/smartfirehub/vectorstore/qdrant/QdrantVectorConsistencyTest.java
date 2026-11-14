package com.smartfirehub.vectorstore.qdrant;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static io.qdrant.client.ValueFactory.value;
import static org.assertj.core.api.Assertions.assertThat;

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
import com.smartfirehub.vectorstore.VectorMarkerRepository.Marker;
import com.smartfirehub.vectorstore.VectorOutboxKind;
import com.smartfirehub.vectorstore.VectorOutboxRepository.Entry;
import com.smartfirehub.vectorstore.repository.jooq.JooqSearchCandidateRepository;
import com.smartfirehub.vectorstore.repository.jooq.JooqVectorMarkerRepository;
import com.smartfirehub.vectorstore.repository.jooq.JooqVectorOutboxRepository;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.GetPoints;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Qdrant 벡터 정합성(WD-721, ADR 0002 결정 6) — 순번 아웃박스·워커·대조 스윕의 완료 기준을 고정한다.
 *
 * <ul>
 *   <li>「불허 → 허용 → 재임베딩 → 늦은 삭제」 경합에서 새 벡터가 남는다
 *   <li>문서 재적재(같은 문서 id 로 새 청크) 뒤 늦은 삭제가 새 청크를 지우지 않는다
 *   <li>순번이 없는 점(순번을 싣기 전에 쓴 점)도 워커가 지운다
 *   <li>아웃박스가 유실돼도 대조 스윕이 저장소에만 남은 점을 지우고, 점이 없는 표시를 비운다. 울타리 뒤에 쓴 점은 건드리지 않는다
 *   <li>저장소가 내려가 있어도 삭제 쌓기는 성공하고, 반영 실패는 행에 남는다
 * </ul>
 *
 * <p>플랫폼 DB 시드는 테넌트를 새로 만들어 커밋하고 끝나면 지운다. 표시 열은 리포지토리 대신 시험이 직접 맞춘다 — 포트의 순번 동작만 떼어 보려는 것이다.
 */
class QdrantVectorConsistencyTest extends IntegrationTestBase {

  private static final EmbeddingSpace S1024 =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");
  private static final DatasetVisibility ALL = DatasetVisibility.UNRESTRICTED;

  private static QdrantProperties props;
  private static QdrantClient client;

  @Autowired private JooqSearchCandidateRepository candidates;
  @Autowired private JooqVectorOutboxRepository outbox;
  @Autowired private JooqVectorMarkerRepository markers;
  @Autowired private DatasetEmbeddingRepository datasets;
  @Autowired private DocumentChunkRepository chunks;
  @Autowired private DSLContext dsl;

  private QdrantDatasetVectorIndex datasetIndex;
  private QdrantChunkVectorIndex chunkIndex;
  private VectorOutboxWorker worker;
  private QdrantReconcileSweeper sweeper;
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
    datasetIndex = new QdrantDatasetVectorIndex(client, props, candidates, outbox, markers);
    chunkIndex = new QdrantChunkVectorIndex(client, props, candidates, outbox, markers);
    worker = new VectorOutboxWorker(null, outbox, new QdrantOutboxApplier(client, props), false);
    sweeper = new QdrantReconcileSweeper(client, props, null, outbox, markers, false);
    tenant = TenantRlsTestSupport.createActiveTenant(dsl, "qcons");
    doc = inTenantFixture(tenant, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "qcons"));
    TenantContext.runScoped(tenant, () -> datasets.upsertSourceText(doc.datasetId(), "화재 통계"));
    TenantContext.clear();
  }

  @AfterEach
  void cleanup() {
    TenantContext.clear();
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenant);
    TenantRlsTestSupport.deleteTenants(dsl, tenant);
    TenantRlsTestSupport.deleteUser(dsl, doc.userId());
  }

  @Test
  @DisplayName("불허 → 허용 → 재임베딩 → 늦은 삭제 — 삭제를 쌓은 뒤 다시 쓴 벡터는 워커가 지우지 않는다")
  void lateDeleteKeepsRewrittenVector() {
    indexDataset(axis(1024, 0));

    // 불허: 삭제를 쌓고 표시를 비운다(리포지토리 deleteVectorsOf 와 같은 순서). 아직 반영하지 않는다.
    in(() -> datasetIndex.deleteDense(tenant, List.of(doc.datasetId())));
    clearDatasetMarker();
    // 허용으로 돌아와 재임베딩 — 새 순번으로 같은 점 id 에 덮어쓴다.
    indexDataset(axis(1024, 1));

    // 늦게 도착한 삭제를 반영해도 새 벡터는 남는다.
    assertThat(drain()).isEqualTo(1);
    assertThat(semantic(axis(1024, 1))).containsExactly(doc.datasetId());
    assertThat(pending()).isEmpty();

    // 대조군: 다시 쓰지 않으면 같은 삭제가 벡터를 지운다. 키워드는 남는다(dense 만 지우기).
    in(() -> datasetIndex.deleteDense(tenant, List.of(doc.datasetId())));
    assertThat(drain()).isEqualTo(1);
    assertThat(points(EmbeddingDimension.D1024.datasetCollection(), datasetPoint())).isEmpty();
    assertThat(keyword()).containsExactly(doc.datasetId());
  }

  @Test
  @DisplayName("문서 재적재 — 같은 문서 id 의 새 청크는 먼저 쌓인 문서 삭제에 지워지지 않는다")
  void reingestKeepsNewChunks() {
    long old = indexChunk("소방 점검 옛 판");

    // 재적재 시작: 문서 색인 삭제를 쌓고 옛 청크 행을 지운다(DocumentIngestionService 순서). 아직 반영하지 않는다.
    in(
        () -> {
          chunkIndex.deleteAllOfDocument(tenant, doc.fileId());
          return null;
        });
    TenantContext.runScoped(tenant, () -> chunks.deleteByDocumentFileId(doc.fileId()));
    long fresh = indexChunk("소방 점검 새 판");

    assertThat(drain()).isEqualTo(1);
    assertThat(chunkKeyword()).containsExactly(fresh);
    assertThat(chunkSemantic()).containsExactly(fresh);
    assertThat(points(EmbeddingDimension.D1024.chunkCollection(), chunkPoint(old))).isEmpty();
    assertThat(points(QdrantCollectionSpec.CHUNKS_KEYWORD_COLLECTION, chunkPoint(old))).isEmpty();
  }

  @Test
  @DisplayName("순번이 없는 점(순번을 싣기 전에 쓴 점)도 워커가 지운다")
  void pointWithoutSeqIsDeleted() throws Exception {
    upsertRaw(
        EmbeddingDimension.D1024.datasetCollection(),
        datasetPoint(),
        Map.of(
            QdrantCollectionSpec.TENANT_ID, value(Long.toString(tenant)),
            QdrantCollectionSpec.DATASET_ID, value(doc.datasetId()),
            QdrantCollectionSpec.MODEL, value(S1024.model())));

    in(
        () -> {
          datasetIndex.deleteAll(tenant, List.of(doc.datasetId()));
          return null;
        });
    assertThat(drain()).isEqualTo(1);
    assertThat(points(EmbeddingDimension.D1024.datasetCollection(), datasetPoint())).isEmpty();
  }

  @Test
  @DisplayName("아웃박스 유실 — 대조 스윕이 표시가 가리키지 않는 점과 부모 없는 점을 지우고, 키워드는 원문이 있으면 남긴다")
  void sweepRemovesStorePointsWithoutMarkers() throws Exception {
    indexDataset(axis(1024, 0));
    // 불허로 표시를 비웠는데 아웃박스 행이 사라졌다(유실).
    clearDatasetMarker();
    // 이미 지운 데이터셋의 점(부모 없음).
    long gone = 987_654_321L;
    PointId goneId = PointIdFactory.id(QdrantPointIds.dataset(tenant, gone));
    upsertRaw(
        EmbeddingDimension.D1024.datasetCollection(),
        goneId,
        Map.of(
            QdrantCollectionSpec.TENANT_ID, value(Long.toString(tenant)),
            QdrantCollectionSpec.DATASET_ID, value(gone),
            QdrantCollectionSpec.MODEL, value(S1024.model()),
            QdrantCollectionSpec.SEQ, value(1L)));

    long fence = TenantContext.runScopedGet(tenant, outbox::nextSeq);
    QdrantReconcileSweeper.Result r =
        TenantContext.runScopedGet(tenant, () -> sweeper.reconcileCurrentTenant(fence));

    assertThat(r.removedPoints()).isEqualTo(2);
    assertThat(points(EmbeddingDimension.D1024.datasetCollection(), datasetPoint())).isEmpty();
    assertThat(points(EmbeddingDimension.D1024.datasetCollection(), goneId)).isEmpty();
    // 키워드 점은 원문 행이 있어 남는다(키워드는 정리 대상이 아니다).
    assertThat(keyword()).containsExactly(doc.datasetId());
  }

  @Test
  @DisplayName("울타리 — 지난 회차 뒤에 쓴 점은 아직 표시가 없어도 지우지 않는다(쓰기 트랜잭션이 커밋 전일 수 있다)")
  void sweepSparesPointsWrittenAfterFence() {
    long fence = TenantContext.runScopedGet(tenant, outbox::nextSeq);
    // 표시를 채우기 전의 쓰기(커밋 전 트랜잭션과 같은 모양).
    TenantContext.runScoped(
        tenant,
        () -> datasetIndex.upsertDense(S1024, List.of(doc.datasetId()), List.of(axis(1024, 0))));

    QdrantReconcileSweeper.Result r =
        TenantContext.runScopedGet(tenant, () -> sweeper.reconcileCurrentTenant(fence));

    assertThat(r.removedPoints()).isZero();
    assertThat(points(EmbeddingDimension.D1024.datasetCollection(), datasetPoint())).hasSize(1);
  }

  @Test
  @DisplayName("표시는 찼는데 점이 없거나 모델이 다르면 대조 스윕이 표시를 비워 백로그로 돌린다")
  void sweepClearsMarkersWithoutPoints() {
    long chunk =
        TenantContext.runScopedGet(
                tenant,
                () ->
                    chunks.insertChunks(
                        doc.fileId(), doc.datasetId(), List.of(new Chunk(0, "소방 점검", 1))))
            .get(0);
    // 점은 쓰지 않고 표시만 채운다.
    inTenantFixture(
        tenant,
        () -> {
          dsl.execute(
              "UPDATE dataset_embedding SET embedded_dim = 1024, embedded_model = 'bge-m3',"
                  + " keyword_marker = 'bigram-v1:x' WHERE dataset_id = ?",
              doc.datasetId());
          dsl.execute(
              "UPDATE document_chunk SET embedded_dim = 1024, embedded_model = 'bge-m3',"
                  + " keyword_marker = 'bigram-v1:y' WHERE id = ?",
              chunk);
        });

    QdrantReconcileSweeper.Result r =
        TenantContext.runScopedGet(tenant, () -> sweeper.reconcileCurrentTenant(null));

    assertThat(r.clearedMarkers()).isEqualTo(4);
    assertThat(datasetMarker())
        .isEqualTo(new Marker(doc.datasetId(), doc.datasetId(), null, null, false));
    assertThat(TenantContext.runScopedGet(tenant, () -> markers.findChunkMarkers(List.of(chunk))))
        .containsExactly(new Marker(chunk, doc.datasetId(), null, null, false));

    // 점이 있고 모델도 같으면 표시를 두고, 모델이 다르면 비운다.
    indexDataset(axis(1024, 0));
    assertThat(
            TenantContext.runScopedGet(tenant, () -> sweeper.reconcileCurrentTenant(null))
                .clearedMarkers())
        .isZero();
    inTenantFixture(
        tenant,
        () ->
            dsl.execute(
                "UPDATE dataset_embedding SET embedded_model = 'other' WHERE dataset_id = ?",
                doc.datasetId()));
    assertThat(
            TenantContext.runScopedGet(tenant, () -> sweeper.reconcileCurrentTenant(null))
                .clearedMarkers())
        .isEqualTo(1);
    assertThat(datasetMarker().dim()).isNull();
  }

  @Test
  @DisplayName("낱말이 없는 원문 — 키워드 점이 없어도 표시를 비우지 않는다(키워드 백로그와 서로 되돌리는 무한 반복 방지)")
  void sweepKeepsKeywordMarkerOfTermlessText() {
    long chunk =
        TenantContext.runScopedGet(
                tenant,
                () ->
                    chunks.insertChunks(
                        doc.fileId(), doc.datasetId(), List.of(new Chunk(0, "-----", 1))))
            .get(0);
    // 키워드 쓰기는 낱말이 없으면 점을 지우고 표시만 채운다(KeywordIndexService 와 같은 결과).
    ChunkRef ref = new ChunkRef(chunk, doc.datasetId(), doc.fileId());
    TenantContext.runScoped(
        tenant, () -> chunkIndex.upsertKeyword(tenant, List.of(KeywordSource.chunk(ref, "-----"))));
    inTenantFixture(
        tenant,
        () ->
            dsl.execute(
                "UPDATE document_chunk SET keyword_marker = 'bigram-v1:z' WHERE id = ?", chunk));
    assertThat(points(QdrantCollectionSpec.CHUNKS_KEYWORD_COLLECTION, chunkPoint(chunk))).isEmpty();

    assertThat(
            TenantContext.runScopedGet(tenant, () -> sweeper.reconcileCurrentTenant(null))
                .clearedMarkers())
        .isZero();
    assertThat(TenantContext.runScopedGet(tenant, () -> markers.findChunkMarkers(List.of(chunk))))
        .extracting(Marker::keyword)
        .containsExactly(true);
  }

  @Test
  @DisplayName("저장소가 내려가 있어도 삭제 쌓기는 성공하고, 반영 실패는 시도 수·오류와 함께 행에 남는다")
  void storeDownKeepsOutboxRow() {
    indexDataset(axis(1024, 0));
    QdrantProperties dead =
        new QdrantProperties("http://127.0.0.1:1", "x", 2, true, Duration.ofMillis(300));
    try (QdrantClient deadClient = QdrantClientConfig.create(dead)) {
      QdrantDatasetVectorIndex broken =
          new QdrantDatasetVectorIndex(deadClient, dead, candidates, outbox, markers);
      // 지울 수는 표시로 센다 — 저장소에 묻지 않는다.
      assertThat(in(() -> broken.deleteDense(tenant, List.of(doc.datasetId())))).isEqualTo(1);

      VectorOutboxWorker brokenWorker =
          new VectorOutboxWorker(null, outbox, new QdrantOutboxApplier(deadClient, dead), false);
      assertThat(TenantContext.runScopedGet(tenant, brokenWorker::drainCurrentTenant)).isZero();
    }
    List<Entry> rows = pending();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).kind()).isEqualTo(VectorOutboxKind.DATASET_DENSE);
    assertThat(rows.get(0).attempts()).isEqualTo(1);
    assertThat(
            inTenantFixture(
                tenant,
                () ->
                    dsl.fetchOne("SELECT last_error FROM vector_outbox WHERE tenant_id = ?", tenant)
                        .get(0, String.class)))
        .isNotBlank();

    // 저장소가 돌아오면 다음 회차가 반영한다.
    assertThat(drain()).isEqualTo(1);
    assertThat(points(EmbeddingDimension.D1024.datasetCollection(), datasetPoint())).isEmpty();
  }

  // ---- 도우미 ----

  /** 테넌트 범위에서 부른다(포트 삭제는 현재 테넌트에 쌓인다). */
  private <T> T in(Supplier<T> call) {
    return TenantContext.runScopedGet(tenant, call);
  }

  private int drain() {
    return TenantContext.runScopedGet(tenant, worker::drainCurrentTenant);
  }

  private List<Entry> pending() {
    return TenantContext.runScopedGet(tenant, () -> outbox.findPending(100));
  }

  /** 데이터셋 dense·키워드를 쓰고 표시 열을 채운다(리포지토리 upsertEmbeddings 와 같은 순서). */
  private void indexDataset(float[] v) {
    TenantContext.runScoped(
        tenant,
        () -> {
          datasetIndex.upsertDense(S1024, List.of(doc.datasetId()), List.of(v));
          datasetIndex.upsertKeyword(
              tenant, List.of(KeywordSource.dataset(doc.datasetId(), "화재 통계")));
        });
    inTenantFixture(
        tenant,
        () ->
            dsl.execute(
                "UPDATE dataset_embedding SET embedded_dim = 1024, embedded_model = 'bge-m3'"
                    + " WHERE dataset_id = ?",
                doc.datasetId()));
  }

  private void clearDatasetMarker() {
    inTenantFixture(
        tenant,
        () ->
            dsl.execute(
                "UPDATE dataset_embedding SET embedded_dim = NULL, embedded_model = NULL"
                    + " WHERE dataset_id = ?",
                doc.datasetId()));
  }

  private Marker datasetMarker() {
    return TenantContext.runScopedGet(
            tenant, () -> markers.findDatasetMarkers(List.of(doc.datasetId())))
        .get(0);
  }

  /** 청크 행을 커밋하고 dense·키워드를 쓴 뒤 표시를 채운다(청크 적재 순서). */
  private long indexChunk(String content) {
    long id =
        TenantContext.runScopedGet(
                tenant,
                () ->
                    chunks.insertChunks(
                        doc.fileId(), doc.datasetId(), List.of(new Chunk(0, content, 1))))
            .get(0);
    ChunkRef ref = new ChunkRef(id, doc.datasetId(), doc.fileId());
    TenantContext.runScoped(
        tenant,
        () -> {
          chunkIndex.upsertDense(S1024, List.of(ref), List.of(axis(1024, 3)));
          chunkIndex.upsertKeyword(tenant, List.of(KeywordSource.chunk(ref, content)));
        });
    inTenantFixture(
        tenant,
        () ->
            dsl.execute(
                "UPDATE document_chunk SET embedded_dim = 1024, embedded_model = 'bge-m3' WHERE id = ?",
                id));
    return id;
  }

  private PointId datasetPoint() {
    return PointIdFactory.id(QdrantPointIds.dataset(tenant, doc.datasetId()));
  }

  private PointId chunkPoint(long chunkId) {
    return PointIdFactory.id(QdrantPointIds.chunk(tenant, chunkId));
  }

  private List<RetrievedPoint> points(String collection, PointId id) {
    try {
      return client
          .retrieveAsync(
              GetPoints.newBuilder().setCollectionName(collection).addIds(id).build(),
              Duration.ofSeconds(30))
          .get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 순번을 마음대로 정한(또는 없는) dense 점을 바로 쓴다. */
  private void upsertRaw(
      String collection, PointId id, Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload)
      throws Exception {
    client
        .upsertAsync(
            collection,
            List.of(
                PointStruct.newBuilder()
                    .setId(id)
                    .setVectors(
                        VectorsFactory.namedVectors(
                            Map.of(
                                QdrantCollectionSpec.DENSE, VectorFactory.vector(axis(1024, 0)))))
                    .putAllPayload(payload)
                    .build()))
        .get(30, TimeUnit.SECONDS);
  }

  private List<Long> semantic(float[] q) {
    return TenantContext.runScopedGet(tenant, () -> datasetIndex.semantic(S1024, q, null, 10, ALL))
        .stream()
        .map(DatasetSearchHit::datasetId)
        .toList();
  }

  private List<Long> keyword() {
    return TenantContext.runScopedGet(tenant, () -> datasetIndex.keyword("화재", null, 10, ALL))
        .stream()
        .map(DatasetSearchHit::datasetId)
        .toList();
  }

  private List<Long> chunkKeyword() {
    return TenantContext.runScopedGet(tenant, () -> chunkIndex.keyword("소방 점검", null, 10, ALL))
        .stream()
        .map(DocumentSearchHit::chunkId)
        .toList();
  }

  private List<Long> chunkSemantic() {
    return TenantContext.runScopedGet(
            tenant, () -> chunkIndex.semantic(S1024, axis(1024, 3), null, 10, ALL))
        .stream()
        .map(DocumentSearchHit::chunkId)
        .toList();
  }
}
