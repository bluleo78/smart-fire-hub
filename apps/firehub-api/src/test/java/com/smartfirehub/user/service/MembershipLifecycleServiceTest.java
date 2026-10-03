package com.smartfirehub.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.exception.UserNotFoundException;
import com.smartfirehub.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 멤버십 단위 정지·재활성·제거와 잠금 방지 규칙(WD-2). 비트랜잭션, 새 테넌트 A·B.
 *
 * <p>구성: owner(A OWNER+ADMIN), admin(A ADMIN, B ACTIVE MEMBER), member(A MEMBER).
 */
class MembershipLifecycleServiceTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private UserService userService;
  /**
   * 스파이 — 동시성 테스트에서 {@code countActiveAdmins} 판정 직후 지연을 넣어 두 트랜잭션을 확실히 겹치게
   * 한다(SignupClosureTest 와 같은 기법). 다른 테스트에서는 실제 메서드를 그대로 호출한다.
   */
  @MockitoSpyBean private UserRepository userRepository;
  @Autowired private MembershipRepository membershipRepository;
  @Autowired private AuthService authService;
  @Autowired private TenantProvisioningService provisioningService;

  private long tenantA;
  private long tenantB;
  private long owner;
  private long admin;
  private long member;
  private String adminUsername;
  private final List<Long> users = new ArrayList<>();

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "wd2-life-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "wd2-life-b");
    provisioningService.provisionDefaults(tenantA);
    provisioningService.provisionDefaults(tenantB);
    owner = create(tenantA, "owner");
    dsl.execute(
        "update membership set role = 'OWNER' where user_id = ? and tenant_id = ?", owner, tenantA);
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, owner, tenantA, "ADMIN");
    admin = create(tenantA, "admin");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, admin, tenantA, "ADMIN");
    TenantRlsTestSupport.insertActiveMembership(dsl, admin, tenantB);
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, admin, tenantB, "USER");
    adminUsername = userRepository.findById(admin).orElseThrow().username();
    member = create(tenantA, "member");
  }

  @AfterEach
  void tearDown() {
    reset(userRepository);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    List<Runnable> steps = new ArrayList<>();
    users.forEach(
        id ->
            steps.add(
                () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, id, tenantA, tenantB)));
    steps.add(
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantA));
    steps.add(
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantB));
    TenantRlsTestSupport.cleanupAll(steps.toArray(Runnable[]::new));
  }

  private long create(long tenantId, String prefix) {
    String username = prefix + "-" + System.nanoTime() + "@example.com";
    long id =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                passwordEncoder,
                username,
                username,
                "Password123",
                prefix,
                tenantId)
            .id();
    users.add(id);
    return id;
  }

  private void inA(Runnable r) {
    TenantContext.runScoped(tenantA, r);
  }

  private boolean globalActive(long userId) {
    return userRepository.findById(userId).orElseThrow().isActive();
  }

  @Test
  void suspend_changesOnlyMembership_globalFlagUntouched() {
    inA(() -> userService.setUserActive(member, false, owner));

    assertThat(membershipRepository.findInTenant(member, tenantA).orElseThrow().status())
        .isEqualTo("SUSPENDED");
    assertThat(globalActive(member)).isTrue();
  }

  @Test
  void suspendedInA_canStillLoginToB() {
    // 핵심 회귀: 예전에는 전역 is_active 를 꺼서 B 로그인까지 막았다.
    TenantContext.runScoped(tenantA, () -> userService.setUserActive(admin, false, owner));

    var token = authService.login(new LoginRequest(adminUsername, "Password123"));

    assertThat(token.memberships()).extracting("tenantId").containsExactly(tenantB);
    assertThat(token.activeTenantId()).isEqualTo(tenantB);
  }

  /**
   * WD-3: 운영자가 전역 계정을 비활성화해도 isActive(멤버십)는 그대로 활성이고, accountActive 가 false 로 따로 온다. 멤버십까지 정지된 경우에도
   * 두 값이 서로를 가리지 않는다.
   */
  @Test
  void globallyDeactivated_listAndDetail_exposeAccountActiveSeparately() {
    dsl.execute("update \"user\" set is_active = false where id = ?", member);

    var row =
        TenantContext.runScopedGet(tenantA, () -> userService.getUsers(null, 0, 50))
            .content()
            .stream()
            .filter(u -> u.id().equals(member))
            .findFirst()
            .orElseThrow();
    assertThat(row.isActive()).isTrue();
    assertThat(row.accountActive()).isFalse();
    var detail = TenantContext.runScopedGet(tenantA, () -> userService.getUserById(member));
    assertThat(detail.isActive()).isTrue();
    assertThat(detail.accountActive()).isFalse();
    assertThat(detail.lastActiveAdmin()).isFalse();

    inA(() -> userService.setUserActive(member, false, owner));
    var suspended = TenantContext.runScopedGet(tenantA, () -> userService.getUserById(member));
    assertThat(suspended.isActive()).isFalse();
    assertThat(suspended.accountActive()).isFalse();
  }

  /** WD-3: 평범한 활성 계정은 accountActive=true, 자기 프로필(/users/me)은 isActive 와 같은 전역 값이다. */
  @Test
  void activeAccount_accountActiveTrue_andMyProfileMirrorsGlobal() {
    var detail = TenantContext.runScopedGet(tenantA, () -> userService.getUserById(member));
    assertThat(detail.accountActive()).isTrue();
    var me = userService.getMyProfile(member);
    assertThat(me.accountActive()).isEqualTo(me.isActive()).isTrue();
  }

  @Test
  void suspendedMember_isListedAndReactivatable() {
    inA(() -> userService.setUserActive(member, false, owner));

    var page = TenantContext.runScopedGet(tenantA, () -> userService.getUsers(null, 0, 50));
    var row = page.content().stream().filter(u -> u.id().equals(member)).findFirst().orElseThrow();
    assertThat(row.isActive()).isFalse();
    var detail = TenantContext.runScopedGet(tenantA, () -> userService.getUserById(member));
    assertThat(detail.isActive()).isFalse();
    assertThat(detail.membershipRole()).isEqualTo("MEMBER");

    inA(() -> userService.setUserActive(member, true, owner));
    assertThat(membershipRepository.findInTenant(member, tenantA).orElseThrow().isActive())
        .isTrue();
  }

  @Test
  void remove_deletesOnlyThisTenantMembershipAndRoles() {
    TenantContext.runScoped(tenantA, () -> userService.removeMember(admin, owner));

    assertThat(membershipRepository.findInTenant(admin, tenantA)).isEmpty();
    assertThat(membershipRepository.findInTenant(admin, tenantB)).isPresent();
    Integer rolesA =
        inTenantFixture(
            tenantA,
            () -> dsl.fetchCount(dsl.selectOne().from("user_role").where("user_id = ?", admin)));
    Integer rolesB =
        inTenantFixture(
            tenantB,
            () -> dsl.fetchCount(dsl.selectOne().from("user_role").where("user_id = ?", admin)));
    assertThat(rolesA).isZero();
    assertThat(rolesB).isEqualTo(1); // B 에서는 USER 1개만 가진다(setUp)
    assertThat(userRepository.findById(admin)).isPresent(); // 계정 유지
  }

  @Test
  void self_suspendAndRemove_rejected() {
    assertThatThrownBy(() -> inA(() -> userService.setUserActive(admin, false, admin)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("자기 자신은 정지하거나 제거할 수 없습니다");
    assertThatThrownBy(() -> inA(() -> userService.removeMember(admin, admin)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void owner_suspendAndRemove_rejected() {
    assertThatThrownBy(() -> inA(() -> userService.setUserActive(owner, false, admin)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("워크스페이스 소유자는 정지하거나 제거할 수 없습니다");
    assertThatThrownBy(() -> inA(() -> userService.removeMember(owner, admin)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void lastActiveAdmin_rejected_whenOtherAdminSuspendedInThisTenant() {
    // owner 의 ADMIN 을 빼서 A 의 ADMIN 을 admin 하나로 만든 뒤, 다른 ADMIN(member 에 부여) 을 정지 상태로 둔다.
    inTenantFixture(
        tenantA,
        () ->
            dsl.execute(
                "delete from user_role where user_id = ? and role_id = (select id from role where name = 'ADMIN')",
                owner));
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, member, tenantA, "ADMIN");
    membershipRepository.updateStatus(member, tenantA, "SUSPENDED");

    // 정지된 ADMIN(member)은 활성으로 세지 않는다 → admin 이 마지막 활성 ADMIN.
    assertThatThrownBy(() -> inA(() -> userService.setUserActive(admin, false, owner)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("이 워크스페이스의 마지막 활성 ADMIN 은 정지하거나 제거할 수 없습니다");
    assertThatThrownBy(() -> inA(() -> userService.removeMember(admin, owner)))
        .isInstanceOf(IllegalStateException.class);
    // 정지된 ADMIN 자체의 제거는 활성 ADMIN 수를 줄이지 않으므로 허용된다.
    inA(() -> userService.removeMember(member, owner));
  }

  @Test
  void detail_flagsLastActiveAdmin() {
    inTenantFixture(
        tenantA,
        () ->
            dsl.execute(
                "delete from user_role where user_id = ? and role_id = (select id from role where name = 'ADMIN')",
                owner));
    var detail = TenantContext.runScopedGet(tenantA, () -> userService.getUserById(admin));
    assertThat(detail.lastActiveAdmin()).isTrue();
    var memberDetail = TenantContext.runScopedGet(tenantA, () -> userService.getUserById(member));
    assertThat(memberDetail.lastActiveAdmin()).isFalse();
  }

  @Test
  void otherTenantUser_notFound() {
    long outsider = create(tenantB, "outsider");
    assertThatThrownBy(() -> inA(() -> userService.removeMember(outsider, owner)))
        .isInstanceOf(UserNotFoundException.class);
    assertThatThrownBy(() -> inA(() -> userService.setUserActive(outsider, false, owner)))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  void suspendReactivateRemove_writeAuditLogs() {
    inA(() -> userService.setUserActive(member, false, owner));
    inA(() -> userService.setUserActive(member, true, owner));
    inA(() -> userService.removeMember(member, owner));

    List<String> actions =
        inTenantFixture(
            tenantA,
            () ->
                dsl.fetch(
                        "select action_type from audit_log where resource_id = ? order by id",
                        String.valueOf(member))
                    .getValues(0, String.class));
    assertThat(actions).containsExactly("MEMBER_SUSPEND", "MEMBER_REACTIVATE", "MEMBER_REMOVE");
  }

  /**
   * 리뷰 지적 3: 활성 ADMIN 이 둘(admin·member)일 때 서로를 동시에 정지하면, 잠금 없이는 두 트랜잭션이 모두
   * "활성 ADMIN 2명" 을 보고 통과해 활성 ADMIN 이 0 이 된다. 판정 직후 지연으로 겹침을 강제한다 — 테넌트 단위
   * advisory 잠금이 있으면 두 번째는 첫 번째 커밋을 본 뒤 409(IllegalStateException)가 된다.
   * 래치로 "둘 다 판정에 도달" 을 기다리지 않는 이유: 잠금이 있으면 두 번째는 판정에 도달하지 못해 교착된다.
   */
  @Test
  void concurrentMutualSuspend_ofTwoLastAdmins_onlyOneSucceeds() throws Exception {
    // owner 의 ADMIN 을 빼고 member 에 ADMIN 을 줘서, 활성 ADMIN = {admin, member} (owner 는 정지 불가라 제외).
    stripOwnerAdmin();
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, member, tenantA, "ADMIN");
    delayAfterCountActiveAdmins();

    // admin 이 member 를, member 가 admin 을 정지.
    List<String> results =
        runConcurrently(
            () -> inA(() -> userService.setUserActive(member, false, admin)),
            () -> inA(() -> userService.setUserActive(admin, false, member)));

    assertThat(results).containsExactlyInAnyOrder("OK", "CONFLICT");
    // user_role·role 은 RLS 대상이라 A 컨텍스트 안에서 센다(밖에서 세면 항상 0).
    reset(userRepository);
    assertThat(inTenantFixture(tenantA, () -> userRepository.countActiveAdmins(tenantA)))
        .isEqualTo(1);
  }

  /**
   * 리뷰 지적(#784 #785): 전역 비활성된 ADMIN(member) 은 활성 ADMIN 집합 밖이라, 남은 활성 ADMIN(admin) 이 그의
   * ADMIN 회수·정지·제거를 할 때 '마지막 활성 ADMIN' 거짓 409 가 나면 안 된다.
   */
  private void globallyDeactivatedAdminMember() {
    stripOwnerAdmin();
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, member, tenantA, "ADMIN");
    dsl.execute("update \"user\" set is_active = false where id = ?", member);
  }

  @Test
  void globallyInactiveAdmin_roleRevoke_notBlockedAsLastAdmin() {
    globallyDeactivatedAdminMember();
    inA(() -> userService.setUserRoles(member, List.of(), admin));
  }

  @Test
  void globallyInactiveAdmin_suspend_notBlockedAsLastAdmin() {
    globallyDeactivatedAdminMember();
    inA(() -> userService.setUserActive(member, false, admin));
    assertThat(membershipRepository.findInTenant(member, tenantA).orElseThrow().status())
        .isEqualTo("SUSPENDED");
  }

  @Test
  void globallyInactiveAdmin_remove_notBlockedAsLastAdmin() {
    globallyDeactivatedAdminMember();
    inA(() -> userService.removeMember(member, admin));
    assertThat(membershipRepository.findInTenant(member, tenantA)).isEmpty();
  }

  @Autowired private RoleRepository roleRepository;

  /** 테넌트 A 의 역할 id(이름으로). role 은 RLS 대상이라 A 컨텍스트 트랜잭션 안에서 조회한다. */
  private long roleIdInA(String roleName) {
    return inTenantFixture(tenantA, () -> roleRepository.findByName(roleName).orElseThrow().id());
  }

  /** A 에서 owner 의 ADMIN 을 빼서 "활성 ADMIN = admin 한 명" 상태를 만든다(owner 는 OWNER 라 별도 보호). */
  private void stripOwnerAdmin() {
    inTenantFixture(
        tenantA,
        () ->
            dsl.execute(
                "delete from user_role where user_id = ? and role_id = (select id from role where name = 'ADMIN')",
                owner));
  }

  private boolean hasAdminInA(long userId) {
    return inTenantFixture(tenantA, () -> userRepository.hasAdminRole(userId));
  }

  /** #785 핵심: 다른 관리자가 마지막 활성 ADMIN 의 ADMIN 을 빼면 409, 역할은 그대로. */
  @Test
  void lastActiveAdmin_roleDemotion_rejected() {
    stripOwnerAdmin();
    long userRole = roleIdInA("USER");

    assertThatThrownBy(() -> inA(() -> userService.setUserRoles(admin, List.of(userRole), owner)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("이 워크스페이스의 마지막 활성 ADMIN 에게서 ADMIN 역할을 뺄 수 없습니다");
    assertThat(hasAdminInA(admin)).isTrue();
  }

  /** 빈 배열(역할 전체 제거)도 같은 409 — NPE·500 이 아니다. */
  @Test
  void lastActiveAdmin_emptyRoles_rejected() {
    stripOwnerAdmin();

    assertThatThrownBy(() -> inA(() -> userService.setUserRoles(admin, List.of(), owner)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(hasAdminInA(admin)).isTrue();
  }

  /**
   * ADMIN 을 유지한 채 역할 목록만 바꾸는 요청은 거부되지 않고 그대로 저장된다. 주의: 이 테스트는 "과잉 차단 방지"
   * 중 removesAdmin=false 분기(ADMIN 이 요청에 남아 있으면 마지막-ADMIN 판정 자체를 건너뜀)만 증명한다. "정말
   * 마지막 ADMIN 일 때" 의 판정 정확성은 위 거부 테스트들이, 다른 ADMIN 이 남는 경우의 허용은
   * demotion_allowed_whenAnotherActiveAdminRemains 가 증명한다.
   */
  @Test
  void lastActiveAdmin_keepingAdmin_otherRolesChange_allowed() {
    stripOwnerAdmin();
    long adminRole = roleIdInA("ADMIN");

    inA(() -> userService.setUserRoles(admin, List.of(adminRole), owner)); // USER 를 뺀다

    assertThat(hasAdminInA(admin)).isTrue();
    Integer roleCount =
        inTenantFixture(
            tenantA,
            () -> dsl.fetchCount(dsl.selectOne().from("user_role").where("user_id = ?", admin)));
    assertThat(roleCount).isEqualTo(1);
  }

  /** 정지된 ADMIN 의 강등은 활성 ADMIN 수를 줄이지 않으므로 허용. */
  @Test
  void suspendedAdmin_roleDemotion_allowed() {
    stripOwnerAdmin();
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, member, tenantA, "ADMIN");
    membershipRepository.updateStatus(member, tenantA, "SUSPENDED");
    long userRole = roleIdInA("USER");

    inA(() -> userService.setUserRoles(member, List.of(userRole), admin));

    assertThat(hasAdminInA(member)).isFalse();
  }

  /** 다른 활성 ADMIN 이 남으면 강등 허용(setUp: owner·admin 둘 다 ADMIN). */
  @Test
  void demotion_allowed_whenAnotherActiveAdminRemains() {
    long userRole = roleIdInA("USER");

    inA(() -> userService.setUserRoles(admin, List.of(userRole), owner));

    assertThat(hasAdminInA(admin)).isFalse();
  }

  /**
   * 동시성: 활성 ADMIN 이 둘(admin·member)일 때 서로의 ADMIN 을 동시에 뺀다. countActiveAdmins 직후 지연으로
   * 겹침을 강제한다 — 잠금이 없으면 둘 다 "2명"을 보고 통과해 0 명이 된다.
   */
  @Test
  void concurrentMutualDemotion_ofTwoLastAdmins_onlyOneSucceeds() throws Exception {
    stripOwnerAdmin();
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, member, tenantA, "ADMIN");
    long userRole = roleIdInA("USER");
    delayAfterCountActiveAdmins();

    List<String> results =
        runConcurrently(
            () -> inA(() -> userService.setUserRoles(member, List.of(userRole), admin)),
            () -> inA(() -> userService.setUserRoles(admin, List.of(userRole), member)));

    assertThat(results).containsExactlyInAnyOrder("OK", "CONFLICT");
    reset(userRepository);
    assertThat(inTenantFixture(tenantA, () -> userRepository.countActiveAdmins(tenantA)))
        .isEqualTo(1);
  }

  /** 정지(setUserActive)와 역할 회수(setUserRoles)가 같은 잠금을 쓰는지 — 섞여 겹쳐도 0 명이 되지 않는다. */
  @Test
  void concurrentDemotionAndSuspend_shareTheSameLock() throws Exception {
    stripOwnerAdmin();
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, member, tenantA, "ADMIN");
    long userRole = roleIdInA("USER");
    delayAfterCountActiveAdmins();

    List<String> results =
        runConcurrently(
            () -> inA(() -> userService.setUserRoles(member, List.of(userRole), admin)),
            () -> inA(() -> userService.setUserActive(admin, false, member)));

    assertThat(results).containsExactlyInAnyOrder("OK", "CONFLICT");
    reset(userRepository);
    assertThat(inTenantFixture(tenantA, () -> userRepository.countActiveAdmins(tenantA)))
        .isEqualTo(1);
  }

  /** countActiveAdmins 판정 직후 700ms 지연 — 판정과 쓰기 사이 구간을 늘려 두 트랜잭션을 확실히 겹친다. */
  private void delayAfterCountActiveAdmins() {
    doAnswer(
            inv -> {
              Object count = inv.callRealMethod();
              Thread.sleep(700);
              return count;
            })
        .when(userRepository)
        .countActiveAdmins(anyLong());
  }

  /**
   * 두 작업을 동시에 시작해 결과를 "OK"/"CONFLICT"(IllegalStateException) 로 모은다. 래치로 "둘 다 판정 도달" 을
   * 기다리지 않는다 — 잠금이 있으면 두 번째는 판정에 도달하지 못해 교착된다.
   */
  private List<String> runConcurrently(Runnable first, Runnable second) {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<CompletableFuture<String>> futures =
          List.of(first, second).stream()
              .map(
                  task ->
                      CompletableFuture.supplyAsync(
                          () -> {
                            try {
                              start.await();
                              task.run();
                              return "OK";
                            } catch (IllegalStateException e) {
                              return "CONFLICT";
                            } catch (InterruptedException e) {
                              throw new IllegalStateException(e);
                            }
                          },
                          pool))
              .toList();
      start.countDown();
      return futures.stream().map(CompletableFuture::join).toList();
    } finally {
      pool.shutdownNow();
    }
  }
}
