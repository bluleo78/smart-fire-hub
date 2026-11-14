package com.smartfirehub.dataset.repository.jooq;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.SourceHash;
import com.smartfirehub.embedding.repository.VectorLiterals;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository;
import com.smartfirehub.securitylevel.repository.EmbeddingAiPolicyRepository;
import com.smartfirehub.support.EmbeddingTestFixtures;
import com.smartfirehub.support.EmbeddingTestFixtures.DocFixture;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.vectorstore.repository.jooq.PgDatasetVectorIndex;
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

/** 데이터셋 카탈로그 벡터: 차원 이동, #392 모델 필터, 부모 행 없으면 no-op, 차원별 HNSW, 다른 차원 정리의 테넌트 범위. */
class DatasetEmbeddingVectorStoreTest extends IntegrationTestBase {

  // 시드 원문의 해시 — 벡터를 쓸 때 "이 원문으로 만든 벡터" 라고 넘긴다(WD-720: 지금 원문과 같아야 표시가 찬다).
  private static final String H_DOC = SourceHash.of("화재 통계");
  private static final String H_DOC2 = SourceHash.of("소방 점검");

  private static final EmbeddingSpace S1024 =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");
  private static final EmbeddingSpace S1536 = new EmbeddingSpace(EmbeddingDimension.D1536, "m2");

  // 저장·검색 인프라(벡터 차원·모델 필터·실행 계획)를 검증하는 테스트라 가시성은 항상 참인 SQL 을 넘긴다 — 가시성 자체는 securitylevel 패키지 테스트가
  // 고정한다.
  // 제한 없는 가시성(예전 문자열 "TRUE") — 이 테스트는 등급 가시성이 아니라 검색 자체를 본다(WD-485 T10: 인터페이스가 도메인 값을 받는다).
  private static final DatasetVisibility ALL_VISIBLE = DatasetVisibility.UNRESTRICTED;

  // semanticSql(EXPLAIN 단언용)은 구현 내부라 이미 렌더한 가시성 조각을 받는다.
  private static final String ALL_VISIBLE_SQL = "TRUE";

  // 가장 넓은 실제 허용 값(자체 호스팅 — DENY 등급만 뺀다). "제한 없음" 허용 값은 우회 경로라 일부러 없다(WD-485 T10).
  private static final EmbeddingAllowance ALLOW_ALL_LEVELS =
      new EmbeddingAllowance(ProviderHosting.SELF_HOSTED);

  @Autowired private DatasetEmbeddingRepository repo;
  @Autowired private PgDatasetVectorIndex searchRepo;
  @Autowired private DatasetAccessRepository accessRepository;
  @Autowired private DSLContext dsl;
  // 손으로 만드는 소유자 커넥션 인스턴스에 넘길 술어 렌더러(삭제 경로는 쓰지 않는다 — 생성자 인자일 뿐).
  @Autowired private EmbeddingAiPolicyRepository aiPolicyRepository;

