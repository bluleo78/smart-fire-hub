package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V88 dataset 도메인 RLS 격리를 양방향으로 검증한다.
 *
 * <p>공유 테스트 DB 라 전체 카운트 비교 단언은 쓸 수 없다(다른 세션이 동시에 쓴다). 실행마다
 * 고유한 테넌트 두 개를 만들어 그 범위에서만 단언하고, 만든 행은 자기 테넌트 컨텍스트에서 지운다.
 *
 * <p>이 클래스에 클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — 테넌트를 바꿔 가며 여러
 * 트랜잭션을 열어야 하고, 하나의 테스트 트랜잭션에 묶이면 GUC 가 처음 값으로 고정된다.
 */
class DatasetDomainRlsTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DatasetRepository datasetRepository;

  private TransactionTemplate tx;
  private long tenantA;
  private long tenantB;
  private Long createdUserId;

  @BeforeEach
  void createTenants() {
    tx = new TransactionTemplate(transactionManager);
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "rls-ds-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "rls-ds-b");
    // dataset.created_by 는 NOT NULL 이다. "user" 는 전역 테이블(RLS 없음)이라 그냥 만든다.
    createdUserId = TenantRlsTestSupport.insertUser(dsl, "rlsuser");
  }

  @AfterEach
  void cleanup() {
    // RLS 스코프 안에서 지우므로 자기 테넌트 행만 지워진다(남의 행은 애초에 보이지 않는다).
    deleteOwnRows(tenantA);
    deleteOwnRows(tenantB);
    TenantRlsTestSupport.deleteUser(dsl, createdUserId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
    TenantContext.clear();
  }

  @Test
  void datasetIsVisibleToOwnerAndInvisibleToOtherTenant() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "dataset", () -> insertDataset("격리검증-A"));
  }

  @Test
  void datasetCategoryIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "dataset_category", () -> insertCategory("카테고리-A"));
  }

  @Test
  void datasetColumnFollowsParentIsolation() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx,
        dsl,
        tenantA,
        tenantB,
        "dataset_column",
        () -> insertColumn(insertDataset("자식격리-A"), "col_a"));
  }

  @Test
  void insertWithMismatchedTenantIsRejectedByWithCheck() {
    // WITH CHECK 는 컨텍스트와 다른 tenant_id 의 INSERT 를 거부해야 한다. 거부하지 않으면
    // 한 테넌트가 남의 테넌트에 행을 심을 수 있다(격리가 읽기에만 걸린 상태).
    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                TenantRlsTestSupport.runInTenantTransaction(
                    tx,
                    tenantA,
                    () ->
                        dsl.insertInto(table(name("dataset")))
                            .set(field(name("name"), String.class), "교차삽입시도")
                            .set(field(name("table_name"), String.class), "tbl_cross_" + tenantB)
                            .set(field(name("storage_type"), String.class), "TABLE")
                            .set(field(name("origin_type"), String.class), "SOURCE")
                            .set(field(name("created_by"), Long.class), createdUserId)
                            .set(field(name("tenant_id"), Long.class), tenantB)
                            .execute()));

    assertThat(thrown)
        .as("다른 테넌트 id 로 INSERT 가 통과하면 WITH CHECK 가 없는 것이다")
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void readWithoutTenantContextSeesNothing() {
    // fail-closed 확인: 컨텍스트가 없으면 GUC 가 비고 정책이 전 행을 차단해야 한다.
    Long datasetId = TenantRlsTestSupport.runInTenantTransaction(tx, tenantA, () -> insertDataset("무컨텍스트"));

    TenantContext.clear();
    Boolean visible =
        tx.execute(status -> TenantRlsTestSupport.rowExists(dsl, "dataset", "id", datasetId));

    assertThat(visible).as("테넌트 컨텍스트 없이 RLS 테이블이 보이면 fail-open 이다").isFalse();
  }

  @Test
  void twoTenantsCanShareTableNameNowThatDataSchemaIsSeparated() {
    // P3-b2 T5(V112)가 이 테스트를 뒤집었다 — 예전 이름은
    // twoTenantsCannotShareTableNameWhileDataSchemaIsShared 였고 "누군가 유니크를 앞당겨
    // 접는 것"을 막는 가드였다(idx_dataset_table_name 이 전역 유니크로 남아 있어야 한다를
    // 고정). 그 근거(DataSchema.current() 가 상수 "data" 라 두 테넌트가 같은 물리 테이블을
    // 공유한다)는 P3-b2 T1(커밋 266c002b)이 테넌트별 물리 스키마로 갈라놓으면서 사라졌다.
    //
    // 지금은 두 테넌트가 같은 table_name 을 골라도 물리적으로 서로 다른 테이블
    // (data_t{a}."foo" 와 data_t{b}."foo")을 가리키므로 카탈로그 행 레벨에서도 충돌할
    // 이유가 없다 — V112 가 idx_dataset_table_name 을 (tenant_id, table_name) 으로 접어
    // 그 사실을 인덱스 계층에도 반영한다. 같은 테넌트 안 중복은 여전히 거부돼야 한다(그
    // 부분 유니크는 그대로 살아 있다).
    //
    // DatasetService.createDataset 을 타지 않는 이유: 이 테스트가 검증하려는 것은 인덱스
    // 계층 자체의 판정이고, 서비스 경로는 그 앞에 물리 테이블 DDL 이 섞여 원인이 흐려진다.
    // 실제 DDL 경로(DataTableService.createTable)까지 포함한 회귀는
    // DataTableServiceTenantUniqueTest 가 별도로 고정한다(그쪽이 이 밴드에서 더 중요한
    // 데이터 손실 부재 단언을 행 단위로 한다).
    String sharedTableName = "tbl_shared_" + TenantRlsTestSupport.nextTenantId();

    TenantRlsTestSupport.runInTenantTransaction(
        tx, tenantA, () -> insertDatasetWithTableName("공유이름-A", sharedTableName));

    // 사전검사는 RLS 스코프라 남의 테넌트 행을 보지 못한다 — 인덱스가 이제 테넌트 스코프이므로
    // 이 사전검사와 인덱스 판정이 처음으로 일치한다(V109 Javadoc 이 예고한 거짓 제약의 제거).
    Boolean visibleToB =
        TenantRlsTestSupport.runInTenantTransaction(
            tx, tenantB, () -> datasetRepository.existsByTableName(sharedTableName));
    assertThat(visibleToB).as("RLS 가 남의 테넌트 table_name 을 숨겨야 한다").isFalse();

    // 핵심 단언: 물리 스키마가 분리됐으므로 B 도 같은 이름으로 인덱스 계층에서 거부 없이
    // 만들 수 있어야 한다.
    Long bId =
        TenantRlsTestSupport.runInTenantTransaction(
            tx, tenantB, () -> insertDatasetWithTableName("공유이름-B", sharedTableName));
    assertThat(bId).as("테넌트 B 가 같은 table_name 으로 카탈로그 행을 만들 수 있어야 한다").isNotNull();

    // 같은 테넌트 안 중복은 여전히 거부돼야 한다((tenant_id, table_name) 유니크가 그 부분은
    // 그대로 지킨다).
    Throwable sameTenantDuplicate =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                TenantRlsTestSupport.runInTenantTransaction(
                    tx, tenantA, () -> insertDatasetWithTableName("공유이름-A2", sharedTableName)));

    assertThat(sameTenantDuplicate)
        .as("같은 테넌트 안 중복 table_name 은 여전히 거부돼야 한다")
        .isInstanceOf(DataAccessException.class);
  }

  // ── 픽스처 ────────────────────────────────────────────────────────────


  /** tenant_id 는 명시하지 않는다 — DEFAULT 가 GUC 에서 채우는 것을 함께 검증하기 위함이다. */
  private Long insertDataset(String namePrefix) {
    return insertDatasetWithTableName(
        namePrefix, "tbl_rls_" + TenantRlsTestSupport.nextTenantId());
  }

  /** table_name 을 호출자가 정하는 버전. 두 테넌트가 같은 이름을 쓰는 시나리오에 필요하다. */
  private Long insertDatasetWithTableName(String namePrefix, String tableName) {
    long suffix = TenantRlsTestSupport.nextTenantId();
    return dsl.insertInto(table(name("dataset")))
        .set(field(name("name"), String.class), namePrefix + "-" + suffix)
        .set(field(name("table_name"), String.class), tableName)
        .set(field(name("storage_type"), String.class), "TABLE")
        .set(field(name("origin_type"), String.class), "SOURCE")
        .set(field(name("created_by"), Long.class), createdUserId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private Long insertCategory(String namePrefix) {
    long suffix = TenantRlsTestSupport.nextTenantId();
    return dsl.insertInto(table(name("dataset_category")))
        .set(field(name("name"), String.class), namePrefix + "-" + suffix)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private Long insertColumn(Long datasetId, String columnName) {
    return dsl.insertInto(table(name("dataset_column")))
        .set(field(name("dataset_id"), Long.class), datasetId)
        .set(field(name("column_name"), String.class), columnName)
        .set(field(name("data_type"), String.class), "TEXT")
        .set(field(name("is_nullable"), Boolean.class), true)
        .set(field(name("is_indexed"), Boolean.class), false)
        .set(field(name("is_primary_key"), Boolean.class), false)
        .set(field(name("column_order"), Integer.class), 1)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  /** 해당 테넌트 컨텍스트에서 이 테스트가 만든 행을 지운다(RLS 스코프라 남의 행은 못 지운다). */
  private void deleteOwnRows(long tenantId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () -> {
          dsl.deleteFrom(table(name("dataset_column"))).execute();
          dsl.deleteFrom(table(name("dataset"))).execute();
          dsl.deleteFrom(table(name("dataset_category"))).execute();
        });
  }
}
