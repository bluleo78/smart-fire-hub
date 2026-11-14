package com.smartfirehub.vectorstore.qdrant;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.dataset.repository.mybatis.DatasetEmbeddingMapper;
import com.smartfirehub.dataset.repository.mybatis.MyBatisDatasetEmbeddingRepository;
import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.document.repository.mybatis.DocumentChunkMapper;
import com.smartfirehub.document.repository.mybatis.MyBatisDocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.SourceHash;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import com.smartfirehub.user.repository.AccessFixtures;
import com.smartfirehub.user.repository.TenantPairContractBase;
import com.smartfirehub.user.repository.TiberoAccessFixtures;
import com.smartfirehub.vectorstore.KeywordIndexService;
import com.smartfirehub.vectorstore.SearchCandidateRepository;
import com.smartfirehub.vectorstore.VectorMarkerRepository;
import com.smartfirehub.vectorstore.VectorOutboxRepository;
import com.smartfirehub.vectorstore.repository.mybatis.MyBatisSearchCandidateRepository;
import com.smartfirehub.vectorstore.repository.mybatis.MyBatisVectorMarkerRepository;
import com.smartfirehub.vectorstore.repository.mybatis.MyBatisVectorOutboxRepository;
import com.smartfirehub.vectorstore.repository.mybatis.SearchCandidateMapper;
import com.smartfirehub.vectorstore.repository.mybatis.VectorMarkerMapper;
import com.smartfirehub.vectorstore.repository.mybatis.VectorOutboxMapper;
import io.qdrant.client.QdrantClient;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tibero 플랫폼 × qdrant 조합 끝-끝(WD-720 완료 기준). 플랫폼 리포지토리는 MyBatis 구현 + Tibero 실서버(런타임 계정·DBMS_RLS),
 * 색인은 Testcontainers Qdrant 다. 운영 조합과 같게 MyBatis 리포지토리가 Qdrant 포트를 품는다.
 *
 * <p>Tibero 에서 처음 도는 것: 벡터를 쓰고 표시 열·원문 해시를 맞추는 MyBatis 문장, 키워드 표시({@code ||} 로 규칙과 해시 잇기), 생성 키로 받은
 * 청크 id 를 Qdrant 점에 싣기, 가시성 2단계 후보 확인(MyBatis 가시성 조각). 두 테넌트에 같은 원문을 두어 교차 0건을 함께 본다.
 */
