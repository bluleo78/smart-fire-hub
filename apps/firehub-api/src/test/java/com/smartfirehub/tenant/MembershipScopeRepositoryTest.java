package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 멤버십 단위 정지/제거와 테넌트 범위 ADMIN 카운트의 저장소 계약.
 *
 * <p>비트랜잭션: 테넌트 둘(A, B)을 실제로 만들고 RLS 컨텍스트를 바꿔 가며 검증해야 한다. 공유 test DB
 * 의 기본 테넌트(1)에는 다른 테스트의 ADMIN 이 섞여 있어 카운트가 오염되므로 <b>새 테넌트 둘</b>만 쓴다.
 */
class MembershipScopeRepositoryTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private MembershipRepository membershipRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private PermissionRepository permissionRepository;
  @Autowired private TenantProvisioningService provisioningService;

  private long tenantA;
  private long tenantB;
  private long admin1;
  private long admin2;

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "wd2-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "wd2-b");
    // 시스템 역할(USER/ADMIN…) 복제. OWNER 멤버십이 없으니 ADMIN 자동 배정은 일어나지 않는다.
    provisioningService.provisionDefaults(tenantA);
    provisioningService.provisionDefaults(tenantB);
    admin1 = createIn(tenantA, "wd2-admin1");
    admin2 = createIn(tenantA, "wd2-admin2");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, admin1, tenantA, "ADMIN");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, admin2, tenantA, "ADMIN");
    // admin2 는 B 에도 ACTIVE 멤버 + ADMIN — B 의 행이 A 판정에 섞이면 안 된다.
    TenantRlsTestSupport.insertActiveMembership(dsl, admin2, tenantB);
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, admin2, tenantB, "ADMIN");
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.cleanupAll(
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, admin1, tenantA, tenantB),
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, admin2, tenantA, tenantB),
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantA),
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantB));
  }

  private long createIn(long tenantId, String prefix) {
    String username = prefix + "-" + System.nanoTime() + "@example.com";
    return TestUsers.createMember(
            dsl,
            fixtureTransactionTemplate,
            passwordEncoder,
            username,
            username,
            "Password123",
            prefix,
            tenantId)
        .id();
  }

  private int activeAdminsIn(long tenantId) {
    return inTenantFixture(tenantId, () -> userRepository.countActiveAdmins(tenantId));
  }

  @Test
  void countActiveAdmins_excludesSuspendedMembershipInSameTenant() {
    assertThat(activeAdminsIn(tenantA)).isEqualTo(2);

    membershipRepository.updateStatus(admin2, tenantA, "SUSPENDED");

    // admin2 는 A 에서 정지 — B 에서 ACTIVE ADMIN 이어도 A 의 카운트에 들어가면 안 된다.
    assertThat(activeAdminsIn(tenantA)).isEqualTo(1);
    assertThat(activeAdminsIn(tenantB)).isEqualTo(1);
  }

  @Test
  void updateStatusAndDelete_touchOnlyGivenTenant() {
    membershipRepository.updateStatus(admin2, tenantA, "SUSPENDED");
    assertThat(membershipRepository.findInTenant(admin2, tenantA).orElseThrow().status())
        .isEqualTo("SUSPENDED");
    assertThat(membershipRepository.findInTenant(admin2, tenantB).orElseThrow().status())
        .isEqualTo("ACTIVE");

    int deleted = membershipRepository.delete(admin2, tenantA);
    int rolesDeleted =
        inTenantFixture(tenantA, () -> userRepository.deleteRolesInTenant(admin2, tenantA));

    assertThat(deleted).isEqualTo(1);
    assertThat(rolesDeleted).isEqualTo(2); // USER + ADMIN
    assertThat(membershipRepository.findInTenant(admin2, tenantA)).isEmpty();
    assertThat(membershipRepository.findInTenant(admin2, tenantB)).isPresent();
    // B 의 역할은 그대로다.
    Integer rolesInB =
        inTenantFixture(
            tenantB,
            () -> dsl.fetchCount(dsl.selectOne().from("user_role").where("user_id = ?", admin2)));
    assertThat(rolesInB).isEqualTo(1); // B 에는 ADMIN 하나만 붙였다(USER 는 A 에서만)
  }

  @Test
  void permissions_emptyWhenMembershipSuspended_otherTenantIntact() {
    membershipRepository.updateStatus(admin2, tenantA, "SUSPENDED");

    var permsA =
        inTenantFixture(tenantA, () -> permissionRepository.findPermissionCodesByUserId(admin2));
    var permsB =
        inTenantFixture(tenantB, () -> permissionRepository.findPermissionCodesByUserId(admin2));

    assertThat(permsA).isEmpty();
    assertThat(permsB).contains("user:write");
  }

  @Test
  void findInTenant_batch_returnsSuspendedToo() {
    membershipRepository.updateStatus(admin2, tenantA, "SUSPENDED");
    var map = membershipRepository.findInTenant(tenantA, List.of(admin1, admin2));
    assertThat(map.get(admin1).isActive()).isTrue();
    assertThat(map.get(admin2).isActive()).isFalse();
  }
}
