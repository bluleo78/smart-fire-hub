package com.smartfirehub.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 역할 부여·회수·역할 권한 추가는 호출자 자신의 범위 안에서만 된다(WD-116, 사람 결정 A안).
 *
 * <p>실제 HTTP(MockMvc + 실제 JWT) 로 검증한다 — 결함 보고가 "role:read + role:assign 만 가진 사용자가 PUT 으로 204 를 받는다"
 * 였으므로, 서비스 가드가 컨트롤러·예외 매핑(403 + 한국어 메시지)까지 실제로 이어지는지를 같은 경로로 고정한다.
 *
 * <p>구성(새 테넌트 A, 비트랜잭션 — 요청이 각자 커밋되므로 정리는 tearDown 에서): admin1·admin2(ADMIN — 둘이라 "마지막 ADMIN" 409 가
 * 먼저 걸리지 않는다), delegate(DELEGATE 역할만 — role:read·role:assign·user:read·user:write, 등급 '내부'),
 * writer(WRITER 역할만 — role:read·role:write·dataset:read, 등급 '내부'), target(USER). 위임자·작성자는 USER 를 떼어
 * 범위가 정확히 그 역할로만 정해지게 한다.
 */
@AutoConfigureMockMvc
class RoleGrantScopeTest extends IntegrationTestBase {

  private static final String ROLE_SCOPE_MESSAGE = "자신에게 없는 권한·등급을 가진 역할은 부여하거나 회수할 수 없습니다";
  private static final String ADMIN_ROLE_MESSAGE = "시스템 ADMIN 역할은 ADMIN 만 부여하거나 회수할 수 있습니다";
  private static final String PERMISSION_SCOPE_MESSAGE = "자신에게 없는 권한은 역할에 추가할 수 없습니다";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private TenantProvisioningService provisioningService;
  @Autowired private ObjectMapper objectMapper;

  private long tenantA;
  private long admin1;
  private long admin2;
  private long delegate;
  private long writer;
  private long target;

  private long adminRole;
  private long userRole;
  private long delegateRole;
  private long writerRole;

  /** 위임자 범위 안: 위임자가 가진 권한(role:read)만, 등급 '내부'(= 위임자 등급). */
  private long inScopeRole;

  /** 위임자에게 없는 권한(audit:read), 등급 '내부'. */
  private long lackingPermRole;

  /** 권한은 범위 안(role:read)이지만 등급 '민감' — 위임자('내부')보다 높다. */
  private long highClearanceRole;