@TiberoLive
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoQdrantSearchLiveTest extends TenantPairContractBase {

  private static final EmbeddingSpace SPACE =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");
  private static final DatasetVisibility ALL = DatasetVisibility.UNRESTRICTED;

  private TiberoPlatformSchema poc;
  private QdrantProperties props;
  private QdrantClient client;
  private QdrantDatasetVectorIndex datasetIndex;
  private QdrantChunkVectorIndex chunkIndex;
  private DatasetEmbeddingRepository datasets;
  private DocumentChunkRepository chunks;
  private KeywordIndexService keywordIndex;

  @BeforeAll
  void wire() {
    poc = TiberoPlatformSchema.shared();
    props = QdrantTestContainer.properties(2, true);
    client = QdrantClientConfig.create(props);
    SearchCandidateRepository candidates =
        poc.transactional(
            SearchCandidateRepository.class,
            new MyBatisSearchCandidateRepository(poc.mapper(SearchCandidateMapper.class)));
    // 아웃박스·표시 열(WD-721)도 MyBatis 판 + Tibero 실서버로 묶는다.
    VectorOutboxRepository outbox =
        poc.transactional(
            VectorOutboxRepository.class,
            new MyBatisVectorOutboxRepository(poc.mapper(VectorOutboxMapper.class)));
    VectorMarkerRepository markers =
        poc.transactional(
            VectorMarkerRepository.class,
            new MyBatisVectorMarkerRepository(poc.mapper(VectorMarkerMapper.class)));
    datasetIndex = new QdrantDatasetVectorIndex(client, props, candidates, outbox, markers);
    chunkIndex = new QdrantChunkVectorIndex(client, props, candidates, outbox, markers);
    datasets =
        poc.transactional(
            DatasetEmbeddingRepository.class,
            new MyBatisDatasetEmbeddingRepository(
                poc.mapper(DatasetEmbeddingMapper.class), datasetIndex));
    chunks =
        poc.transactional(
            DocumentChunkRepository.class,
            new MyBatisDocumentChunkRepository(poc.mapper(DocumentChunkMapper.class), chunkIndex));
    keywordIndex =
        new KeywordIndexService(
            datasetIndex, chunkIndex, datasets, chunks, poc.tx().getTransactionManager());
  }

  @AfterAll
  void closeClient() {
    client.close();
  }

  @BeforeEach
  void emptyStore() throws Exception {
    QdrantTestContainer.deleteAllCollections(client);
    new QdrantVectorStoreLifecycle(client, props).prepare();
  }

  @Override
  protected AccessFixtures newFixtures() {
    return new TiberoAccessFixtures(poc);
  }

  /** 운영 경로처럼: 원문 저장 → dense 쓰기(표시 열) — 테넌트를 먼저 세우고 프록시가 트랜잭션을 연다. */
  private void index(long tenant, long datasetId, String text) {
    TenantContext.runScoped(
        tenant,
        () -> {
          datasets.upsertSourceText(datasetId, text);
          datasets.upsertEmbedding(SPACE, datasetId, axis(1024, 0), SourceHash.of(text));
        });
  }

  @Test
  void datasetAndChunkSearchWorkOnTiberoPlatform_withoutCrossingTenants() {
    long a = fx.createDataset(tenantA, prefix + " A");
    long b = fx.createDataset(tenantB, prefix + " B");
    index(tenantA, a, "화재 통계 자료");
    index(tenantB, b, "화재 통계 자료");
    long fileA = fx.createDocumentFile(tenantA, a, "a.txt", "COMPLETED");
    List<Long> chunkIds =
        TenantContext.runScopedGet(
            tenantA, () -> chunks.insertChunks(fileA, a, List.of(new Chunk(0, "화재 예방 점검", 1))));
    TenantContext.runScoped(
        tenantA, () -> chunks.upsertEmbeddings(SPACE, chunkIds, List.of(axis(1024, 3))));

    // 키워드는 백로그 스윕으로 쓴다(표시 열 SQL·|| 잇기가 Tibero 에서 돈다). 두 번째 스윕은 할 일이 없다.
    assertThat(TenantContext.runScopedGet(tenantA, keywordIndex::sweepCurrentTenant)).isEqualTo(2);
    assertThat(TenantContext.runScopedGet(tenantA, keywordIndex::sweepCurrentTenant)).isZero();
    assertThat(TenantContext.runScopedGet(tenantB, keywordIndex::sweepCurrentTenant)).isEqualTo(1);

    assertThat(datasetIds(tenantA, true)).containsExactly(a);
    assertThat(datasetIds(tenantA, false)).containsExactly(a);
    assertThat(datasetIds(tenantB, true)).containsExactly(b);
    assertThat(datasetIds(tenantB, false)).containsExactly(b);

    assertThat(
            TenantContext.runScopedGet(
                    tenantA, () -> chunkIndex.semantic(SPACE, axis(1024, 3), null, 5, ALL))
                .stream()
                .map(DocumentSearchHit::chunkId))
        .containsExactly(chunkIds.get(0));
    assertThat(
            TenantContext.runScopedGet(tenantA, () -> chunkIndex.keyword("화재 예방", null, 5, ALL))
                .stream()
                .map(DocumentSearchHit::content))
        .containsExactly("화재 예방 점검");
    assertThat(TenantContext.runScopedGet(tenantB, () -> chunkIndex.keyword("화재 예방", null, 5, ALL)))
        .isEmpty();

    // 원문 해시 판정이 Tibero 에서도 돈다 — 원문을 바꾸면 벡터 백로그와 키워드 백로그가 함께 잡힌다.
    TenantContext.runScoped(tenantA, () -> datasets.upsertSourceText(a, "구조 출동 기록"));
    assertThat(
            TenantContext.runScopedGet(
                tenantA,
                () ->
                    datasets.countMissing(
                        SPACE,
                        new com.smartfirehub.securitylevel.ai.EmbeddingAllowance(
                            com.smartfirehub.securitylevel.access.ProviderHosting.SELF_HOSTED))))
        .isEqualTo(1);
    assertThat(TenantContext.runScopedGet(tenantA, keywordIndex::sweepCurrentTenant)).isEqualTo(1);
    assertThat(
            TenantContext.runScopedGet(tenantA, () -> datasetIndex.keyword("구조", null, 5, ALL))
                .stream()
                .map(DatasetSearchHit::datasetId))
        .containsExactly(a);
  }

  private List<Long> datasetIds(long tenant, boolean semantic) {
    return TenantContext.runScopedGet(
            tenant,
            () ->
                semantic
                    ? datasetIndex.semantic(SPACE, axis(1024, 0), null, 5, ALL)
                    : datasetIndex.keyword("화재", null, 5, ALL))
        .stream()
        .map(DatasetSearchHit::datasetId)
        .toList();
  }
}
