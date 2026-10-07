package com.smartfirehub.document.repository;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.EmbeddingTestFixtures;
import com.smartfirehub.support.EmbeddingTestFixtures.DocFixture;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.sql.SQLException;
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

/**
 * 문서 청크 벡터 저장 계층: 1024 테넌트와 1536 테넌트 공존, #392(모델 필터), 차원별 HNSW, 복합 FK.
 *
 * <p>검증 대상 호출(리포지토리)은 {@code inTenantFixture} <b>밖</b>에서 {@code TenantContext.runScoped} 로 부른다 —
 * 리포지토리가 스스로 트랜잭션·GUC 를 세우는지까지 함께 본다(IntegrationTestBase 경계 규칙).
 */
class DocumentChunkVectorStoreTest extends IntegrationTestBase {

  private static final EmbeddingSpace S1024 =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");
  private static final EmbeddingSpace S1536 =
      new EmbeddingSpace(EmbeddingDimension.D1536, "text-embedding-3-small");

  // 저장·검색 인프라(벡터 차원·모델 필터·실행 계획)를 검증하는 테스트라 가시성은 항상 참인 SQL 을 넘긴다 — 가시성 자체는 securitylevel 패키지 테스트가
  // 고정한다.
  private static final String ALL_VISIBLE = "TRUE";

  @Autowired private DocumentChunkRepository repo;
  @Autowired private DSLContext dsl;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource ownerDataSource;

  private long tenantA;
  private long tenantB;
  private DocFixture docA;
  private DocFixture docB;

