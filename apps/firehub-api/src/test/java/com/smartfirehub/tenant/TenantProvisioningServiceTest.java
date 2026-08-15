package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 신규 테넌트가 역할 0개로 잠기지 않는지 검증한다.
 *
 * <p>role 이 테넌트 스코프가 된 뒤로, 프로비저닝이 없으면 신규 테넌트의 사용자는 멤버십이
 * 있어도 권한 로딩이 0행이라 모든 API 가 403 이 된다.
 */
class TenantProvisioningServiceTest extends IntegrationTestBase {

  @Autowired private TenantProvisioningService provisioningService;
  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  private Long createdTenant;

  @AfterEach
  void cleanUp() {
    if (createdTenant != null) {
      // V99 로 이 4테이블에 RLS 가 걸렸다. 테스트 커넥션은 비특권 롤 app_tenant 라 정책이 그대로
      // 적용되므로, 트랜잭션 밖의 bare delete 는 GUC 가 비어 0행을 지우고 이어지는 tenant 삭제가
      // FK 로 터진다. 대상 테넌트 컨텍스트 트랜잭션 안에서 지운다.
      // 자식부터 지운다 — role_permission/report_template 이 tenant FK 를 잡고 있다.
      TenantRlsTestSupport.runInTenantTransaction(
          tx, createdTenant, () -> TenantRlsTestSupport.deleteRbacCascade(dsl, createdTenant));
      TenantRlsTestSupport.deleteTenants(dsl, createdTenant);
      createdTenant = null;
    }
  }

  @Test
  @DisplayName("신규 테넌트에 시스템 역할·권한매핑·내장 양식이 시드된다")
  void provisionsRolesPermissionsAndTemplates() {
    createdTenant = TenantRlsTestSupport.createActiveTenant(dsl, "provision");

    provisioningService.provisionDefaults(createdTenant);

    // 검증 조회도 RLS 대상이다. 대상 테넌트의 행은 대상 테넌트 컨텍스트에서, 원본 테넌트(1)의
    // 행은 테넌트 1 컨텍스트에서 세어야 한다 — 한 컨텍스트에서 양쪽을 세면 상대편이 항상 0 이라
    // 단언이 공허해진다.
    Integer roles =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            createdTenant,
            () ->
                (Integer)
                    dsl.fetchValue(
                        "select count(*)::int from role where tenant_id = ? and is_system = true",
                        createdTenant));
    Integer sourceRoles =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            DEFAULT_TEST_TENANT_ID,
            () ->
                (Integer)
                    dsl.fetchValue(
                        "select count(*)::int from role where tenant_id = 1 and is_system = true"));
    assertThat(roles).isEqualTo(sourceRoles).isPositive();

    Integer mappings =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            createdTenant,
            () ->
                (Integer)
                    dsl.fetchValue(
                        "select count(*)::int from role_permission where tenant_id = ?",
                        createdTenant));
    assertThat(mappings).isPositive();

    Integer templates =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            createdTenant,
            () ->
                (Integer)
                    dsl.fetchValue(
                        "select count(*)::int from report_template where tenant_id = ?"
                            + " and user_id is null",
                        createdTenant));
    assertThat(templates).isPositive();

    // V98 은 컬럼 목록에서 style 을 빠뜨렸다. style 은 nullable 이라 마이그레이션이 실패하지 않아
    // 조용히 통과했지만, 원본 내장 양식 3건은 모두 style 이 채워져 있고 ProactiveJobAsyncRunner 가
    // 그 값을 AI 에 넘긴다 — 즉 신규 테넌트의 리포트만 문체가 달라진다. V100 이 이를 고쳤다.
    Integer styleless =
        TenantRlsTestSupport.runInTenantTransaction(
            tx,
            createdTenant,
            () ->
                (Integer)
                    dsl.fetchValue(
                        "select count(*)::int from report_template where tenant_id = ?"
                            + " and user_id is null and style is null",
                        createdTenant));
    assertThat(styleless)
        .as("복제된 내장 양식에 style 이 비어 있다 — provision_tenant_defaults 가 컬럼을 빠뜨렸다")
        .isZero();
  }

  @Test
  @DisplayName("두 번 호출해도 행이 중복되지 않는다 (멱등)")
  void isIdempotent() {
    createdTenant = TenantRlsTestSupport.createActiveTenant(dsl, "provision-idem");

    provisioningService.provisionDefaults(createdTenant);
    Integer after1 = countRoles();

    provisioningService.provisionDefaults(createdTenant);
    Integer after2 = countRoles();

    assertThat(after2).isEqualTo(after1);
  }

  /** 대상 테넌트 컨텍스트에서 그 테넌트의 역할 수를 센다(RLS 대상이라 트랜잭션이 필요하다). */
  private Integer countRoles() {
    return TenantRlsTestSupport.runInTenantTransaction(
        tx,
        createdTenant,
        () ->
            (Integer)
                dsl.fetchValue(
                    "select count(*)::int from role where tenant_id = ?", createdTenant));
  }
}
