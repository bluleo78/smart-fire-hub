package com.smartfirehub.graphingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.graphingest.repository.GraphIngestRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** GraphIngestRepository 통합 테스트 — 실제 Postgres(smartfirehub_test) 대상. */
class GraphIngestRepositoryTest extends IntegrationTestBase {

  @Autowired private GraphIngestRepository repo;
  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  // IntegrationTestBase 는 롤백하지 않고 커밋하므로, 테스트 네임스페이스(dataset_id >= 9000)를
  // 매 테스트 전 정리해 재실행 시 누적 행으로 인한 실패(hasSize 등)를 방지한다.
  //
  // V102 이후 dataset_graph_ingest 는 RLS 대상이다 — 트랜잭션(=GUC) 밖 DELETE 는 조용히 0행이 되어
  // 정리가 무력화되고, 다음 실행에서 hasSize(2) 가 깨진다. 정리만 감싼다.
  @BeforeEach
  void cleanupTestNamespace() {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.deleteFrom(table(name("dataset_graph_ingest")))
                .where(field(name("dataset_id"), Long.class).ge(9000L))
                .execute());
  }

  @Test
  void save_and_findByDataset_returnsNewestFirst() {
    repo.save(9001L, 1, 10, 20, 15, 0, "SUCCESS");
    repo.save(9001L, 1, 11, 22, 16, 2, "PARTIAL");

    var rows = repo.findByDataset(9001L);

    assertThat(rows).hasSize(2);
    // 최신순(DESC) 정렬 검증
    assertThat(rows.get(0).ingestedAt()).isAfterOrEqualTo(rows.get(1).ingestedAt());
    assertThat(rows).extracting("status").contains("SUCCESS", "PARTIAL");
  }

  @Test
  void findStale_returnsDatasetsBelowBoundOntologyVersion_latestRowOnly() {
    long ontologyId = createOntology("V102_STALE_PROBE_" + System.nanoTime(), 3);
    bindDataset(9101L, ontologyId);
    bindDataset(9102L, ontologyId);
    try {
      repo.save(9101L, 1, 1, 1, 1, 0, "SUCCESS"); // v1 적재(낡음, 바인딩 온톨로지 v3 미만)
      repo.save(9102L, 3, 1, 1, 1, 0, "SUCCESS"); // v3 적재(최신, 낡지 않음)

      var stale = repo.findStale();

      assertThat(stale).extracting("datasetId").contains(9101L).doesNotContain(9102L);
    } finally {
      cleanupOntologyFixture(ontologyId, 9101L, 9102L);
    }
  }

  @Test
  void findStale_usesOnlyLatestRowPerDataset() {
    long ontologyId = createOntology("V102_LATEST_ONLY_PROBE_" + System.nanoTime(), 3);
    bindDataset(9201L, ontologyId);
    try {
      // 9201: 과거엔 v1로 적재됐지만 이후 v3로 재적재됨 → 최신 기준으로는 stale 아님
      repo.save(9201L, 1, 1, 1, 1, 0, "SUCCESS");
      repo.save(9201L, 3, 1, 1, 1, 0, "SUCCESS");

      var stale = repo.findStale();

      assertThat(stale).extracting("datasetId").doesNotContain(9201L);
    } finally {
      cleanupOntologyFixture(ontologyId, 9201L);
    }
  }

  @Test
  void findStale_바인딩이_없는_데이터셋은_결과에서_제외된다() {
    // 적재 이력만 있고 dataset_ontology 바인딩이 없는 데이터셋은 INNER JOIN에서 걸러져
    // stale 여부와 무관하게 결과에 나타나지 않아야 한다(적재 자체가 바인딩 전제이므로 정상 케이스).
    repo.save(9301L, 0, 1, 1, 1, 0, "SUCCESS");

    var stale = repo.findStale();

    assertThat(stale).extracting("datasetId").doesNotContain(9301L);
  }

  /** 테스트용 온톨로지 1행 생성(schema_version 지정), 트랜잭션 안에서 실행. */
  private long createOntology(String domain, int schemaVersion) {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.insertInto(table(name("ontology")))
                .set(field(name("domain"), String.class), domain)
                .set(field(name("schema_version"), Integer.class), schemaVersion)
                .returning(field(name("id"), Long.class))
                .fetchOne()
                .get(field(name("id"), Long.class)));
  }

  /** 데이터셋↔온톨로지 바인딩 1행 생성, 트랜잭션 안에서 실행. */
  private void bindDataset(long datasetId, long ontologyId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.insertInto(table(name("dataset_ontology")))
                .set(field(name("dataset_id"), Long.class), datasetId)
                .set(field(name("ontology_id"), Long.class), ontologyId)
                .execute());
  }

  /** 온톨로지 + 바인딩 픽스처 정리(커밋되므로 수동 삭제 필수). */
  private void cleanupOntologyFixture(long ontologyId, Long... datasetIds) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.deleteFrom(table(name("dataset_ontology")))
                .where(field(name("dataset_id"), Long.class).in(datasetIds))
                .execute());
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.deleteFrom(table(name("ontology")))
                .where(field(name("id"), Long.class).eq(ontologyId))
                .execute());
  }
}
