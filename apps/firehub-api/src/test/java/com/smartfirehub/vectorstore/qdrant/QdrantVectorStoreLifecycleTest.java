package com.smartfirehub.vectorstore.qdrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.embedding.EmbeddingDimension;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.Memory;
import io.qdrant.client.grpc.Collections.Modifier;
import io.qdrant.client.grpc.Collections.PayloadSchemaInfo;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Qdrant 기동 준비 테스트(WD-719 완료 기준) — 실제 Qdrant v1.20(Testcontainers)에 붙는다.
 *
 * <ul>
 *   <li>빈 저장소에 기동 → 컬렉션·payload 색인이 생긴다.
 *   <li>재기동(새 인스턴스) → 대조를 통과한다.
 *   <li>구성을 바꾸고 기동 → 멈춘다.
 * </ul>
 *
 * 앱 전체가 아닌 Lifecycle 빈 단위로 본다. 벡터 저장소 {@code qdrant} 는 색인 구현(WD-720)이 들어올 때까지 기동을 거부하기 때문이다.
 */
class QdrantVectorStoreLifecycleTest {

  private static QdrantClient client;

  @BeforeAll
  static void startQdrant() {
    client = QdrantClientConfig.create(QdrantTestContainer.properties(6, true));
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  @BeforeEach
  void emptyStore() throws Exception {
    QdrantTestContainer.deleteAllCollections(client);
  }

  @Test
  void createsAllCollectionsWithExpectedConfigOnEmptyStore() throws Exception {
    lifecycle(6, true).prepare();

    assertThat(client.listCollectionsAsync().get(30, TimeUnit.SECONDS))
        .containsExactlyInAnyOrder(
            "fh_datasets_1024",
            "fh_datasets_1536",
            "fh_chunks_1024",
            "fh_chunks_1536",
            "fh_rows_kw");

    CollectionInfo info = info(EmbeddingDimension.D1024.datasetCollection());
    assertThat(info.getConfig().getParams().getShardNumber()).isEqualTo(6);
    VectorParams dense =
        info.getConfig().getParams().getVectorsConfig().getParamsMap().getMapMap().get("dense");
    assertThat(dense.getSize()).isEqualTo(1024);
    assertThat(dense.getDistance()).isEqualTo(Distance.Cosine);
    assertThat(dense.getMemory()).isEqualTo(Memory.Cold);
    assertThat(info.getConfig().getQuantizationConfig().getScalar().getMemory())
        .isEqualTo(Memory.Pinned);
    assertThat(
            info.getConfig()
                .getParams()
                .getSparseVectorsConfig()
                .getMapMap()
                .get("kw_bigram")
                .getModifier())
        .isEqualTo(Modifier.Idf);

    // tenant_id 는 키워드형 + is_tenant 가 실제로 저장돼야 한다(정수형이면 Qdrant 가 조용히 버린다)
    Map<String, PayloadSchemaInfo> schema = info.getPayloadSchemaMap();
    assertThat(schema.keySet())
        .containsExactlyInAnyOrder("tenant_id", "dataset_id", "model", "seq");
    assertThat(schema.get("tenant_id").getDataType()).isEqualTo(PayloadSchemaType.Keyword);
    assertThat(schema.get("tenant_id").getParams().getKeywordIndexParams().getIsTenant()).isTrue();

    CollectionInfo kw = info("fh_rows_kw");
    assertThat(kw.getConfig().getParams().getVectorsConfig().getParamsMap().getMapMap()).isEmpty();
    assertThat(kw.getConfig().hasQuantizationConfig()).isFalse();
    assertThat(kw.getPayloadSchemaMap().keySet())
        .contains("row_id", "chunk_no", "source_hash", "generation")
        .doesNotContain("model");
  }

  @Test
  void restartWithSameConfigPassesVerification() {
    lifecycle(6, true).prepare();
    // 새 인스턴스 = 재기동(프로세스 안 캐시 없음)
    lifecycle(6, true).prepare();
  }

  @Test
  void changedShardNumberStopsStartup() {
    lifecycle(6, true).prepare();
    assertThatThrownBy(() -> lifecycle(3, true).prepare())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("샤드 수 6 ≠ 설정 3");
  }

  @Test
  void changedQuantizationStopsStartup() {
    lifecycle(6, true).prepare();
    assertThatThrownBy(() -> lifecycle(6, false).prepare())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("양자화");
  }

  @Test
  void foreignCollectionWithSameNameStopsStartup() throws Exception {
    // 누가 같은 이름으로 다른 구성(차원·이름 없는 벡터·메타 없음)의 컬렉션을 만들어 둔 경우
    client
        .createCollectionAsync(
            "fh_datasets_1024",
            VectorParams.newBuilder().setSize(768).setDistance(Distance.Dot).build())
        .get(30, TimeUnit.SECONDS);
    assertThatThrownBy(() -> lifecycle(6, true).prepare())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fh_datasets_1024")
        .hasMessageContaining("스키마 버전 없음");
  }

  @Test
  void missingPayloadIndexesAreFilledOnNextStartup() throws Exception {
    // 컬렉션만 만들고 색인을 만들기 전에 죽은 상황
    QdrantProperties props = QdrantTestContainer.properties(6, true);
    QdrantCollectionSpec spec = QdrantCollectionSpec.chunks(EmbeddingDimension.D1536);
    CreateCollection req = spec.toCreateRequest(props);
    client.createCollectionAsync(req).get(30, TimeUnit.SECONDS);
    assertThat(info(spec.name()).getPayloadSchemaMap()).isEmpty();

    lifecycle(6, true).prepare();

    assertThat(info(spec.name()).getPayloadSchemaMap().keySet())
        .containsExactlyInAnyOrder("tenant_id", "dataset_id", "document_id", "model", "seq");
  }

  @Test
  void rowCollectionIsCreatedOnDemand() throws Exception {
    QdrantVectorStoreLifecycle lc = lifecycle(6, true);
    lc.prepare();
    assertThat(lc.ensureRowCollection(768)).isEqualTo("fh_rows_768");
    assertThat(lc.ensureRowCollection(768)).isEqualTo("fh_rows_768"); // 두 번째는 확인만
    assertThat(info("fh_rows_768").getPayloadSchemaMap().keySet())
        .containsExactlyInAnyOrder(
            "tenant_id",
            "dataset_id",
            "model",
            "seq",
            "row_id",
            "chunk_no",
            "source_hash",
            "generation");
    assertThatThrownBy(() -> lc.ensureRowCollection(1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void availableReflectsServerHealth() {
    assertThat(lifecycle(6, true).available()).isTrue();
    QdrantProperties down =
        new QdrantProperties("http://127.0.0.1:1", null, 6, true, java.time.Duration.ofSeconds(2));
    try (QdrantClient dead = QdrantClientConfig.create(down)) {
      assertThat(new QdrantVectorStoreLifecycle(dead, down).available()).isFalse();
    }
  }

  /**
   * 하네스 위에서 쓰기·검색이 한 바퀴 도는지 본다(WD-720 이 이 위에 선다).
   *
   * <ul>
   *   <li>sparse id 가 2³¹ 을 넘어 Java int 로는 음수인 경우(「소방」 = 3486565070)도 gRPC 가 uint32 로 저장·검색한다.
   *   <li>키워드형 tenant_id 필터가 다른 테넌트를 거른다.
   * </ul>
   */
  @Test
  void sparseIdsAboveInt32RoundTripAndTenantFilterApplies() throws Exception {
    lifecycle(6, true).prepare();
    String collection = EmbeddingDimension.D1024.datasetCollection();
    BigramSparseEncoder.SparseTerms doc =
        BigramSparseEncoder.document("소방안전관리자", BigramSparseEncoder.Profile.DATASET);
    float[] dense = new float[1024];
    dense[0] = 1f;

    client
        .upsertAsync(
            collection,
            List.of(
                PointStruct.newBuilder()
                    .setId(PointIdFactory.id(QdrantPointIds.dataset(7, 42)))
                    .setVectors(
                        VectorsFactory.namedVectors(
                            Map.of(
                                QdrantCollectionSpec.DENSE,
                                VectorFactory.vector(dense),
                                QdrantCollectionSpec.KW_BIGRAM,
                                VectorFactory.vector(doc.valueList(), doc.indexList()))))
                    .putAllPayload(
                        Map.of(
                            QdrantCollectionSpec.TENANT_ID, ValueFactory.value("7"),
                            QdrantCollectionSpec.DATASET_ID, ValueFactory.value(42L)))
                    .build()))
        .get(30, TimeUnit.SECONDS);

    // 저장된 sparse id 를 부호 없는 값으로 읽으면 생성기가 낸 값과 같다
    RetrievedPoint stored =
        client
            .retrieveAsync(
                collection,
                List.of(PointIdFactory.id(QdrantPointIds.dataset(7, 42))),
                false,
                true,
                null)
            .get(30, TimeUnit.SECONDS)
            .get(0);
    List<Long> storedIds =
        stored
            .getVectors()
            .getVectors()
            .getVectorsMap()
            .get(QdrantCollectionSpec.KW_BIGRAM)
            .getSparse()
            .getIndicesList()
            .stream()
            .map(Integer::toUnsignedLong)
            .sorted()
            .toList();
    List<Long> expectedIds =
        java.util.Arrays.stream(doc.indices())
            .mapToLong(Integer::toUnsignedLong)
            .sorted()
            .boxed()
            .toList();
    assertThat(storedIds).isEqualTo(expectedIds).contains(3486565070L);

    BigramSparseEncoder.SparseTerms q = BigramSparseEncoder.query("소방");
    assertThat(keywordHits(collection, q, "7")).containsExactly(QdrantPointIds.dataset(7, 42));
    assertThat(keywordHits(collection, q, "8")).isEmpty();
  }

  private static List<java.util.UUID> keywordHits(
      String collection, BigramSparseEncoder.SparseTerms q, String tenant) throws Exception {
    return client
        .queryAsync(
            QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setUsing(QdrantCollectionSpec.KW_BIGRAM)
                .setQuery(QueryFactory.nearest(q.valueList(), q.indexList()))
                .setFilter(
                    Filter.newBuilder()
                        .addMust(
                            ConditionFactory.matchKeyword(QdrantCollectionSpec.TENANT_ID, tenant)))
                .setLimit(10)
                .build())
        .get(30, TimeUnit.SECONDS)
        .stream()
        .map(p -> java.util.UUID.fromString(p.getId().getUuid()))
        .toList();
  }

  private static QdrantVectorStoreLifecycle lifecycle(int shards, boolean quantization) {
    return new QdrantVectorStoreLifecycle(
        client, QdrantTestContainer.properties(shards, quantization));
  }

  private static CollectionInfo info(String name) throws Exception {
    return client.getCollectionInfoAsync(name).get(30, TimeUnit.SECONDS);
  }
}
