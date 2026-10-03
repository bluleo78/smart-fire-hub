package com.smartfirehub.dataset.search;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.EmbeddingTestFixtures;
import com.smartfirehub.support.EmbeddingTestFixtures.DocFixture;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/** 데이터셋 카탈로그 벡터: 차원 이동, #392 모델 필터, 부모 행 없으면 no-op, 차원별 HNSW, 다른 차원 정리의 테넌트 범위. */
class DatasetEmbeddingVectorStoreTest extends IntegrationTestBase {

  private static final EmbeddingSpace S1024 =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");
  private static final EmbeddingSpace S1536 = new EmbeddingSpace(EmbeddingDimension.D1536, "m2");

  @Autowired private DatasetEmbeddingRepository repo;
  @Autowired private DatasetSearchRepository searchRepo;
  @Autowired private DSLContext dsl;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource ownerDataSource;

  private long tenant;
  private DocFixture doc;
  // 테스트가 더 만든 픽스처 사용자 — 데이터셋이 사용자를 참조하므로 데이터셋 정리 뒤에 지운다.
  private final List<Long> extraUsers = new ArrayList<>();

  @BeforeEach
  void seed() {
    tenant = TenantRlsTestSupport.createActiveTenant(dsl, "dsvec");
    doc = inTenantFixture(tenant, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "dsvec"));
    TenantContext.runScoped(tenant, () -> repo.upsertSourceText(doc.datasetId(), "화재 통계"));
    TenantContext.runScoped(
        tenant, () -> repo.upsertEmbedding(S1024, doc.datasetId(), axis(1024, 0)));
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenant);
    TenantRlsTestSupport.deleteTenants(dsl, tenant);
    TenantRlsTestSupport.deleteUser(dsl, doc.userId());
    extraUsers.forEach(u -> TenantRlsTestSupport.deleteUser(dsl, u));
  }

  @Test
  void upsertIntoNewDimensionRemovesOldDimensionRow() {
    TenantContext.runScoped(
        tenant, () -> repo.upsertEmbedding(S1536, doc.datasetId(), axis(1536, 0)));
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1024))).isZero();
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1536))).isEqualTo(1);
  }

  @Test
  void batchUpsertMovesEveryRowAndSkipsMissingParent() {
    // B1: 배치 한 번에 여러 데이터셋 — 첫 행만 처리하는 구현을 배제하려고 두 번째 데이터셋에도 옛 차원 행을 둔다.
    DocFixture doc2 =
        inTenantFixture(tenant, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "dsvec2"));
    extraUsers.add(doc2.userId());
    TenantContext.runScoped(
        tenant,
        () -> {
          repo.upsertSourceText(doc2.datasetId(), "소방 점검");
          repo.upsertEmbedding(S1024, doc2.datasetId(), axis(1024, 1));
        });
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1024))).isEqualTo(2);

    TenantContext.runScoped(
        tenant,
        () ->
            repo.upsertEmbeddings(
                S1536,
                List.of(doc.datasetId(), Long.MAX_VALUE, doc2.datasetId()),
                List.of(axis(1536, 0), axis(1536, 1), axis(1536, 2))));

    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1024))).isZero();
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1536))).isEqualTo(2);
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countMissing(S1536))).isZero();
  }

  @Test
  void upsertWithoutParentRowIsNoOp() {
    // 부모 dataset_embedding 행이 없으면(삭제 경합) 조용히 건너뛴다 — 옛 UPDATE 0행과 같은 의미.
    TenantContext.runScoped(
        tenant, () -> repo.upsertEmbedding(S1024, Long.MAX_VALUE, axis(1024, 0)));
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1024))).isEqualTo(1);
  }

  @Test
  void semanticSearchFiltersByModel() {
    // #392
    assertThat(
            TenantContext.runScopedGet(
                tenant, () -> searchRepo.searchByCosine(S1024, axis(1024, 0), null, 10)))
        .extracting(DatasetSearchHit::datasetId)
        .containsExactly(doc.datasetId());
    EmbeddingSpace otherModel = new EmbeddingSpace(EmbeddingDimension.D1024, "other");
    assertThat(
            TenantContext.runScopedGet(
                tenant, () -> searchRepo.searchByCosine(otherModel, axis(1024, 0), null, 10)))
        .isEmpty();
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countMissing(otherModel)))
        .isEqualTo(1);
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.findMissing(otherModel, 0L, 10)))
        .extracting(DatasetEmbeddingRepository.SourceTextRow::sourceText)
        .containsExactly("화재 통계");
  }

  @Test
  void deleteOtherDimensionsRemovesOnlyCurrentTenantRows() {
    // 시드: A(=tenant) 는 1024 테이블에만 1행. 이 테스트에서만 B 를 만들어 1536 테이블에만 1행을 둔다.
    long tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "dsvec-b");
    DocFixture docB =
        inTenantFixture(tenantB, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "dsvecb"));
    try {
      TenantContext.runScoped(tenantB, () -> repo.upsertSourceText(docB.datasetId(), "B 카탈로그"));
      TenantContext.runScoped(
          tenantB, () -> repo.upsertEmbedding(S1536, docB.datasetId(), axis(1536, 0)));

      // (1) 소유자 커넥션(RLS 우회)에서 B 가 1536 을 남기라고 하면 DELETE 는 1024 테이블로 간다 — 거기엔 A 의 행만
      //     있다. 이 경로에선 RLS 가 막아 주지 않으므로 명시적 WHERE tenant_id = ? 만이 보호막이다. 스프링 빈이 아닌
      //     인스턴스라 @Transactional 없이 소유자 DSL 로 자동 커밋된다.
      DatasetEmbeddingRepository ownerRepo =
          new DatasetEmbeddingRepository(DSL.using(ownerDataSource, SQLDialect.POSTGRES));
      int deletedByOwner =
          TenantContext.runScopedGet(
              tenantB, () -> ownerRepo.deleteOtherDimensions(EmbeddingDimension.D1536));
      assertThat(deletedByOwner).isZero();
      assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1024))).isEqualTo(1);
      assertThat(TenantContext.runScopedGet(tenantB, () -> repo.countEmbedded(S1536))).isEqualTo(1);

      // (2) 양성 대조군 — 같은 소유자 경로로 자기 테넌트 행은 실제로 지운다(아무것도 안 지우는 구현 배제).
      int deletedOwn =
          TenantContext.runScopedGet(
              tenantB, () -> ownerRepo.deleteOtherDimensions(EmbeddingDimension.D1024));
      assertThat(deletedOwn).isEqualTo(1);
      assertThat(TenantContext.runScopedGet(tenantB, () -> repo.countEmbedded(S1536))).isZero();
      assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1024))).isEqualTo(1);
    } finally {
      TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenantB);
      TenantRlsTestSupport.deleteTenants(dsl, tenantB);
      TenantRlsTestSupport.deleteUser(dsl, docB.userId());
    }
  }

  @Test
  void semanticSqlUsesTheDimensionsHnswIndex() {
    List<Object> params = new ArrayList<>();
    String sql =
        searchRepo.semanticSql(
            S1536, null, VectorLiterals.toVectorLiteral(axis(1536, 0)), 5, params);
    String plan =
        inTenantFixture(
            tenant,
            () -> {
              dsl.execute("SET LOCAL enable_seqscan = off");
              // 테넌트 btree 로 몇 행을 읽고 Sort 하는 계획도 막는다 — 정렬을 HNSW 가 맡는 계획만 남긴다.
              dsl.execute("SET LOCAL enable_sort = off");
              return String.join(
                  "\n", dsl.fetch("EXPLAIN " + sql, params.toArray()).getValues(0, String.class));
            });
    assertThat(plan).contains("idx_dataset_embedding_vec_1536_embedding");
  }
}
