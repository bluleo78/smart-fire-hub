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
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 데이터셋 카탈로그 벡터: 차원 이동, #392 모델 필터, 부모 행 없으면 no-op, 차원별 HNSW. */
class DatasetEmbeddingVectorStoreTest extends IntegrationTestBase {

  private static final EmbeddingSpace S1024 = new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");
  private static final EmbeddingSpace S1536 = new EmbeddingSpace(EmbeddingDimension.D1536, "m2");

  @Autowired private DatasetEmbeddingRepository repo;
  @Autowired private DatasetSearchRepository searchRepo;
  @Autowired private DSLContext dsl;

  private long tenant;
  private DocFixture doc;

  @BeforeEach
  void seed() {
    tenant = TenantRlsTestSupport.createActiveTenant(dsl, "dsvec");
    doc = inTenantFixture(tenant, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "dsvec"));
    TenantContext.runScoped(tenant, () -> repo.upsertSourceText(doc.datasetId(), "화재 통계"));
    TenantContext.runScoped(tenant, () -> repo.upsertEmbedding(S1024, doc.datasetId(), axis(1024, 0)));
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenant);
    TenantRlsTestSupport.deleteTenants(dsl, tenant);
    TenantRlsTestSupport.deleteUser(dsl, doc.userId());
  }

  @Test
  void upsertIntoNewDimensionRemovesOldDimensionRow() {
    TenantContext.runScoped(tenant, () -> repo.upsertEmbedding(S1536, doc.datasetId(), axis(1536, 0)));
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1024))).isZero();
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1536))).isEqualTo(1);
  }

  @Test
  void upsertWithoutParentRowIsNoOp() {
    // 부모 dataset_embedding 행이 없으면(삭제 경합) 조용히 건너뛴다 — 옛 UPDATE 0행과 같은 의미.
    TenantContext.runScoped(tenant, () -> repo.upsertEmbedding(S1024, Long.MAX_VALUE, axis(1024, 0)));
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1024))).isEqualTo(1);
  }

  @Test
  void semanticSearchFiltersByModel() {
    // #392
    assertThat(TenantContext.runScopedGet(tenant, () -> searchRepo.searchByCosine(S1024, axis(1024, 0), null, 10)))
        .extracting(DatasetSearchHit::datasetId)
        .containsExactly(doc.datasetId());
    EmbeddingSpace otherModel = new EmbeddingSpace(EmbeddingDimension.D1024, "other");
    assertThat(TenantContext.runScopedGet(tenant, () -> searchRepo.searchByCosine(otherModel, axis(1024, 0), null, 10)))
        .isEmpty();
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countMissing(otherModel))).isEqualTo(1);
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.findMissing(otherModel, 0L, 10)))
        .extracting(DatasetEmbeddingRepository.SourceTextRow::sourceText)
        .containsExactly("화재 통계");
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
              return String.join("\n", dsl.fetch("EXPLAIN " + sql, params.toArray()).getValues(0, String.class));
            });
    assertThat(plan).contains("idx_dataset_embedding_vec_1536_embedding");
  }
}
