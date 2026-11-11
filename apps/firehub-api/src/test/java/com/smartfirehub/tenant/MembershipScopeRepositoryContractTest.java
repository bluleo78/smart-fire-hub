package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.repository.AccessContractBase;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 멤버십 단위 정지/제거와 테넌트 범위 ADMIN 카운트의 저장소 계약.
 *
 * <p>비트랜잭션: 테넌트 둘(A, B)을 실제로 만들고 RLS 컨텍스트를 바꿔 가며 검증해야 한다. 공유 test DB 의 기본 테넌트(1)에는 다른 테스트의 ADMIN 이
 * 섞여 있어 카운트가 오염되므로 <b>새 테넌트 둘</b>만 쓴다.
 *
 * <p>인터페이스 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-485). 픽스처는 {@code AccessFixtures} seam 이라 PG·Tibero 모두 같은
 * 시나리오를 돈다(WD-706 — 시험 본문은 그대로 두고 픽스처만 seam 으로 옮겼다).
 */
abstract class MembershipScopeRepositoryContractTest extends AccessContractBase {

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract MembershipRepository membershipRepository();

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract UserRepository userRepository();

  /** 계약 대상 리포지토리 — 구현별 하위 클래스가 제공한다. */
  protected abstract PermissionRepository permissionRepository();

  private long tenantA;
  private long tenantB;
  private long admin1;
  private long admin2;

  @BeforeEach
  void setUp() {
    tenantA = fx.createTenant();
    tenantB = fx.createTenant();
    // 시스템 역할(USER/ADMIN…) 복제. OWNER 멤버십이 없으니 ADMIN 자동 배정은 일어나지 않는다.
    fx.provisionDefaults(tenantA);
    fx.provisionDefaults(tenantB);
    admin1 = createIn(tenantA, "wd2-admin1");
    admin2 = createIn(tenantA, "wd2-admin2");
    grantRole(admin1, tenantA, "ADMIN");
    grantRole(admin2, tenantA, "ADMIN");
    // admin2 는 B 에도 ACTIVE 멤버 + ADMIN — B 의 행이 A 판정에 섞이면 안 된다.
    fx.createMembership(admin2, tenantB, "ACTIVE");
    grantRole(admin2, tenantB, "ADMIN");
  }

  /** 그 테넌트의 ACTIVE 멤버 + USER 역할 사용자(옛 TestUsers.createMember 와 같은 모양). */
  private long createIn(long tenantId, String prefix) {
    String username = prefix + "-" + System.nanoTime() + "@example.com";
    long userId = fx.createUser(username, username);
    fx.createMembership(userId, tenantId, "ACTIVE");
    grantRole(userId, tenantId, "USER");
    return userId;
  }

  /** 이름으로 그 테넌트의 역할을 붙인다(역할이 없으면 픽스처가 예외 — 프로비저닝 선행 필요). */
  private void grantRole(long userId, long tenantId, String roleName) {
    fx.grantRole(tenantId, userId, fx.roleIdByName(tenantId, roleName));
  }

  private int activeAdminsIn(long tenantId) {
    return fx.inTenant(tenantId, () -> userRepository().countActiveAdmins(tenantId));
  }

  @Test
  void countActiveAdmins_excludesSuspendedMembershipInSameTenant() {
    assertThat(activeAdminsIn(tenantA)).isEqualTo(2);

    membershipRepository().updateStatus(admin2, tenantA, "SUSPENDED");

    // admin2 는 A 에서 정지 — B 에서 ACTIVE ADMIN 이어도 A 의 카운트에 들어가면 안 된다.
    assertThat(activeAdminsIn(tenantA)).isEqualTo(1);
    assertThat(activeAdminsIn(tenantB)).isEqualTo(1);
  }

  @Test
  void updateStatusAndDelete_touchOnlyGivenTenant() {
    membershipRepository().updateStatus(admin2, tenantA, "SUSPENDED");
    assertThat(membershipRepository().findInTenant(admin2, tenantA).orElseThrow().status())
        .isEqualTo("SUSPENDED");
    assertThat(membershipRepository().findInTenant(admin2, tenantB).orElseThrow().status())
        .isEqualTo("ACTIVE");

    int deleted = membershipRepository().delete(admin2, tenantA);
    int rolesDeleted =
        fx.inTenant(tenantA, () -> userRepository().deleteRolesInTenant(admin2, tenantA));

    assertThat(deleted).isEqualTo(1);
    assertThat(rolesDeleted).isEqualTo(2); // USER + ADMIN
    assertThat(membershipRepository().findInTenant(admin2, tenantA)).isEmpty();
    assertThat(membershipRepository().findInTenant(admin2, tenantB)).isPresent();
    // B 의 역할은 그대로다.
    int rolesInB = fx.countUserRoles(tenantB, admin2);
    assertThat(rolesInB).isEqualTo(1); // B 에는 ADMIN 하나만 붙였다(USER 는 A 에서만)
  }

  @Test
  void permissions_emptyWhenMembershipSuspended_otherTenantIntact() {
    membershipRepository().updateStatus(admin2, tenantA, "SUSPENDED");

    var permsA =
        fx.inTenant(tenantA, () -> permissionRepository().findPermissionCodesByUserId(admin2));
    var permsB =
        fx.inTenant(tenantB, () -> permissionRepository().findPermissionCodesByUserId(admin2));

    assertThat(permsA).isEmpty();
    assertThat(permsB).contains("user:write");
  }

  @Test
  void findInTenant_batch_returnsSuspendedToo() {
    membershipRepository().updateStatus(admin2, tenantA, "SUSPENDED");
    var map = membershipRepository().findInTenant(tenantA, List.of(admin1, admin2));
    assertThat(map.get(admin1).isActive()).isTrue();
    assertThat(map.get(admin2).isActive()).isFalse();
  }
}