  @BeforeEach
  void seed() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "vec-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "vec-b");
    docA = inTenantFixture(tenantA, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "veca"));
    docB = inTenantFixture(tenantB, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "vecb"));
    TenantContext.runScoped(
        tenantA,
        () ->
            repo.insertBatch(
                docA.fileId(),
                docA.datasetId(),
                List.of(new Chunk(0, "a-near", 1), new Chunk(1, "a-far", 1)),
                List.of(axis(1024, 0), axis(1024, 1)),
                S1024));
    TenantContext.runScoped(
        tenantB,
        () ->
            repo.insertBatch(
                docB.fileId(),
                docB.datasetId(),
                List.of(new Chunk(0, "b-near", 1)),
                List.of(axis(1536, 0)),
                S1536));
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenantA);
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenantB);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
    TenantRlsTestSupport.deleteUser(dsl, docA.userId());
    TenantRlsTestSupport.deleteUser(dsl, docB.userId());
  }

  @Test
  void eachTenantSeesOnlyItsOwnDimensionAndRows() {
    List<DocumentSearchHit> aHits =
        TenantContext.runScopedGet(
            tenantA, () -> repo.searchByCosine(S1024, axis(1024, 0), List.of(), 10, ALL_VISIBLE));
    assertThat(aHits).extracting(DocumentSearchHit::content).containsExactly("a-near", "a-far");
    // A 컨텍스트에서 1536 공간은 비어 있다(B 의 행이 새지 않는다).
    assertThat(TenantContext.runScopedGet(tenantA, () -> repo.countEmbedded(S1536))).isZero();
    assertThat(TenantContext.runScopedGet(tenantB, () -> repo.countEmbedded(S1024))).isZero();
    assertThat(TenantContext.runScopedGet(tenantB, () -> repo.countEmbedded(S1536))).isEqualTo(1);
    // 벡터 테이블 단독 SELECT 도 RLS 로 막힌다(조인 누락 대비).
    Long seenByA =
        inTenantFixture(
            tenantA,
            () -> dsl.fetchOne("SELECT count(*) FROM document_chunk_vec_1536").get(0, Long.class));
    assertThat(seenByA).isZero();
  }

  @Test
  void searchIgnoresVectorsOfAnotherModelInSameDimension() {
    // #392: 같은 1024 공간에 모델 A 벡터만 있는데 모델 B 로 검색하면 A 벡터는 결과에 없어야 한다.
    EmbeddingSpace otherModel = new EmbeddingSpace(EmbeddingDimension.D1024, "other-model");
    assertThat(
            TenantContext.runScopedGet(
                tenantA,
                () -> repo.searchByCosine(otherModel, axis(1024, 0), List.of(), 10, ALL_VISIBLE)))
        .isEmpty();
    assertThat(TenantContext.runScopedGet(tenantA, () -> repo.countMissing(otherModel)))
        .isEqualTo(2);
  }

  @Test
  void upsertMovesVectorAcrossDimensionsKeepingOneRowPerChunk() {
    List<Long> ids =
        inTenantFixture(
            tenantA,
            () -> dsl.fetch("SELECT id FROM document_chunk ORDER BY id").getValues(0, Long.class));
    TenantContext.runScoped(
        tenantA, () -> repo.upsertEmbeddings(S1536, ids, List.of(axis(1536, 0), axis(1536, 1))));
    // 불변식: 한 청크의 벡터는 차원 테이블 전체를 통틀어 최대 1행.
    assertThat(TenantContext.runScopedGet(tenantA, () -> repo.countEmbedded(S1024))).isZero();
    assertThat(TenantContext.runScopedGet(tenantA, () -> repo.countEmbedded(S1536))).isEqualTo(2);
    assertThat(TenantContext.runScopedGet(tenantA, () -> repo.countMissing(S1536))).isZero();
  }

  @Test
  void upsertForVanishedChunkIsNoOp() {
    // Review Focus: 조회 뒤 문서가 지워져도 FK 오류로 잡이 죽지 않는다(INSERT…SELECT 가 0행).
    TenantContext.runScoped(
        tenantA,
        () -> repo.upsertEmbeddings(S1536, List.of(Long.MAX_VALUE), List.of(axis(1536, 0))));
    assertThat(TenantContext.runScopedGet(tenantA, () -> repo.countEmbedded(S1536))).isZero();
  }

  @Test
  void findMissingPagesByIdAndSkipsEmbedded() {
    EmbeddingSpace next = new EmbeddingSpace(EmbeddingDimension.D1536, "m2");
    var first = TenantContext.runScopedGet(tenantA, () -> repo.findMissing(next, 0L, 1));
    assertThat(first).hasSize(1);
    var second =
        TenantContext.runScopedGet(
            tenantA, () -> repo.findMissing(next, first.get(0).chunkId(), 10));
    assertThat(second).hasSize(1);
    assertThat(second.get(0).chunkId()).isGreaterThan(first.get(0).chunkId());
  }

  @Test
  void deleteOtherDimensionsRemovesOnlyCurrentTenantRows() {
    // 시드: A 는 1024 테이블에만 2행, B 는 1536 테이블에만 1행.
    // (1) 교차 테넌트: B 가 1536 을 남기라고 하면 DELETE 는 1024 테이블에 간다 — 거기엔 A 의 행만 있다.
    //     테넌트 필터가 없으면 A 의 2행이 사라진다.
    TenantContext.runScoped(tenantB, () -> repo.deleteOtherDimensions(EmbeddingDimension.D1536));
    assertThat(TenantContext.runScopedGet(tenantA, () -> repo.countEmbedded(S1024))).isEqualTo(2);
    assertThat(TenantContext.runScopedGet(tenantB, () -> repo.countEmbedded(S1536))).isEqualTo(1);

    // (2) 소유자 커넥션(RLS 우회)에서도 남의 행을 지우지 않는다 — 이 경로에선 RLS 가 막아 주지 않으므로
    //     명시적 WHERE tenant_id = ? 만이 보호막이다(javadoc 의 주장). 스프링 빈이 아닌 인스턴스라
    //     @Transactional 없이 소유자 DSL 로 자동 커밋된다.
    DocumentChunkRepository ownerRepo =
        new DocumentChunkRepository(DSL.using(ownerDataSource, SQLDialect.POSTGRES));
    int deletedByOwner =
        TenantContext.runScopedGet(
            tenantB, () -> ownerRepo.deleteOtherDimensions(EmbeddingDimension.D1536));
    assertThat(deletedByOwner).isZero();
    assertThat(TenantContext.runScopedGet(tenantA, () -> repo.countEmbedded(S1024))).isEqualTo(2);

    // (3) 양성 대조군 — 자기 테넌트 행은 실제로 지운다(아무것도 안 지우는 구현이 위 단언을 통과하지 못하게).
    TenantContext.runScoped(tenantB, () -> repo.deleteOtherDimensions(EmbeddingDimension.D1024));
    assertThat(TenantContext.runScopedGet(tenantB, () -> repo.countEmbedded(S1536))).isZero();
    assertThat(TenantContext.runScopedGet(tenantA, () -> repo.countEmbedded(S1024))).isEqualTo(2);
  }

  @Test
  void semanticSqlUsesTheDimensionsHnswIndex() {
    // 행이 적어 플래너가 원래 seq scan 을 고를 수 있으므로 enable_seqscan 을 끄고 비교한다(PgRowSearchIndexSearchTest 선례).
    assertThat(explain(S1024)).contains("idx_document_chunk_vec_1024_embedding");
    assertThat(explain(S1536)).contains("idx_document_chunk_vec_1536_embedding");
  }

  @Test
  void iterativeScanFillsTopKWhenModelFilterRejectsNearestCandidates() {
    // 모델 필터(인덱스 없음)는 HNSW 가 후보를 뽑은 "뒤에" 거른다. 질의에 가장 가까운 40개가 다른 모델이면
    // ef_search 가 작을 때 iterative scan 없이는 현재 모델 벡터를 5건 채우지 못한다.
    // 뮤테이션: HnswSearch.relaxIterativeScan 의 iterative_scan 줄을 지우면 "with" 단언이 빨개진다.
    // (행이 적어 seq scan 이 되면 차이가 사라지므로 enable_seqscan 을 끄고, ef_search 를 10 으로 낮춰 재현한다.)
    EmbeddingSpace otherModel = new EmbeddingSpace(EmbeddingDimension.D1024, "other-model");
    List<Chunk> near = new ArrayList<>();
    List<float[]> nearVec = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      near.add(new Chunk(100 + i, "other-" + i, 1));
      nearVec.add(axis(1024, 5));
    }
    List<Chunk> target = new ArrayList<>();
    List<float[]> targetVec = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      target.add(new Chunk(200 + i, "target-" + i, 1));
      targetVec.add(axis(1024, 6));
    }
    TenantContext.runScoped(
        tenantA,
        () -> repo.insertBatch(docA.fileId(), docA.datasetId(), near, nearVec, otherModel));
    TenantContext.runScoped(
        tenantA, () -> repo.insertBatch(docA.fileId(), docA.datasetId(), target, targetVec, S1024));

    List<Object> params = new ArrayList<>();
    String sql =
        repo.semanticSql(S1024, List.of(), vectorLiteral(axis(1024, 5)), 5, params, ALL_VISIBLE);
    int without =
        inTenantFixture(
            tenantA,
            () -> {
              dsl.execute("SET LOCAL enable_seqscan = off");
              // 테넌트 btree 로 몇 행을 읽고 Sort 하는 계획도 막는다 — 정렬을 HNSW 가 맡는 계획만 남긴다.
              dsl.execute("SET LOCAL enable_sort = off");
              dsl.execute("SET LOCAL hnsw.ef_search = 10");
              return dsl.fetch(sql, params.toArray()).size();
            });
    int with =
        inTenantFixture(
            tenantA,
            () -> {
              dsl.execute("SET LOCAL enable_seqscan = off");
              // 테넌트 btree 로 몇 행을 읽고 Sort 하는 계획도 막는다 — 정렬을 HNSW 가 맡는 계획만 남긴다.
              dsl.execute("SET LOCAL enable_sort = off");
              com.smartfirehub.embedding.HnswSearch.relaxIterativeScan(dsl);
              dsl.execute("SET LOCAL hnsw.ef_search = 10"); // relax 가 200 으로 올린 값을 다시 낮춘다
              return dsl.fetch(sql, params.toArray()).size();
            });
    assertThat(without).isLessThan(5);
    assertThat(with).isEqualTo(5);
    // 실제 경로도 5건을 채운다(ef_search 200 + iterative scan).
    assertThat(
            TenantContext.runScopedGet(
                tenantA,
                () -> repo.searchByCosine(S1024, axis(1024, 5), List.of(), 5, ALL_VISIBLE)))
        .hasSize(5);
  }

  @Test
  void compositeFkRejectsVectorWithAnotherTenantsId() {
    // 소유자 커넥션(RLS 우회)으로 넣어야 WITH CHECK(42501)보다 FK(23503)가 먼저 판정된다.
    Long chunkOfA =
        inTenantFixture(
            tenantA, () -> dsl.fetchOne("SELECT min(id) FROM document_chunk").get(0, Long.class));
    DSLContext owner = DSL.using(ownerDataSource, SQLDialect.POSTGRES);
    Throwable thrown =
        catchThrowable(
            () ->
                owner.execute(
                    "INSERT INTO document_chunk_vec_1536 (chunk_id, tenant_id, dataset_id, embedding, embedding_model)"
                        + " VALUES (?, ?, ?, ?::vector, 'x')",
                    chunkOfA,
                    tenantB,
                    docA.datasetId(),
                    vectorLiteral(axis(1536, 0))));
    SQLException sql = TenantRlsTestSupport.findSqlException(thrown);
    assertThat((Object) sql).as("SQLException 이 감싸져 있어야 SQLSTATE 를 단언할 수 있다").isNotNull();
    assertThat(sql.getSQLState()).isEqualTo("23503");
  }

  private String explain(EmbeddingSpace space) {
    List<Object> params = new ArrayList<>();
    String sql =
        repo.semanticSql(
            space,
            List.of(),
            vectorLiteral(axis(space.dimension().size(), 0)),
            5,
            params,
            ALL_VISIBLE);
    return inTenantFixture(
        tenantA,
        () -> {
          dsl.execute("SET LOCAL enable_seqscan = off");
          // 테넌트 btree 로 몇 행을 읽고 Sort 하는 계획도 막는다 — 정렬을 HNSW 가 맡는 계획만 남긴다.
          dsl.execute("SET LOCAL enable_sort = off");
          return String.join(
              "\n", dsl.fetch("EXPLAIN " + sql, params.toArray()).getValues(0, String.class));
        });
  }

  private static String vectorLiteral(float[] v) {
    return com.smartfirehub.dataset.search.VectorLiterals.toVectorLiteral(v);
  }
}
