package com.smartfirehub.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.OwnershipTransferResult;
import com.smartfirehub.user.exception.UserNotFoundException;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 워크스페이스 소유권 이전(WD-1132). 새 테넌트 A 에 owner(OWNER+ADMIN), admin(ADMIN), member(MEMBER, ADMIN 역할 없음)를
 * 둔다.
 *
 * <p>서비스({@link OwnershipTransferService})는 명시 tenantId 로 부르고, 웹 경로({@link
 * UserService#transferOwnership})는 테넌트 컨텍스트 안에서 부른다.
 */
class OwnershipTransferServiceTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private UserService userService;
  @Autowired private OwnershipTransferService transferService;
  @Autowired private MembershipRepository membershipRepository;
  @Autowired private TenantProvisioningService provisioningService;
  @Autowired private ObjectMapper objectMapper;

  private long tenantA;
  private long owner;
  private long admin;
  private long member;
  private String tenantName;
  private final List<Long> users = new ArrayList<>();

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "wd1132-xfer");
    provisioningService.provisionDefaults(tenantA);
    tenantName = dsl.fetchOne("select name from tenant where id = ?", tenantA).get(0, String.class);
    owner = create("owner");
    dsl.execute(
        "update membership set role = 'OWNER' where user_id = ? and tenant_id = ?", owner, tenantA);
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, owner, tenantA, "ADMIN");
    admin = create("admin");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, admin, tenantA, "ADMIN");
    member = create("member");
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    List<Runnable> steps = new ArrayList<>();
    users.forEach(
        id -> steps.add(() -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, id, tenantA)));
    steps.add(
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantA));
    TenantRlsTestSupport.cleanupAll(steps.toArray(Runnable[]::new));
  }

  private long create(String prefix) {
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
                tenantA)
            .id();
    users.add(id);
    return id;
  }

  private OwnershipTransferResult transfer(long target, String confirmName) {
    return TenantContext.runScopedGet(
        tenantA, () -> transferService.transfer(tenantA, target, confirmName));
  }

  private String roleOf(long userId) {
    return membershipRepository.findInTenant(userId, tenantA).orElseThrow().role();
  }

  private boolean hasAdminRole(long userId) {
    return inTenantFixture(
        tenantA,
        () ->
            dsl.fetchCount(
                    dsl.selectOne()
                        .from("user_role")
                        .where(
                            "user_id = ? and role_id = (select id from role where name = 'ADMIN')",
                            userId))
                > 0);
  }

  @Test
  void transfer_movesOwnerAndKeepsOldOwnerAdmin() {
    OwnershipTransferResult result = transfer(admin, tenantName);

    assertThat(roleOf(admin)).isEqualTo("OWNER");
    assertThat(roleOf(owner)).isEqualTo("ADMIN");
    assertThat(hasAdminRole(owner)).isTrue();
    assertThat(result.fromUserIds()).containsExactly(owner);
    assertThat(result.toUserId()).isEqualTo(admin);
    assertThat(result.grantedAdmin()).isFalse();
  }

  @Test
  void transfer_grantsAdminRoleWhenMissing() {
    OwnershipTransferResult result = transfer(member, tenantName);

    assertThat(result.grantedAdmin()).isTrue();
    assertThat(hasAdminRole(member)).isTrue();
    assertThat(roleOf(member)).isEqualTo("OWNER");
  }

  @Test
  void transfer_demotesAllOtherOwners() {
    // 옛 데이터: 소유자가 둘 — admin 도 OWNER
    dsl.execute(
        "update membership set role = 'OWNER' where user_id = ? and tenant_id = ?", admin, tenantA);

    OwnershipTransferResult result = transfer(member, tenantName);

    assertThat(result.fromUserIds()).containsExactlyInAnyOrder(owner, admin);
    assertThat(membershipRepository.findOwnerUserIds(tenantA)).containsExactly(member);
    assertThat(roleOf(owner)).isEqualTo("ADMIN");
    assertThat(roleOf(admin)).isEqualTo("ADMIN");
  }

  @Test
  void transfer_wrongConfirmName_400() {
    assertThatThrownBy(() -> transfer(admin, tenantName.toLowerCase()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(roleOf(owner)).isEqualTo("OWNER");
  }

  @Test
  void transfer_confirmNameSurroundingSpacesAllowed() {
    transfer(admin, "  " + tenantName + "  ");
    assertThat(roleOf(admin)).isEqualTo("OWNER");
  }

  /** 저장 이름이 앞뒤 공백을 가져도(" Acme "), 공백 없이 입력한 이름으로 통과한다 — 저장·입력 모두 trim 비교(WD-1132 리뷰). */
  @Test
  void transfer_storedNameWithSurroundingSpaces_matchesTrimmedInput() {
    dsl.execute("update tenant set name = ? where id = ?", " " + tenantName + " ", tenantA);

    transfer(admin, tenantName);

    assertThat(roleOf(admin)).isEqualTo("OWNER");
  }

  /**
   * 저장 이름 끝이 전각 공백(U+3000)이어도 공백 없는 입력으로 통과한다 — String.trim() 은 전각 공백을 못 걷어 웹(JS)과 갈렸다(WD-1132).
   */
  @Test
  void transfer_storedNameWithIdeographicSpace_matchesInput() {
    dsl.execute("update tenant set name = ? where id = ?", tenantName + "\u3000", tenantA);

    transfer(admin, tenantName);

    assertThat(roleOf(admin)).isEqualTo("OWNER");
  }

  /** 저장 이름 공백을 trim 해도 대소문자는 여전히 구분한다. */
  @Test
  void transfer_storedNameWithSpaces_stillCaseSensitive() {
    dsl.execute("update tenant set name = ? where id = ?", " " + tenantName + " ", tenantA);

    assertThatThrownBy(() -> transfer(admin, tenantName.toUpperCase()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 워크스페이스가 없으면 409(IllegalStateException)가 아니라 404 관례 예외다(WD-1132 리뷰). */
  @Test
  void transfer_unknownTenant_404() {
    assertThatThrownBy(
            () ->
                TenantContext.runScopedGet(
                    99_999_999L, () -> transferService.transfer(99_999_999L, admin, tenantName)))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).status().value())
        .isEqualTo(404);
  }

  @Test
  void transfer_nonMember_404() {
    long outsider = create("outsider");
    dsl.execute("delete from membership where user_id = ? and tenant_id = ?", outsider, tenantA);

    assertThatThrownBy(() -> transfer(outsider, tenantName))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  void transfer_suspendedMember_409() {
    membershipRepository.updateStatus(admin, tenantA, "SUSPENDED");

    assertThatThrownBy(() -> transfer(admin, tenantName)).isInstanceOf(IllegalStateException.class);
    assertThat(roleOf(owner)).isEqualTo("OWNER");
  }

  @Test
  void transfer_inactiveAccount_409() {
    dsl.execute("update \"user\" set is_active = false where id = ?", admin);

    assertThatThrownBy(() -> transfer(admin, tenantName)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void transfer_alreadySoleOwner_409() {
    assertThatThrownBy(() -> transfer(owner, tenantName)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void transferOwnership_callerNotOwner_403() {
    // ADMIN 이어도 소유자가 아니면 넘길 수 없다.
    assertThatThrownBy(
            () ->
                TenantContext.runScoped(
                    tenantA, () -> userService.transferOwnership(admin, member, tenantName)))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).status().value())
        .isEqualTo(403);
  }

  @Test
  void transferOwnership_self_400() {
    assertThatThrownBy(
            () ->
                TenantContext.runScoped(
                    tenantA, () -> userService.transferOwnership(owner, owner, tenantName)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void transferOwnership_writesOwnerTransferAudit() throws Exception {
    TenantContext.runScoped(
        tenantA, () -> userService.transferOwnership(owner, member, tenantName));

    List<org.jooq.Record> rows =
        inTenantFixture(
            tenantA,
            () ->
                dsl.fetch(
                    "select user_id, resource, metadata::text as metadata from audit_log"
                        + " where action_type = 'OWNER_TRANSFER' and resource_id = ?",
                    String.valueOf(member)));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("user_id", Long.class)).isEqualTo(owner);
    JsonNode meta = objectMapper.readTree(rows.get(0).get("metadata", String.class));
    assertThat(meta.get("toUserId").asLong()).isEqualTo(member);
    assertThat(meta.get("fromUserIds").get(0).asLong()).isEqualTo(owner);
    assertThat(meta.get("grantedAdmin").asBoolean()).isTrue();
  }

  /** ADMIN 을 새로 받은 사용자마다 ROLE_ASSIGN 감사가 한 행씩 남는다(새 소유자 + ADMIN 역할이 없던 옛 소유자). */
  @Test
  void transferOwnership_recordsRoleAssignForEachGrantedAdmin() throws Exception {
    // 옛 소유자 owner 에게서 ADMIN 역할을 미리 뗀다 — 이전이 둘 다에게 ADMIN 을 새로 준다.
    inTenantFixture(
        tenantA,
        () ->
            dsl.execute(
                "delete from user_role where user_id = ? and role_id ="
                    + " (select id from role where name = 'ADMIN')",
                owner));

    TenantContext.runScoped(
        tenantA, () -> userService.transferOwnership(owner, member, tenantName));

    List<org.jooq.Record> rows =
        inTenantFixture(
            tenantA,
            () ->
                dsl.fetch(
                    "select resource_id, user_id, metadata::text as metadata from audit_log"
                        + " where action_type = 'ROLE_ASSIGN' and resource_id in (?, ?)",
                    String.valueOf(member),
                    String.valueOf(owner)));
    assertThat(rows).hasSize(2);
    assertThat(rows.stream().map(r -> r.get("resource_id", String.class)))
        .containsExactlyInAnyOrder(String.valueOf(member), String.valueOf(owner));
    for (org.jooq.Record row : rows) {
      assertThat(row.get("user_id", Long.class)).isEqualTo(owner);
      JsonNode meta = objectMapper.readTree(row.get("metadata", String.class));
      assertThat(meta.get("added")).extracting(JsonNode::asText).containsExactly("ADMIN");
    }
  }

  /** 「지금 소유자」 이름은 멤버십·계정이 활성인 OWNER 만 보인다 — 정지된 OWNER 는 건너뛰고, 모두 정지면 null. */
  @Test
  void getUserById_workspaceOwnerName_skipsSuspendedOwner() {
    long second = create("second");
    dsl.execute(
        "update membership set role = 'OWNER' where user_id = ? and tenant_id = ?",
        second,
        tenantA);
    // 먼저(user_id 가 작은) 소유자를 정지하면 활성인 두 번째 소유자 이름이 나온다
    dsl.execute(
        "update membership set status = 'SUSPENDED' where user_id = ? and tenant_id = ?",
        owner,
        tenantA);
    String secondName =
        dsl.fetchOne("select name from \"user\" where id = ?", second).get(0, String.class);

    String shown =
        TenantContext.runScopedGet(
            tenantA, () -> userService.getUserById(member, second).workspaceOwnerName());
    assertThat(shown).isEqualTo(secondName);

    dsl.execute(
        "update membership set status = 'SUSPENDED' where user_id = ? and tenant_id = ?",
        second,
        tenantA);
    String none =
        TenantContext.runScopedGet(
            tenantA, () -> userService.getUserById(member, second).workspaceOwnerName());
    assertThat(none).isNull();
  }

  /** 정지(멤버십 SUSPENDED)·비활성(계정) 옛 공동 소유자는 강등만 되고 ADMIN 을 받지 않는다. 활성 옛 소유자는 기존처럼 받는다(WD-1158). */
  @Test
  void transfer_doesNotGrantAdminToInactiveOldOwner() {
    long suspended = create("suspended");
    long deactivated = create("deactivated");
    long activeOld = create("activeold");
    for (long id : new long[] {suspended, deactivated, activeOld}) {
      dsl.execute(
          "update membership set role = 'OWNER' where user_id = ? and tenant_id = ?", id, tenantA);
    }
    membershipRepository.updateStatus(suspended, tenantA, "SUSPENDED");
    dsl.execute("update \"user\" set is_active = false where id = ?", deactivated);

    OwnershipTransferResult result = transfer(member, tenantName);

    assertThat(roleOf(suspended)).isEqualTo("ADMIN");
    assertThat(roleOf(deactivated)).isEqualTo("ADMIN");
    assertThat(roleOf(activeOld)).isEqualTo("ADMIN");
    assertThat(hasAdminRole(suspended)).isFalse();
    assertThat(hasAdminRole(deactivated)).isFalse();
    assertThat(hasAdminRole(activeOld)).isTrue();
    assertThat(result.grantedAdminUserIds())
        .containsExactlyInAnyOrder(member, activeOld)
        .doesNotContain(suspended, deactivated);
  }

  /** TenantContext 가 다른 테넌트인 채 transfer 를 부르면 거부하고 아무것도 바꾸지 않는다(WD-1158). */
  @Test
  void transfer_rejectsMismatchedTenantContext() {
    assertThatThrownBy(
            () ->
                TenantContext.runScopedGet(
                    DEFAULT_TEST_TENANT_ID,
                    () -> transferService.transfer(tenantA, admin, tenantName)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(roleOf(owner)).isEqualTo("OWNER");
    assertThat(roleOf(admin)).isNotEqualTo("OWNER");
  }

  /** 소유자 셋(정지 1·활성 2) — 정지를 건너뛰고 활성 중 user_id 가 가장 작은 소유자 이름이 나온다(일괄 조회 뒤에도 순서 유지, WD-1158). */
  @Test
  void getUserById_workspaceOwnerName_firstActiveOwner_batch() {
    long second = create("second");
    long third = create("third");
    for (long id : new long[] {second, third}) {
      dsl.execute(
          "update membership set role = 'OWNER' where user_id = ? and tenant_id = ?", id, tenantA);
    }
    dsl.execute(
        "update membership set status = 'SUSPENDED' where user_id = ? and tenant_id = ?",
        owner,
        tenantA);
    String secondName =
        dsl.fetchOne("select name from \"user\" where id = ?", second).get(0, String.class);

    String shown =
        TenantContext.runScopedGet(
            tenantA, () -> userService.getUserById(member, third).workspaceOwnerName());

    assertThat(shown).isEqualTo(secondName);
  }

  /** ADMIN 을 이미 가진 사용자만 오가면 ROLE_ASSIGN 은 남지 않고 grantedAdminUserIds 도 비어 있다. */
  @Test
  void transfer_noGrantNeeded_noGrantedAdminUserIds() {
    OwnershipTransferResult result = transfer(admin, tenantName);

    assertThat(result.grantedAdminUserIds()).isEmpty();
  }

  @Test
  void transfer_grantedAdminUserIds_includesTargetAndAdminlessOldOwner() {
    inTenantFixture(
        tenantA,
        () ->
            dsl.execute(
                "delete from user_role where user_id = ? and role_id ="
                    + " (select id from role where name = 'ADMIN')",
                owner));

    OwnershipTransferResult result = transfer(member, tenantName);

    assertThat(result.grantedAdminUserIds()).containsExactlyInAnyOrder(member, owner);
    assertThat(result.grantedAdmin()).isTrue();
  }

  /** 이전 뒤 옛 소유자는 OWNER 가 아니라 WD-282 소유자 ADMIN 제거 가드를 받지 않는다 — 다른 ADMIN 이 남아 있으면 ADMIN 을 뺄 수 있다. */
  @Test
  void afterTransfer_oldOwnerAdminRoleCanBeRemoved() {
    transfer(admin, tenantName);

    TenantContext.runScoped(tenantA, () -> userService.setUserRoles(owner, List.of(), admin));

    assertThat(hasAdminRole(owner)).isFalse();
  }

  @Test
  void afterTransfer_oldOwnerNoLongerProtected() {
    transfer(admin, tenantName);

    // 옛 소유자는 이제 평범한 ADMIN — 정지 가능(마지막 ADMIN 도 아님: admin 이 남는다).
    TenantContext.runScoped(tenantA, () -> userService.setUserActive(owner, false, admin));
    assertThat(membershipRepository.findInTenant(owner, tenantA).orElseThrow().isActive())
        .isFalse();
  }

  @Test
  void getUserById_fillsWorkspaceOwnerFields() {
    var asOwner = TenantContext.runScopedGet(tenantA, () -> userService.getUserById(member, owner));
    var asAdmin = TenantContext.runScopedGet(tenantA, () -> userService.getUserById(member, admin));

    assertThat(asOwner.workspaceName()).isEqualTo(tenantName);
    assertThat(asOwner.workspaceOwnerName()).isEqualTo("owner");
    assertThat(asOwner.viewerIsOwner()).isTrue();
    assertThat(asAdmin.viewerIsOwner()).isFalse();
  }
}
