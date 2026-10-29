package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code provision_tenant_defaults(bigint)} 가 신규 테넌트에 기본 데이터셋 카테고리를 시드하는지 검증한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — GUC 는 트랜잭션 로컬이라, 프로비저닝 호출과 검증 조회가 같은 트랜잭션에 묶이면 RLS 를
 * 실제로 통과하는지 알 수 없다.
 *
 * <p>검증 조회는 {@link TenantContext#runScopedGet} 만으로는 부족하다 — 그 메서드는 스레드로컬만 세팅할 뿐이고, RLS GUC({@code
 * app.tenant_id})는 {@code TenantAwareTransactionManager.doBegin} 이 실제 트랜잭션이 열릴 때만 주입한다. 그래서 {@link
 * TenantRlsTestSupport#runInTenantTransaction} 으로 스레드로컬 설정과 트랜잭션 시작을 함께 묶는다.
 */
class TenantProvisioningSeedTest extends IntegrationTestBase {

  @Autowired private TenantProvisioningService tenantProvisioningService;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;

  @BeforeEach
  void setUp() {
    tx = new TransactionTemplate(transactionManager);
  }

  /**
   * 신규 테넌트가 기본 데이터셋 카테고리 3건을 받는지 검증한다.
   *
   * <p>설계서 §4 는 카테고리 복제를 결정했지만 V98/V100 함수 본문은 role·role_permission· report_template 만 채웠다 — 신규
   * 테넌트는 카테고리 0개로 시작한다. 누출이 아니라 기능 회귀다.
   */
  @Test
  void provisionDefaults_seedsDatasetCategories() {
    long tenantId = createTenantRow();

    tenantProvisioningService.provisionDefaults(tenantId);

    List<String> names =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            tenantId,
            () ->
                dsl.select(field(name("dataset_category", "name"), String.class))
                    .from(table(name("dataset_category")))
                    .orderBy(field(name("dataset_category", "name")))
                    .fetchInto(String.class));
    assertThat(names).containsExactlyInAnyOrder("행정", "운영", "통계");
  }

  /** 재실행해도 카테고리가 중복되지 않는다(운영자가 안심하고 다시 부를 수 있어야 한다). */
  @Test
  void provisionDefaults_categorySeedIsIdempotent() {
    long tenantId = createTenantRow();

    tenantProvisioningService.provisionDefaults(tenantId);
    tenantProvisioningService.provisionDefaults(tenantId);

    Integer count =
        TenantRlsTestSupport.runInTenantTransaction(
            tx, tenantId, () -> dsl.fetchCount(table(name("dataset_category"))));
    assertThat(count).isEqualTo(3);
  }

  /**
   * WD-192: 새 워크스페이스의 시스템 ADMIN 역할도 스마트 작업 관리 권한(proactive:admin)을 받는다. V143 은 이미 있는 테넌트에만 부여하고, 이후
   * 테넌트는 프로비저닝이 기본 테넌트의 시스템 역할 권한을 복제해서 받는다 — 함수를 다시 정의하지 않은 근거를 여기서 확인한다.
   */
  @Test
  void provisionDefaults_systemAdminGetsProactiveAdmin() {
    long tenantId = createTenantRow();
    try {
      tenantProvisioningService.provisionDefaults(tenantId);
      Integer count =
          TenantRlsTestSupport.runInTenantTransaction(
              tx,
              tenantId,
              () ->
                  dsl.fetchCount(
                      dsl.selectOne()
                          .from(table(name("role_permission")))
                          .join(table(name("role")))
                          .on(
                              field(name("role", "id"), Long.class)
                                  .eq(field(name("role_permission", "role_id"), Long.class)))
                          .join(table(name("permission")))
                          .on(
                              field(name("permission", "id"), Long.class)
                                  .eq(field(name("role_permission", "permission_id"), Long.class)))
                          .where(field(name("role", "tenant_id"), Long.class).eq(tenantId))
                          .and(field(name("role", "name"), String.class).eq("ADMIN"))
                          .and(field(name("role", "is_system"), Boolean.class).isTrue())
                          .and(
                              field(name("permission", "code"), String.class)
                                  .eq("proactive:admin"))));
      assertThat(count).isEqualTo(1);
    } finally {
      TenantRlsTestSupport.deleteProvisionedTenantCascade(dsl, tx, tenantId);
    }
  }

  /**
   * WD-199·WD-433: 새 워크스페이스의 시스템 ADMIN 역할도 분석 자산 관리 권한(analytics:admin, V144)과 알림 관리 권한
   * (notification:admin, V151)을 받는다. 두 마이그레이션은 이미 있는 테넌트에만 부여하고, 이후 테넌트는 프로비저닝이 기본 테넌트의 시스템 역할 권한을
   * 복제해서 받는다 — 함수를 다시 정의하지 않은 근거를 여기서 확인한다.
   */
  @ParameterizedTest
  @ValueSource(strings = {"analytics:admin", "notification:admin"})
  void provisionDefaults_systemAdminGetsAdminPermission(String code) {
    long tenantId = createTenantRow();
    try {
      tenantProvisioningService.provisionDefaults(tenantId);
      Integer count =
          TenantRlsTestSupport.runInTenantTransaction(
              tx,
              tenantId,
              () ->
                  dsl.fetchCount(
                      dsl.selectOne()
                          .from(table(name("role_permission")))
                          .join(table(name("role")))
                          .on(
                              field(name("role", "id"), Long.class)
                                  .eq(field(name("role_permission", "role_id"), Long.class)))
                          .join(table(name("permission")))
                          .on(
                              field(name("permission", "id"), Long.class)
                                  .eq(field(name("role_permission", "permission_id"), Long.class)))
                          .where(field(name("role", "tenant_id"), Long.class).eq(tenantId))
                          .and(field(name("role", "name"), String.class).eq("ADMIN"))
                          .and(field(name("role", "is_system"), Boolean.class).isTrue())
                          .and(field(name("permission", "code"), String.class).eq(code))));
      assertThat(count).isEqualTo(1);
    } finally {
      TenantRlsTestSupport.deleteProvisionedTenantCascade(dsl, tx, tenantId);
    }
  }

  /** 검증 전용 테넌트 행을 만든다. slug 는 공유 test DB 에서의 충돌 방지를 위해 나노초로 유일화한다. */
  private long createTenantRow() {
    String slug = "p7a-seed-" + System.nanoTime();
    return dsl.insertInto(table(name("tenant")))
        .set(field(name("slug")), slug)
        .set(field(name("name")), slug)
        .set(field(name("status")), "ACTIVE")
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }
}
