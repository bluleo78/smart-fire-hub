package com.smartfirehub.ontology.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V77 마이그레이션이 dataset_ontology 테이블과 ontology IDENTITY 를 정상 생성했는지 확인하는 PG 스키마 시험. 리포지토리 계약이 아니라 PG
 * 스키마를 보므로 계약 테스트(DatasetOntologyRepositoryContractTest)에서 떼어 냈다(WD-711 — 계약 기반이 jOOQ 를 쓰지 않게).
 */
class DatasetOntologyV77MigrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  // raw dsl 은 GUC(app.tenant_id) 가 트랜잭션 시작에만 주입되므로 트랜잭션 안에서 돌린다.
  @Autowired private TransactionTemplate tx;

  @Test
  void V77_dataset_ontology_행을_저장하고_읽을수있다() {
    // 테이블 존재 + 컬럼 계약을 실제 삽입/조회 왕복으로 검증한다(항상-참 count 대신 의미 있는 단언).
    long datasetId = 998877L;
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.insertInto(table(name("dataset_ontology")))
                .set(field(name("dataset_id"), Long.class), datasetId)
                .set(field(name("ontology_id"), Long.class), 1L)
                .set(field(name("bound_by"), Long.class), 42L)
                .execute());
    try {
      Long readOntologyId =
          TenantRlsTestSupport.runInTenantTransaction(
              tx,
              DEFAULT_TEST_TENANT_ID,
              () ->
                  dsl.select(field(name("ontology_id"), Long.class))
                      .from(table(name("dataset_ontology")))
                      .where(field(name("dataset_id"), Long.class).eq(datasetId))
                      .fetchOne(0, Long.class));
      assertThat(readOntologyId).isEqualTo(1L);
    } finally {
      TenantRlsTestSupport.runInTenantTransaction(
          tx,
          DEFAULT_TEST_TENANT_ID,
          () ->
              dsl.deleteFrom(table(name("dataset_ontology")))
                  .where(field(name("dataset_id"), Long.class).eq(datasetId))
                  .execute());
    }
  }

  @Test
  void 신규_온톨로지_INSERT시_id가_자동발급된다() {
    // id를 지정하지 않고 INSERT하면 IDENTITY(START 2)로 2 이상이 발급되어야 한다.
    Long newId =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.insertInto(table(name("ontology")))
                    .set(field(name("domain"), String.class), "V77_IDENTITY_PROBE")
                    .set(field(name("schema_version"), Integer.class), 1)
                    .returning(field(name("id"), Long.class))
                    .fetchOne()
                    .get(field(name("id"), Long.class)));
    try {
      assertThat(newId).isGreaterThanOrEqualTo(2L);
    } finally {
      // 정리: 이 테스트가 만든 온톨로지 삭제(롤백 없음). 단언 실패 시에도 반드시 삭제되어야
      // 이후 재실행 시 domain UNIQUE 제약 위반(고아 행)이 발생하지 않는다.
      TenantRlsTestSupport.runInTenantTransaction(
          tx,
          DEFAULT_TEST_TENANT_ID,
          () ->
              dsl.deleteFrom(table(name("ontology")))
                  .where(field(name("id"), Long.class).eq(newId))
                  .execute());
    }
  }
}
