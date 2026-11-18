package com.smartfirehub.vectorstore.qdrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.rowsearch.IndexRef;
import com.smartfirehub.dataset.rowsearch.IndexedRow;
import com.smartfirehub.dataset.rowsearch.RowHit;
import com.smartfirehub.dataset.rowsearch.RowQueryFilter;
import com.smartfirehub.dataset.rowsearch.SearchSourceReader;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.vectorstore.repository.jooq.JooqVectorOutboxRepository;
import com.smartfirehub.vectorstore.tenant.TenantVectorStore;
import com.smartfirehub.vectorstore.tenant.TenantVectorStoreRepository;
import com.smartfirehub.vectorstore.tenant.VectorStoreConnectionTestResult;
import io.qdrant.client.QdrantClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.qdrant.QdrantContainer;

/**
 * 테넌트 전용 Qdrant(WD-742) — 한 설치본에서 공유 Qdrant 테넌트와 전용 Qdrant 테넌트가 함께 행 검색하고, 전용 인스턴스가 내려가도 공유 테넌트는
 * 그대로인지 본다. 전용 인스턴스는 운영처럼 API 키·JWT RBAC 를 켠 컨테이너를 따로 띄운다.
 */
class QdrantDedicatedRowTargetTest extends IntegrationTestBase {

  private static final long DS = 990_742L;
  private static final int DIM = 8;
  private static final String DEDICATED_KEY = "dedicated-key";

  /** 전용 Qdrant 를 쓰는 테넌트(공유 test DB 의 다른 테넌트와 겹치지 않는 가짜 id — 행 색인은 DB 를 거치지 않는다). */
  private static final long DEDICATED_TENANT = 990_742_001L;

  @Autowired private SearchSourceReader reader;
  @Autowired private JooqVectorOutboxRepository outbox;
  @Autowired private EncryptionService encryption;

  private QdrantContainer dedicatedContainer;
  private QdrantProperties props;
  private QdrantClient sharedClient;
  private QdrantRowClients rowClients;
  private QdrantRowTargets targets;
  private QdrantRowSearchIndex index;
  private final Map<Long, TenantVectorStore> rows = new ConcurrentHashMap<>();

  /** 메모리 저장소 — 대상 해석만 보면 되므로 DB 를 거치지 않는다(저장소 계약은 TenantVectorStoreRepositoryContractTest). */
  private final TenantVectorStoreRepository repository =
      new TenantVectorStoreRepository() {
        @Override
        public void insert(TenantVectorStore row) {
          rows.put(row.tenantId(), row);
        }

        @Override
        public Optional<TenantVectorStore> find(long tenantId) {
          return Optional.ofNullable(rows.get(tenantId));
        }

        @Override
        public List<Long> findTenantIds() {
          return new ArrayList<>(rows.keySet());
        }
      };

  @BeforeEach
  void setUp() throws Exception {
    dedicatedContainer =
        new QdrantContainer(QdrantTestContainer.IMAGE)
            .withApiKey(DEDICATED_KEY)
            // 하네스와 같은 까닭으로 저장소를 메모리에 둔다(WD-758 — 느린 디스크의 동기 쓰기 회피).
            .withTmpFs(QdrantTestContainer.STORAGE_TMPFS)
            .withEnv("QDRANT__SERVICE__JWT_RBAC", "true")
            .withStartupAttempts(3);
    dedicatedContainer.start();
    props = QdrantTestContainer.properties(1, false);
    sharedClient = QdrantClientConfig.create(props);
    QdrantTestContainer.deleteAllCollections(sharedClient);
    QdrantVectorStoreLifecycle lifecycle = new QdrantVectorStoreLifecycle(sharedClient, props);
    lifecycle.prepare();
    rowClients = new QdrantRowClients(props);
    targets = new QdrantRowTargets(props, lifecycle, rowClients, repository, encryption);
    repository.insert(
        new TenantVectorStore(
            DEDICATED_TENANT,
            dedicatedContainer.getHost(),
            dedicatedContainer.getGrpcPort(),
            false,
            encryption.encrypt(DEDICATED_KEY)));
    index =
        new QdrantRowSearchIndex(
            rowClients,
            lifecycle,
            targets,
            props,
            outbox,
            reader,
            100_000,
            60_000,
            System::currentTimeMillis,
            100);
  }

  @AfterEach
  void tearDown() throws Exception {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    targets.destroy();
    rowClients.destroy();
    sharedClient.close();
    if (dedicatedContainer.isRunning()) dedicatedContainer.stop();
  }

