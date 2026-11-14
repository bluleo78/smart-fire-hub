package com.smartfirehub.vectorstore.qdrant;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.search.DatasetEmbeddingService;
import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.dataset.search.DatasetSearchMode;
import com.smartfirehub.dataset.search.DatasetSearchRequest;
import com.smartfirehub.dataset.search.DatasetSearchService;
import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.document.dto.DocumentSearchRequest;
import com.smartfirehub.document.dto.SearchMode;
import com.smartfirehub.document.service.DocumentIngestionService;
import com.smartfirehub.document.service.DocumentSearchService;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.config.EmbeddingConfig;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.config.EmbeddingProviderType;
import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.vectorstore.ChunkVectorIndex;
import com.smartfirehub.vectorstore.DatasetVectorIndex;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import com.smartfirehub.vectorstore.VectorStoreInstallKindCallback;
import io.qdrant.client.QdrantClient;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.jobrunr.scheduling.JobScheduler;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * PostgreSQL 플랫폼 × qdrant 조합 끝-끝(WD-720 완료 기준). 실제 앱 컨텍스트를 {@code firehub.vector-store.type=qdrant}
 * 로 띄우고, 운영 경로(원문 저장·재색인·문서 적재)로 색인한 뒤 데이터셋·문서 검색 서비스의 SEMANTIC·KEYWORD·HYBRID 가 Qdrant 로 답하는지 본다.
 * 서비스의 융합 ({@code RankFusion})·기능 가드는 그대로다 — 서비스는 구현을 모른다.
 *
 * <p>임베딩 공급자만 가짜다(「화재」가 든 글은 0번 축, 아니면 1번 축). Tibero 플랫폼 조합은 같은 서비스 코드에 MyBatis 후보 확인이 붙는 것뿐이라 계약
 * 테스트({@code TiberoMyBatisSearchCandidateRepositoryContractTest})가 그 차이를 덮는다.
 */
class QdrantPlatformSearchIntegrationTest extends IntegrationTestBase {

  private static final QdrantProperties PROPS = QdrantTestContainer.properties(2, true);

  @TempDir static Path uploadDir;

  @DynamicPropertySource
  static void qdrant(DynamicPropertyRegistry r) {
    // 공유 테스트 DB 는 다른 테스트가 먼저 띄운 컨텍스트로 설치 종류가 platform 으로 기록돼 있다(ADR 0002 결정 10 가드).
    // 이 컨텍스트가 뜨기 전에 qdrant 로 바꿔 두고, 끝나면 되돌려 뒤에 뜨는 platform 컨텍스트가 막히지 않게 한다.
    recordInstallType("qdrant");
    r.add("firehub.vector-store.type", () -> "qdrant");
    r.add("firehub.vector-store.qdrant.url", PROPS::url);
    r.add("firehub.vector-store.qdrant.api-key", PROPS::apiKey);
    r.add("firehub.vector-store.qdrant.shard-number", () -> "2");
    r.add("firehub.vector-store.keyword-sweep-enabled", () -> "false");
    r.add("firehub.file.upload-dir", uploadDir::toString);
  }

  /** 클래스가 끝나면 공유 DB 의 설치 종류 기록을 platform 으로 되돌린다. */
  @AfterAll
  static void restoreInstallType() {
    recordInstallType("platform");
  }