  /**
   * 소유자(RLS 우회) 커넥션. 임베딩 벡터 테이블은 플랫폼 DB(public)에 있으므로 플랫폼 DB 슈퍼유저로 붙는다 — 스키마 소유자 풀은 분리 모드에서 데이터 저장소
   * DB 를 가리킨다(WD-494). 별칭 모드에서는 같은 DB·같은 자격증명이다.
   */
  private final DataSource ownerDataSource = PostgresTestContainer.platformOwnerDataSource();

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
        tenant, () -> repo.upsertEmbedding(S1024, doc.datasetId(), axis(1024, 0), H_DOC));
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
        tenant, () -> repo.upsertEmbedding(S1536, doc.datasetId(), axis(1536, 0), H_DOC));
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
          repo.upsertEmbedding(S1024, doc2.datasetId(), axis(1024, 1), H_DOC2);
        });
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1024))).isEqualTo(2);

    TenantContext.runScoped(
        tenant,
        () ->
            repo.upsertEmbeddings(
                S1536,
                List.of(doc.datasetId(), Long.MAX_VALUE, doc2.datasetId()),
                List.of(axis(1536, 0), axis(1536, 1), axis(1536, 2)),
                List.of(H_DOC, H_DOC, H_DOC2)));

    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1024))).isZero();
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1536))).isEqualTo(2);
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countMissing(S1536, ALLOW_ALL_LEVELS)))
        .isZero();
  }

  @Test
  void legacyRowWithoutSourceHashIsJudgedBySpaceOnlyAndGetsHashOnNextWrite() {
    // V161 이전 행(원문 해시 없음)을 흉내 낸다 — 소유자 커넥션으로 해시 열을 비운다.
    DSLContext owner = DSL.using(ownerDataSource, SQLDialect.POSTGRES);
    owner.execute(
        "UPDATE dataset_embedding SET source_hash = NULL, embedded_hash = NULL WHERE dataset_id = ?",
        doc.datasetId());
    // 해시가 없으면 예전처럼 공간만 본다 — 공간이 맞으니 백로그가 아니다(업그레이드 직후 전체 재임베딩을 부르지 않는다).
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countMissing(S1024, ALLOW_ALL_LEVELS)))
        .isZero();
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1024))).isEqualTo(1);

    // 다음 벡터 쓰기가 넘긴 해시로 원문 해시까지 채운다 — 이후로는 원문 변경이 판정된다.
    TenantContext.runScoped(
        tenant, () -> repo.upsertEmbedding(S1024, doc.datasetId(), axis(1024, 0), H_DOC));
    assertThat(
            owner
                .fetchOne(
                    "SELECT source_hash, embedded_hash FROM dataset_embedding WHERE dataset_id = ?",
                    doc.datasetId())
                .intoArray())
        .containsExactly(H_DOC, H_DOC);
    TenantContext.runScoped(tenant, () -> repo.upsertSourceText(doc.datasetId(), "화재 통계 개정"));
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countMissing(S1024, ALLOW_ALL_LEVELS)))
        .isEqualTo(1);
  }

  @Test
  void upsertWithoutParentRowIsNoOp() {
    // 부모 dataset_embedding 행이 없으면(삭제 경합) 조용히 건너뛴다 — 옛 UPDATE 0행과 같은 의미.
    TenantContext.runScoped(
        tenant, () -> repo.upsertEmbedding(S1024, Long.MAX_VALUE, axis(1024, 0), H_DOC));
    assertThat(TenantContext.runScopedGet(tenant, () -> repo.countEmbedded(S1024))).isEqualTo(1);
  }

  @Test
  void semanticSearchFiltersByModel() {
    // #392
    assertThat(
            TenantContext.runScopedGet(
                tenant, () -> searchRepo.semantic(S1024, axis(1024, 0), null, 10, ALL_VISIBLE)))
        .extracting(DatasetSearchHit::datasetId)
        .containsExactly(doc.datasetId());
    EmbeddingSpace otherModel = new EmbeddingSpace(EmbeddingDimension.D1024, "other");
    assertThat(
            TenantContext.runScopedGet(
                tenant,
                () -> searchRepo.semantic(otherModel, axis(1024, 0), null, 10, ALL_VISIBLE)))
        .isEmpty();
    assertThat(
            TenantContext.runScopedGet(
                tenant, () -> repo.countMissing(otherModel, ALLOW_ALL_LEVELS)))
        .isEqualTo(1);
    assertThat(
            TenantContext.runScopedGet(
                tenant, () -> repo.findMissing(otherModel, 0L, 10, ALLOW_ALL_LEVELS)))
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
          tenantB,
          () ->
              repo.upsertEmbedding(
                  S1536, docB.datasetId(), axis(1536, 0), SourceHash.of("B 카탈로그")));

      // (1) 소유자 커넥션(RLS 우회)에서 B 가 1536 을 남기라고 하면 DELETE 는 1024 테이블로 간다 — 거기엔 A 의 행만
      //     있다. 이 경로에선 RLS 가 막아 주지 않으므로 명시적 WHERE tenant_id = ? 만이 보호막이다. 스프링 빈이 아닌
      //     인스턴스라 @Transactional 없이 소유자 DSL 로 자동 커밋된다.
      DatasetEmbeddingRepository ownerRepo =
          new JooqDatasetEmbeddingRepository(
              DSL.using(ownerDataSource, SQLDialect.POSTGRES),
              aiPolicyRepository,
              new PgDatasetVectorIndex(
                  DSL.using(ownerDataSource, SQLDialect.POSTGRES), accessRepository));
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
            S1536, null, VectorLiterals.toVectorLiteral(axis(1536, 0)), 5, params, ALL_VISIBLE_SQL);
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