  private static float[] axis(int a) {
    float[] v = new float[DIM];
    v[a] = 1f;
    return v;
  }

  /** 테넌트 문맥 안에서 색인을 만들고 두 행을 넣는다. */
  private IndexRef indexTwoRows(long tenantId) {
    TenantContext.set(tenantId);
    IndexRef ref = new IndexRef(tenantId, DS, "src", 1L);
    index.recreate(ref, DIM);
    index.upsert(
        ref,
        List.of(
            new IndexedRow(1, "배관 누수 발생", "h1", axis(0), "fake"),
            new IndexedRow(2, "소음 민원", "h2", axis(1), "fake")));
    return ref;
  }

  private static List<String> collections(QdrantClient c) throws Exception {
    return c.listCollectionsAsync()
        .get(QdrantTestContainer.CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("공유 테넌트와 전용 테넌트가 한 설치본에서 함께 행 검색하고, 각자의 인스턴스에만 컬렉션이 생긴다")
  void sharedAndDedicatedTenantsSearchSideBySide() throws Exception {
    IndexRef shared = indexTwoRows(DEFAULT_TEST_TENANT_ID);
    IndexRef dedicated = indexTwoRows(DEDICATED_TENANT);

    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    assertThat(index.semantic(shared, axis(1), RowQueryFilter.none(), 1))
        .extracting(RowHit::rowId)
        .containsExactly(2L);
    TenantContext.set(DEDICATED_TENANT);
    assertThat(index.keyword(dedicated, "누수", RowQueryFilter.none(), 5))
        .extracting(RowHit::rowId)
        .containsExactly(1L);
    assertThat(index.count(dedicated)).isEqualTo(2);

    String dedicatedPrefix = QdrantCollectionSpec.rowsTenantPrefix(DEDICATED_TENANT);
    String sharedPrefix = QdrantCollectionSpec.rowsTenantPrefix(DEFAULT_TEST_TENANT_ID);
    try (QdrantClient d =
        QdrantClientConfig.create(
            QdrantRowTargets.dedicatedProperties(
                props,
                dedicatedContainer.getHost(),
                dedicatedContainer.getGrpcPort(),
                false,
                DEDICATED_KEY))) {
      assertThat(collections(d))
          .anyMatch(n -> n.startsWith(dedicatedPrefix))
          .noneMatch(n -> n.startsWith(sharedPrefix));
    }
    assertThat(collections(sharedClient))
        .anyMatch(n -> n.startsWith(sharedPrefix))
        .noneMatch(n -> n.startsWith(dedicatedPrefix));
  }

  @Test
  @DisplayName("전용 인스턴스가 내려가면 그 테넌트만 「일시 중단」이고 공유 테넌트 검색은 그대로다")
  void dedicatedOutageDoesNotAffectSharedTenants() {
    IndexRef shared = indexTwoRows(DEFAULT_TEST_TENANT_ID);
    indexTwoRows(DEDICATED_TENANT);
    assertThat(targets.available(DEDICATED_TENANT)).isTrue();

    dedicatedContainer.stop();
    // 상태 캐시(5초)가 지나야 다시 점검한다 — 새 해석으로 캐시를 비운다.
    targets.invalidate(DEDICATED_TENANT);

    assertThat(targets.available(DEDICATED_TENANT)).isFalse();
    assertThat(targets.available(DEFAULT_TEST_TENANT_ID)).isTrue();
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    assertThat(index.semantic(shared, axis(0), RowQueryFilter.none(), 1))
        .extracting(RowHit::rowId)
        .containsExactly(1L);
  }

  @Test
  @DisplayName("연결 확인 — 맞는 키·JWT RBAC 면 통과, 틀린 키면 실패 사유")
  void probeChecksKeyAndRbac() {
    QdrantInstanceProbe probe = new QdrantInstanceProbe(props);
    VectorStoreConnectionTestResult ok =
        probe.probe(
            dedicatedContainer.getHost(), dedicatedContainer.getGrpcPort(), false, DEDICATED_KEY);
    assertThat(ok.ok()).as(ok.message()).isTrue();
    assertThat(ok.serverVersion()).isNotBlank();

    VectorStoreConnectionTestResult wrong =
        probe.probe(
            dedicatedContainer.getHost(), dedicatedContainer.getGrpcPort(), false, "wrong-key");
    assertThat(wrong.ok()).isFalse();
    assertThat(wrong.message()).contains("API 키");
  }
}
