package com.smartfirehub.role.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.exception.SystemRoleModificationException;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 역할 CRUD·역할 권한 변경 감사 로그(WD-117).
 *
 * <p>비트랜잭션 + 새 테넌트 A(MembershipLifecycleServiceTest 와 같은 구성). 클래스 레벨 {@code @Transactional} 을 쓰지 않는
 * 이유: 감사 행은 서비스 트랜잭션에 기록되는데, 검증용 조회({@code inTenantFixture})는 별도 트랜잭션이라 미커밋 행을 볼 수 없다. 서비스 호출을
 * 커밋시켜야 "실제로 남았다"는 증거가 된다.
 */
class RoleAuditServiceTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private RoleService roleService;
  @Autowired private RoleRepository roleRepository;
  @Autowired private TenantProvisioningService provisioningService;
  @Autowired private ObjectMapper objectMapper;

  private long tenantA;

  /** 행위자 — audit_log.user_id 는 "user"(id) FK 라 실제 사용자여야 한다. */
  private long actor;

  private String actorName;
  private final List<Long> users = new ArrayList<>();

  @BeforeEach
  void setUp() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "wd117-role-a");
    provisioningService.provisionDefaults(tenantA);
    actorName = "roleadm-" + System.nanoTime() + "@example.com";
    actor =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                passwordEncoder,
                actorName,
                actorName,
                "Password123",
                "roleadm",
                tenantA)
            .id();
    users.add(actor);
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, actor, tenantA, "ADMIN");
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

  private <T> T inAGet(Supplier<T> s) {
    return TenantContext.runScopedGet(tenantA, s);
  }

  private void inA(Runnable r) {
    TenantContext.runScoped(tenantA, r);
  }

  /** 테넌트 A 의 역할 감사 행(id 순). resource='role' 까지 걸어 user resource_id 와 섞이지 않게 한다. */
  private List<Record> roleAudits(String action, long roleId) {
    return inTenantFixture(
        tenantA,
        () ->
            dsl.fetch(
                "select user_id, username, tenant_id, description, result, metadata::text as metadata"
                    + " from audit_log where action_type = ? and resource = 'role'"
                    + " and resource_id = ? order by id",
                action,
                String.valueOf(roleId)));
  }

  private JsonNode metadataOf(Record row) throws Exception {
    return objectMapper.readTree(row.get("metadata", String.class));
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }

  /** 행위자·테넌트·결과 공통 단언. */
  private void assertActor(Record row) {
    assertThat(row.get("user_id", Long.class)).isEqualTo(actor);
    assertThat(row.get("username", String.class)).isEqualTo(actorName);
    assertThat(row.get("tenant_id", Long.class)).isEqualTo(tenantA);
    assertThat(row.get("result", String.class)).isEqualTo("SUCCESS");
  }

  /** 권한 코드 오름차순 앞에서 n 개의 (id, code). permission 은 전역 카탈로그다. */
  private List<Record> firstPermissions(int n) {
    return dsl.fetch("select id, code from permission order by code limit ?", n);
  }

  private RoleResponse createCustomRole(String name, String description) {
    return inAGet(() -> roleService.createRole(name, description, actor));
  }

  @Test
  void createRole_writesRoleCreateAudit() throws Exception {
    RoleResponse created = createCustomRole("AUDITOR", "감사 담당");

    List<Record> rows = roleAudits("ROLE_CREATE", created.id());
    assertThat(rows).hasSize(1);
    assertActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class)).isEqualTo("역할 생성: AUDITOR");
    JsonNode meta = metadataOf(rows.get(0));
    assertThat(meta.get("name").asText()).isEqualTo("AUDITOR");
    assertThat(meta.get("description").asText()).isEqualTo("감사 담당");
  }

  /** 수정은 전/후 값을 남기고, 같은 값으로 다시 저장하면(무변경) 추가 행이 없다. */
  @Test
  void updateRole_writesBeforeAfter_andSkipsNoOp() throws Exception {
    RoleResponse created = createCustomRole("AUDITOR", null);

    inA(() -> roleService.updateRole(created.id(), "AUDITOR2", "설명 추가", actor));
    inA(() -> roleService.updateRole(created.id(), "AUDITOR2", "설명 추가", actor)); // 무변경

    List<Record> rows = roleAudits("ROLE_UPDATE", created.id());
    assertThat(rows).hasSize(1);
    assertActor(rows.get(0));
    JsonNode meta = metadataOf(rows.get(0));
    assertThat(meta.get("before").get("name").asText()).isEqualTo("AUDITOR");
    assertThat(meta.get("before").get("description").isNull()).isTrue();
    assertThat(meta.get("after").get("name").asText()).isEqualTo("AUDITOR2");
    assertThat(meta.get("after").get("description").asText()).isEqualTo("설명 추가");
  }

  /** 삭제 감사는 사라진 역할의 이름과, CASCADE 로 함께 사라지는 권한 코드·이 역할을 가졌던 사용자를 metadata 에 보존한다. */
  @Test
  void deleteRole_writesRoleDeleteAudit() throws Exception {
    RoleResponse created = createCustomRole("TEMP_ROLE", "임시");
    Record perm = firstPermissions(1).get(0);
    inA(
        () ->
            roleService.setRolePermissions(
                created.id(), List.of(perm.get("id", Long.class)), actor));
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, actor, tenantA, "TEMP_ROLE");

    inA(() -> roleService.deleteRole(created.id(), actor));

    List<Record> rows = roleAudits("ROLE_DELETE", created.id());
    assertThat(rows).hasSize(1);
    assertActor(rows.get(0));
    assertThat(rows.get(0).get("description", String.class)).isEqualTo("역할 삭제: TEMP_ROLE");
    JsonNode meta = metadataOf(rows.get(0));
    assertThat(meta.get("name").asText()).isEqualTo("TEMP_ROLE");
    assertThat(texts(meta.get("permissions"))).containsExactly(perm.get("code", String.class));
    assertThat(texts(meta.get("affectedUserIds"))).containsExactly(String.valueOf(actor));
  }

  /** 거부된 변경(시스템 역할 삭제·권한 변경)은 감사를 남기지 않는다. */
  @Test
  void rejectedSystemRoleChanges_writeNoAudit() {
    long adminRoleId =
        inTenantFixture(tenantA, () -> roleRepository.findByName("ADMIN").orElseThrow().id());
    List<Long> permissionIds =
        firstPermissions(1).stream().map(r -> r.get("id", Long.class)).toList();

    assertThatThrownBy(() -> inA(() -> roleService.deleteRole(adminRoleId, actor)))
        .isInstanceOf(SystemRoleModificationException.class);
    assertThatThrownBy(
            () -> inA(() -> roleService.setRolePermissions(adminRoleId, permissionIds, actor)))
        .isInstanceOf(SystemRoleModificationException.class);

    assertThat(roleAudits("ROLE_DELETE", adminRoleId)).isEmpty();
    assertThat(roleAudits("ROLE_PERMISSIONS_UPDATE", adminRoleId)).isEmpty();
  }

  /**
   * 권한 교체는 실제 저장 상태의 차이(추가·제거 코드)를 남긴다. {p0,p1} → {p1,p2} 는 p2 추가·p0 제거이고, 같은 목록을 다시 보내면(무변경) 행이 늘지
   * 않는다.
   */
  @Test
  void setRolePermissions_writesAddedRemoved_andSkipsNoOp() throws Exception {
    RoleResponse role = createCustomRole("PERM_ROLE", null);
    List<Record> perms = firstPermissions(3);
    long p0 = perms.get(0).get("id", Long.class);
    long p1 = perms.get(1).get("id", Long.class);
    long p2 = perms.get(2).get("id", Long.class);
    String c0 = perms.get(0).get("code", String.class);
    String c1 = perms.get(1).get("code", String.class);
    String c2 = perms.get(2).get("code", String.class);

    inA(() -> roleService.setRolePermissions(role.id(), List.of(p0, p1), actor));
    inA(() -> roleService.setRolePermissions(role.id(), List.of(p1, p2), actor));
    inA(() -> roleService.setRolePermissions(role.id(), List.of(p2, p1), actor)); // 무변경(순서만 다름)

    List<Record> rows = roleAudits("ROLE_PERMISSIONS_UPDATE", role.id());
    assertThat(rows).hasSize(2);
    rows.forEach(this::assertActor);

    JsonNode first = metadataOf(rows.get(0));
    assertThat(first.get("role").asText()).isEqualTo("PERM_ROLE");
    assertThat(texts(first.get("added"))).containsExactlyInAnyOrder(c0, c1);
    assertThat(texts(first.get("removed"))).isEmpty();

    JsonNode second = metadataOf(rows.get(1));
    assertThat(texts(second.get("added"))).containsExactly(c2);
    assertThat(texts(second.get("removed"))).containsExactly(c0);
    assertThat(rows.get(1).get("description", String.class)).isEqualTo("역할 권한 변경: PERM_ROLE");
  }
}
