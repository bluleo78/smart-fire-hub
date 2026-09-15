package com.smartfirehub.graphingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.graphingest.dto.RecordGraphIngestRequest;
import com.smartfirehub.graphingest.dto.StaleDatasetResponse;
import com.smartfirehub.graphingest.service.GraphIngestService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GraphIngestService.stale() 통합 테스트 — 서로 다른 온톨로지에 바인딩된 데이터셋이 각자의 바인딩
 * 온톨로지 schema_version을 기준으로 개별 판정되는지 검증한다(#678 — 단일 "기본" 온톨로지 하드코딩
 * 제거).
 */
class GraphIngestServiceTest extends IntegrationTestBase {

  @Autowired private GraphIngestService graphIngestService;
  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  @Test
  void stale_서로_다른_온톨로지에_바인딩된_데이터셋은_각자_기준으로_판정된다() {
    // 테스트 네임스페이스: dataset_id >= 9000(GraphIngestRepositoryTest 관례와 일치, 공유 test DB
    // 충돌 방지). 온톨로지 domain 은 실행마다 고유해야 UNIQUE(domain) 위반이 없다.
    long datasetA = 9301L;
    long datasetB = 9302L;
    String domainA = "테스트 온톨로지 A " + System.nanoTime();
    String domainB = "테스트 온톨로지 B " + System.nanoTime();

    // 온톨로지 A(schema_version=3), 온톨로지 B(schema_version=1)를 만들고,
    // 데이터셋 9301은 A에 v2로 적재(낡음), 데이터셋 9302는 B에 v1로 적재(최신)되도록 준비한다.
    long ontologyA =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.insertInto(table(name("ontology")))
                    .set(field(name("domain"), String.class), domainA)
                    .set(field(name("schema_version"), Integer.class), 3)
                    .returning(field(name("id"), Long.class))
                    .fetchOne()
                    .get(field(name("id"), Long.class)));
    long ontologyB =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.insertInto(table(name("ontology")))
                    .set(field(name("domain"), String.class), domainB)
                    .set(field(name("schema_version"), Integer.class), 1)
                    .returning(field(name("id"), Long.class))
                    .fetchOne()
                    .get(field(name("id"), Long.class)));
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.insertInto(table(name("dataset_ontology")))
                .set(field(name("dataset_id"), Long.class), datasetA)
                .set(field(name("ontology_id"), Long.class), ontologyA)
                .execute());
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.insertInto(table(name("dataset_ontology")))
                .set(field(name("dataset_id"), Long.class), datasetB)
                .set(field(name("ontology_id"), Long.class), ontologyB)
                .execute());

    try {
      graphIngestService.record(datasetA, new RecordGraphIngestRequest(2, 5, 5, 0, 0, "SUCCESS"));
      graphIngestService.record(datasetB, new RecordGraphIngestRequest(1, 5, 5, 0, 0, "SUCCESS"));

      List<StaleDatasetResponse> stale = graphIngestService.stale();

      assertThat(stale).extracting(StaleDatasetResponse::datasetId).contains(datasetA);
      assertThat(stale).extracting(StaleDatasetResponse::datasetId).doesNotContain(datasetB);
      StaleDatasetResponse staleA =
          stale.stream().filter(s -> s.datasetId() == datasetA).findFirst().orElseThrow();
      assertThat(staleA.currentSchemaVersion()).isEqualTo(3);
    } finally {
      // 정리 — IntegrationTestBase 는 롤백하지 않고 커밋하므로 수동 정리 필수.
      TenantRlsTestSupport.runInTenantTransaction(
          tx,
          DEFAULT_TEST_TENANT_ID,
          () ->
              dsl.deleteFrom(table(name("dataset_graph_ingest")))
                  .where(field(name("dataset_id"), Long.class).in(datasetA, datasetB))
                  .execute());
      TenantRlsTestSupport.runInTenantTransaction(
          tx,
          DEFAULT_TEST_TENANT_ID,
          () ->
              dsl.deleteFrom(table(name("dataset_ontology")))
                  .where(field(name("dataset_id"), Long.class).in(datasetA, datasetB))
                  .execute());
      TenantRlsTestSupport.runInTenantTransaction(
          tx,
          DEFAULT_TEST_TENANT_ID,
          () ->
              dsl.deleteFrom(table(name("ontology")))
                  .where(field(name("id"), Long.class).in(ontologyA, ontologyB))
                  .execute());
    }
  }
}