  /**
   * 공유 테스트 DB 의 설치 종류 기록을 소유자 계정으로 바꾼다. Flyway 가 아직 돌지 않은 새 DB(테이블 없음)면 아무것도 하지 않는다 — 그때는 콜백이 빈
   * 설치본으로 보고 이번 설정값(qdrant)을 기록한다.
   */
  private static void recordInstallType(String type) {
    try (Connection c = PostgresTestContainer.platformOwnerDataSource().getConnection()) {
      try (ResultSet rs =
          c.createStatement().executeQuery("SELECT to_regclass('public.system_settings')")) {
        if (!rs.next() || rs.getString(1) == null) {
          return;
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO system_settings (key, value, description) VALUES (?, ?, 'test')"
                  + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")) {
        ps.setString(1, VectorStoreInstallKindCallback.KEY);
        ps.setString(2, type);
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Autowired private VectorStoreFeatures features;
  @Autowired private DatasetVectorIndex datasetIndex;
  @Autowired private ChunkVectorIndex chunkIndex;
  @Autowired private QdrantClient client;
  @Autowired private DatasetEmbeddingService datasetEmbeddingService;
  @Autowired private DatasetSearchService datasetSearch;
  @Autowired private DocumentIngestionService ingestion;
  @Autowired private DocumentSearchService documentSearch;
  @Autowired private EmbeddingConfigService configService;
  @Autowired private DSLContext dsl;
  @MockitoBean private EmbeddingProviderFactory providerFactory;
  @MockitoBean private JobScheduler jobScheduler;

  private long tenant;
  private Long userId;
  private Clearance clearance;

  @BeforeEach
  void seed() throws Exception {
    // 다른 Qdrant 테스트가 컬렉션을 지웠을 수 있어 빈 저장소에서 새로 준비한다(앱 빈의 준비 캐시와 별개로).
    QdrantTestContainer.deleteAllCollections(client);
    new QdrantVectorStoreLifecycle(client, PROPS).prepare();
    when(providerFactory.current()).thenReturn(fakeProvider());
    tenant = TenantRlsTestSupport.createActiveTenant(dsl, "qint");
    userId = TenantRlsTestSupport.insertUser(dsl, "qint");
    clearance = new Clearance(userId, tenant, 100, Set.of(), true, Set.of());
    TenantContext.set(tenant);
    configService.store(
        new EmbeddingConfig(EmbeddingProviderType.OLLAMA, "fake", "http://unused", "", 0),
        EmbeddingDimension.D1024,
        ProviderHosting.EXTERNAL,
        null);
  }

  @AfterEach
  void cleanup() {
    TenantContext.clear();
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenant);
    TenantRlsTestSupport.deleteTenants(dsl, tenant);
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  private static EmbeddingProvider fakeProvider() {
    return new EmbeddingProvider() {
      public List<float[]> embed(List<String> texts) {
        return texts.stream().map(t -> axis(1024, t.contains("화재") ? 0 : 1)).toList();
      }

      public String modelId() {
        return "fake";
      }

      public int dimension() {
        return 1024;
      }
    };
  }

  private long createDataset(String name, String description, String storageType) {
    return inTenantFixture(
        tenant,
        () ->
            dsl.fetchOne(
                    "INSERT INTO dataset(name, description, table_name, storage_type, origin_type, created_by)"
                        + " VALUES (?, ?, ?, ?, 'SOURCE', ?) RETURNING id",
                    name,
                    description,
                    "data.qint_" + Math.abs(System.nanoTime()),
                    storageType,
                    userId)
                .get(0, Long.class));
  }

  /** 운영 경로: 편집 트랜잭션에서 원문 저장(커밋 뒤 키워드) → 비동기 재색인(dense). */
  private void indexDataset(long datasetId) {
    inTenantFixture(tenant, () -> datasetEmbeddingService.syncSourceText(datasetId));
    datasetEmbeddingService.reindexEmbedding(datasetId, tenant);
  }

  @Test
  void beansAreQdrant_andRowSearchStaysOff() {
    assertThat(datasetIndex).isInstanceOf(QdrantDatasetVectorIndex.class);
    assertThat(chunkIndex).isInstanceOf(QdrantChunkVectorIndex.class);
    assertThat(features.supports(PlatformDbFeature.VECTOR_SEARCH)).isTrue();
    assertThat(features.supports(PlatformDbFeature.TRIGRAM_SEARCH)).isTrue();
  }

  @Test
  void datasetSearchAnswersFromQdrantInAllModes() {
    long fire = createDataset("화재 통계", "연도별 화재 발생 건수", "TABLE");
    long hr = createDataset("인사 발령", "직원 인사 자료", "TABLE");
    indexDataset(fire);
    indexDataset(hr);

    for (DatasetSearchMode mode : DatasetSearchMode.values()) {
      List<Long> ids =
          datasetSearch.search(new DatasetSearchRequest("화재", 10, mode, null), clearance).stream()
              .map(DatasetSearchHit::datasetId)
              .toList();
      assertThat(ids).as("모드 %s", mode).isNotEmpty();
      assertThat(ids.get(0)).as("모드 %s 1위", mode).isEqualTo(fire);
      if (mode == DatasetSearchMode.KEYWORD) {
        assertThat(ids).as("키워드는 「화재」가 없는 데이터셋을 내지 않는다").doesNotContain(hr);
      }
    }
    // 저장 유형 필터도 Qdrant 경로에서 지켜진다.
    assertThat(
            datasetSearch.search(
                new DatasetSearchRequest("화재", 10, DatasetSearchMode.HYBRID, "DOCUMENT"),
                clearance))
        .isEmpty();
  }

  @Test
  void documentSearchAnswersFromQdrantInAllModes() {
    long docSet = createDataset("소방 문서", "점검 보고서 모음", "DOCUMENT");
    byte[] data = "화재 예방 점검 결과 보고서입니다. 소화기 위치를 확인했습니다.".getBytes();
    Long fileId = ingestion.upload(docSet, data, "report.txt", "text/plain", userId).id();
    ingestion.processIngestion(fileId, tenant);

    for (SearchMode mode : SearchMode.values()) {
      List<DocumentSearchHit> hits =
          documentSearch.search(new DocumentSearchRequest("화재 예방", null, 5, mode), clearance);
      assertThat(hits).as("모드 %s", mode).isNotEmpty();
      assertThat(hits).as("모드 %s", mode).allMatch(h -> h.datasetId() == docSet);
      assertThat(hits.get(0).fileName()).isEqualTo("report.txt");
    }
  }
}