  private final List<Long> users = new ArrayList<>();

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "wd116-a");
    provisioningService.provisionDefaults(tenantA);
    adminRole = roleId("ADMIN");
    userRole = roleId("USER");

    admin1 = member("admin1");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, admin1, tenantA, "ADMIN");
    admin2 = member("admin2");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, admin2, tenantA, "ADMIN");

    delegateRole =
        createRole("DELEGATE", "내부", "role:read", "role:assign", "user:read", "user:write");
    delegate = member("delegate");
    replaceRoles(delegate, delegateRole);

    writerRole = createRole("WRITER", "내부", "role:read", "role:write", "dataset:read");
    writer = member("writer");
    replaceRoles(writer, writerRole);

    target = member("target");

    inScopeRole = createRole("IN_SCOPE", "내부", "role:read");
    lackingPermRole = createRole("LACKING_PERM", "내부", "audit:read");
    highClearanceRole = createRole("HIGH_CLEARANCE", "민감", "role:read");
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

  // ── 역할 부여·회수(PUT /users/{id}/roles) ─────────────────────────────────────

  /** 결함 재현 1: role:assign 위임자가 자기에게 시스템 ADMIN 을 붙이면 403, 역할은 그대로. */
  @Test
  void delegate_cannotGrantSelfAdmin() throws Exception {
    putRoles(delegate, delegate, delegateRole, adminRole)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(ADMIN_ROLE_MESSAGE));

    assertThat(roleIdsOf(delegate)).containsExactly(delegateRole);
  }

  /** 결함 재현 2: 위임자가 다른 관리자의 ADMIN 을 떼면 403, 그 관리자는 ADMIN 유지. */
  @Test
  void delegate_cannotRemoveAnotherAdminsAdmin() throws Exception {
    putRoles(delegate, admin2, userRole)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(ADMIN_ROLE_MESSAGE));

    assertThat(roleIdsOf(admin2)).contains(adminRole);
  }

  /** 위임자에게 없는 권한을 가진 역할은 부여할 수 없다. */
  @Test
  void delegate_cannotGrantRoleWithPermissionTheyLack() throws Exception {
    putRoles(delegate, target, userRole, lackingPermRole)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(ROLE_SCOPE_MESSAGE));

    assertThat(roleIdsOf(target)).containsExactly(userRole);
  }

  /** 위임자보다 열람 등급이 높은 역할은 부여할 수 없다(권한은 범위 안이어도). */
  @Test
  void delegate_cannotGrantRoleWithHigherClearance() throws Exception {
    putRoles(delegate, target, userRole, highClearanceRole)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(ROLE_SCOPE_MESSAGE));

    assertThat(roleIdsOf(target)).containsExactly(userRole);
  }

  /** 범위 밖 역할은 회수도 할 수 없다 — 회수도 "바뀌는 역할" 이다. */
  @Test
  void delegate_cannotRevokeOutOfScopeRole() throws Exception {
    grant(target, highClearanceRole);

    putRoles(delegate, target, userRole)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(ROLE_SCOPE_MESSAGE));

    assertThat(roleIdsOf(target)).contains(highClearanceRole);
  }

  /** 범위 안의 역할은 부여할 수 있다(과잉 차단 방지). */
  @Test
  void delegate_canGrantInScopeRole() throws Exception {
    putRoles(delegate, target, userRole, inScopeRole).andExpect(status().isNoContent());

    assertThat(roleIdsOf(target)).containsExactlyInAnyOrder(userRole, inScopeRole);
  }

  /**
   * 대상이 이미 가진 범위 밖 역할(권한 초과·등급 초과·ADMIN)은 그대로 두면 검사하지 않는다 — 바뀌는 역할(대칭차)만 본다. 그렇지 않으면 위임자가 상위 역할을 가진
   * 사람의 다른 역할을 전혀 편집할 수 없다.
   */
  @Test
  void delegate_unchangedOutOfScopeRoles_doNotBlockInScopeChange() throws Exception {
    grant(target, highClearanceRole);
    grant(target, lackingPermRole);

    putRoles(delegate, target, userRole, highClearanceRole, lackingPermRole, inScopeRole)
        .andExpect(status().isNoContent());
    putRoles(delegate, admin2, userRole, adminRole, inScopeRole).andExpect(status().isNoContent());

    assertThat(roleIdsOf(target))
        .containsExactlyInAnyOrder(userRole, highClearanceRole, lackingPermRole, inScopeRole);
    assertThat(roleIdsOf(admin2)).containsExactlyInAnyOrder(userRole, adminRole, inScopeRole);
  }

  /**
   * 의도된 판정 순서 고정: 마지막 활성 ADMIN 의 ADMIN 을 위임자가 빼려 하면 범위 403 보다 기존 잠금 방지 409 가 먼저 난다(범위 가드는 400/409
   * 판정 뒤에 있다 — 기존 계약과 동시 상호 강등 동작을 유지하기 위해). 어느 쪽이든 ADMIN 은 그대로 남는다.
   */
  @Test
  void delegate_removingLastActiveAdmin_getsLockout409First() throws Exception {
    // admin1 의 ADMIN 을 떼어 admin2 를 마지막 활성 ADMIN 으로 만든다.
    inTenantFixture(
        tenantA,
        () ->
            dsl.execute(
                "delete from user_role where user_id = ? and role_id = ?", admin1, adminRole));

    putRoles(delegate, admin2, userRole)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("이 워크스페이스의 마지막 활성 ADMIN 에게서 ADMIN 역할을 뺄 수 없습니다"));

    assertThat(roleIdsOf(admin2)).contains(adminRole);
  }

  /**
   * 판정 근거가 없는 역할 id(존재하지 않음·다른 테넌트 역할)는 위임자에게 fail-closed 403 이다. 다른 테넌트 역할은 RLS 로 보이지 않아 권한이 "0개"
   * 로 읽히지만, 등급 판정이 거부를 맡으므로 통과하지 않는다. 대상 역할은 그대로다.
   */
  @Test
  void delegate_unknownOrOtherTenantRoleId_isForbidden() throws Exception {
    long otherTenantRole =
        inTenantFixture(
            DEFAULT_TEST_TENANT_ID,
            () -> ((Number) dsl.fetchValue("select id from role where name = 'USER'")).longValue());

    putRoles(delegate, target, userRole, 987_654_321L)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(ROLE_SCOPE_MESSAGE));
    putRoles(delegate, target, userRole, otherTenantRole)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(ROLE_SCOPE_MESSAGE));

    assertThat(roleIdsOf(target)).containsExactly(userRole);
  }

  /** 시스템 ADMIN 호출자는 범위 제한이 없다 — ADMIN·권한 초과·등급 초과 역할 모두 부여·회수. */
  @Test
  void admin_isUnrestricted() throws Exception {
    putRoles(admin1, target, userRole, adminRole, lackingPermRole, highClearanceRole)
        .andExpect(status().isNoContent());
    assertThat(roleIdsOf(target))
        .containsExactlyInAnyOrder(userRole, adminRole, lackingPermRole, highClearanceRole);

    putRoles(admin1, target, userRole).andExpect(status().isNoContent());
    assertThat(roleIdsOf(target)).containsExactly(userRole);
  }

  // ── 멤버 추가(POST /users) ────────────────────────────────────────────────────

  /** 결함 재현 3: user:write + role:assign 위임자가 ADMIN 계정을 만들면 403, 계정도 생기지 않는다. */
  @Test
  void delegate_cannotCreateAdminViaAddMember() throws Exception {
    String email = "wd116-adm-" + System.nanoTime() + "@acme.io";

    addMember(delegate, email, adminRole)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(ADMIN_ROLE_MESSAGE));

    assertThat(dsl.fetchExists(dsl.selectOne().from("\"user\"").where("username = ?", email)))
        .isFalse();
  }

  /** 멤버 추가에서도 범위 밖(권한 초과) 역할은 403. */
  @Test
  void delegate_cannotAddMemberWithOutOfScopeRole() throws Exception {
    String email = "wd116-oos-" + System.nanoTime() + "@acme.io";

    addMember(delegate, email, lackingPermRole)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(ROLE_SCOPE_MESSAGE));
  }

  /** 범위 안 역할로 멤버 추가는 201. 자동으로 붙는 기본 USER 는 검사하지 않는다 — 위임자는 USER 를 떼어 USER 의 권한이 없는데도 통과한다. */
  @Test
  void delegate_canAddMemberWithInScopeRole() throws Exception {
    String email = "wd116-ok-" + System.nanoTime() + "@acme.io";

    String body =
        addMember(delegate, email, inScopeRole)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long created = objectMapper.readTree(body).get("userId").asLong();
    users.add(created);

    assertThat(roleIdsOf(created)).containsExactlyInAnyOrder(userRole, inScopeRole);
  }

  // ── 역할 권한 변경(PUT /roles/{id}/permissions) ────────────────────────────────

  /** role:write 만 가진 사용자는 자기에게 없는 권한을 역할에 추가할 수 없다. */
  @Test
  void writer_cannotAddPermissionTheyLack() throws Exception {
    putPermissions(writer, inScopeRole, "role:read", "audit:read")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(PERMISSION_SCOPE_MESSAGE));

    assertThat(permissionCodesOf(inScopeRole)).containsExactly("role:read");
  }

  /** 자기 역할에 없는 권한을 붙여 권한을 올리는 것도 막힌다(결함 보고의 "자기 역할 포함"). */
  @Test
  void writer_cannotAddLackingPermissionToOwnRole() throws Exception {
    putPermissions(writer, writerRole, "role:read", "role:write", "dataset:read", "role:assign")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(PERMISSION_SCOPE_MESSAGE));

    assertThat(permissionCodesOf(writerRole))
        .containsExactlyInAnyOrder("role:read", "role:write", "dataset:read");
  }

  /**
   * 자기가 가진 권한은 추가할 수 있고, 제거는 제한하지 않는다 — 자기에게 없는 권한(audit:read)도 제거는 된다. 이미 붙어 있는 남의 권한을 그대로 두는 교체도
   * "추가" 가 아니므로 통과한다.
   */
  @Test
  void writer_canAddHeldPermission_andRemoveAny() throws Exception {
    // 유지(audit:read, 자기에게 없음) + 추가(dataset:read, 보유) → 허용
    putPermissions(writer, lackingPermRole, "audit:read", "dataset:read")
        .andExpect(status().isNoContent());
    assertThat(permissionCodesOf(lackingPermRole))
        .containsExactlyInAnyOrder("audit:read", "dataset:read");

    // 제거(audit:read) → 허용
    putPermissions(writer, lackingPermRole, "dataset:read").andExpect(status().isNoContent());
    assertThat(permissionCodesOf(lackingPermRole)).containsExactly("dataset:read");

    // 전부 제거 → 허용
    putPermissions(writer, lackingPermRole).andExpect(status().isNoContent());
    assertThat(permissionCodesOf(lackingPermRole)).isEmpty();
  }

  /** 시스템 ADMIN 은 권한 추가에 제한이 없다. */
  @Test
  void admin_canAddAnyPermission() throws Exception {
    putPermissions(admin1, inScopeRole, "role:read", "audit:read", "security:settings")
        .andExpect(status().isNoContent());

    assertThat(permissionCodesOf(inScopeRole))
        .containsExactlyInAnyOrder("role:read", "audit:read", "security:settings");
  }

  // ── 요청 헬퍼 ─────────────────────────────────────────────────────────────────

  private String token(long userId) {
    return "Bearer " + jwt.generateAccessToken(userId, "u" + userId, tenantA);
  }

  private ResultActions putRoles(long caller, long userId, Long... roleIds) throws Exception {
    return mockMvc.perform(
        put("/api/v1/users/" + userId + "/roles")
            .header("Authorization", token(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("roleIds", Arrays.asList(roleIds)))));
  }

  private ResultActions addMember(long caller, String email, Long... roleIds) throws Exception {
    return mockMvc.perform(
        post("/api/v1/users")
            .header("Authorization", token(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    Map.of(
                        "email",
                        email,
                        "name",
                        "위임 테스트",
                        "temporaryPassword",
                        "TempPass1x",
                        "roleIds",
                        Arrays.asList(roleIds)))));
  }

  private ResultActions putPermissions(long caller, long roleId, String... codes) throws Exception {
    List<Long> ids =
        Arrays.stream(codes)
            .map(
                c ->
                    ((Number) dsl.fetchValue("select id from permission where code = ?", c))
                        .longValue())
            .toList();
    return mockMvc.perform(
        put("/api/v1/roles/" + roleId + "/permissions")
            .header("Authorization", token(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("permissionIds", ids))));
  }

  // ── 픽스처 헬퍼(role·user_role·role_permission 은 RLS 대상이라 A 컨텍스트 안에서) ──────────────

  private long member(String prefix) {
    String username = "wd116-" + prefix + "-" + System.nanoTime() + "@example.com";
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

  private long roleId(String name) {
    return inTenantFixture(
        tenantA,
        () -> ((Number) dsl.fetchValue("select id from role where name = ?", name)).longValue());
  }

  /** A 에 지정 등급·권한의 사용자 정의 역할을 만든다. */
  private long createRole(String name, String levelName, String... codes) {
    return inTenantFixture(
        tenantA,
        () -> {
          long id =
              ((Number)
                      dsl.fetchValue(
                          "insert into role (name, is_system, max_security_level_id)"
                              + " values (?, false, (select id from security_level where name = ?))"
                              + " returning id",
                          name,
                          levelName))
                  .longValue();
          for (String code : codes) {
            dsl.execute(
                "insert into role_permission (role_id, permission_id)"
                    + " select ?, id from permission where code = ?",
                id,
                code);
          }
          return id;
        });
  }

  /** 사용자의 A 역할을 지정 역할로만 바꾼다(USER 포함 전부 삭제 후 삽입). */
  private void replaceRoles(long userId, long roleId) {
    inTenantFixture(
        tenantA,
        () -> {
          dsl.execute("delete from user_role where user_id = ?", userId);
          dsl.execute("insert into user_role (user_id, role_id) values (?, ?)", userId, roleId);
        });
  }

  private void grant(long userId, long roleId) {
    inTenantFixture(
        tenantA,
        () ->
            dsl.execute("insert into user_role (user_id, role_id) values (?, ?)", userId, roleId));
  }

  private List<Long> roleIdsOf(long userId) {
    return inTenantFixture(
        tenantA,
        () ->
            dsl.fetch("select role_id from user_role where user_id = ? order by role_id", userId)
                .getValues(0, Long.class));
  }

  private List<String> permissionCodesOf(long roleId) {
    return inTenantFixture(
        tenantA,
        () ->
            dsl.fetch(
                    "select p.code from role_permission rp join permission p on p.id ="
                        + " rp.permission_id where rp.role_id = ? order by p.code",
                    roleId)
                .getValues(0, String.class));
  }
}
