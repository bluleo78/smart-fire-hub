package com.smartfirehub.role.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.EditConflictException;
import com.smartfirehub.role.dto.RoleDetailResponse;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.exception.SystemRoleModificationException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RoleServiceTest extends IntegrationTestBase {

  @Autowired private RoleService roleService;

  @Autowired private DSLContext dsl;

  /**
   * 감사 로그 행위자(WD-117) — audit_log.user_id 는 "user"(id) FK 라 실제 사용자여야 한다. 클래스가 @Transactional 이라 테스트
   * 트랜잭션 안에서 넣고 함께 롤백된다(감사 행도 같은 트랜잭션이라 FK 를 만족한다).
   */
  private Long callerId;

  @BeforeEach
  void setUpCaller() {
    callerId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "role-caller-" + System.nanoTime() + "@example.com")
            .set(USER.PASSWORD, "x")
            .set(USER.NAME, "Role Caller")
            .returning(USER.ID)
            .fetchOne()
            .getId();
  }

  @Test
  void getAllRoles_returnsList() {
    List<RoleResponse> result = roleService.getAllRoles();

    assertThat(result).hasSizeGreaterThanOrEqualTo(2);
    assertThat(result).anyMatch(r -> r.name().equals("ADMIN"));
    assertThat(result).anyMatch(r -> r.name().equals("USER"));
  }

  @Test
  void getRoleById_returnsRoleWithPermissions() {
    Long adminRoleId =
        dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchOne(ROLE.ID);

    RoleDetailResponse result = roleService.getRoleById(adminRoleId);

    assertThat(result.name()).isEqualTo("ADMIN");
    assertThat(result.permissions()).isNotEmpty();
  }

  @Test
  void getRoleById_notFound_throwsException() {
    assertThatThrownBy(() -> roleService.getRoleById(999L))
        .isInstanceOf(RoleNotFoundException.class);
  }

  @Test
  void createRole_success() {
    RoleResponse result = roleService.createRole("MODERATOR", "Moderator role", callerId);

    assertThat(result.id()).isNotNull();
    assertThat(result.name()).isEqualTo("MODERATOR");
    assertThat(result.description()).isEqualTo("Moderator role");
  }

  @Test
  void createRole_duplicateName_throwsException() {
    assertThatThrownBy(() -> roleService.createRole("ADMIN", "Duplicate", callerId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("이미 존재하는 역할 이름입니다");
  }

  @Test
  void updateRole_success() {
    makeCallerAdmin(); // 이름·설명 수정도 범위 가드(WD-284)를 탄다
    RoleResponse created = roleService.createRole("MODERATOR", "Moderator", callerId);

    roleService.updateRole(created.id(), "MOD", "Updated moderator", callerId);

    RoleDetailResponse updated = roleService.getRoleById(created.id());
    assertThat(updated.name()).isEqualTo("MOD");
    assertThat(updated.description()).isEqualTo("Updated moderator");
  }

  /** WD-279: 이름을 다른 역할(시스템 역할 포함)의 이름으로 바꾸면 생성과 같은 「이미 존재하는 역할 이름」으로 거부하고 바꾸지 않는다. */
  @Test
  void updateRole_nameOfAnotherRole_throwsAndKeepsName() {
    makeCallerAdmin(); // 이름·설명 수정도 범위 가드(WD-284)를 탄다
    RoleResponse created = roleService.createRole("MODERATOR", "Moderator", callerId);
    roleService.createRole("EDITOR", "Editor", callerId);

    assertThatThrownBy(() -> roleService.updateRole(created.id(), "ADMIN", "가짜", callerId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("이미 존재하는 역할 이름입니다");
    assertThatThrownBy(() -> roleService.updateRole(created.id(), "EDITOR", "중복", callerId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("이미 존재하는 역할 이름입니다");

    assertThat(roleService.getRoleById(created.id()).name()).isEqualTo("MODERATOR");
  }

  /** WD-279: 이름은 그대로 두고 설명만 바꾸면 자기 이름과의 중복으로 막지 않는다. */
  @Test
  void updateRole_sameName_descriptionOnly_succeeds() {
    makeCallerAdmin(); // 이름·설명 수정도 범위 가드(WD-284)를 탄다
    RoleResponse created = roleService.createRole("MODERATOR", "Moderator", callerId);

    roleService.updateRole(created.id(), "MODERATOR", "설명만 바꿈", callerId);

    assertThat(roleService.getRoleById(created.id()).description()).isEqualTo("설명만 바꿈");
  }

  @Test
  void updateRole_systemRole_throwsException() {
    Long adminRoleId =
        dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchOne(ROLE.ID);

    assertThatThrownBy(() -> roleService.updateRole(adminRoleId, "RENAMED", "Try rename", callerId))
        .isInstanceOf(SystemRoleModificationException.class);
  }

  @Test
  void deleteRole_success() {
    // 자기 범위를 넘는 역할은 삭제할 수 없다(WD-141) — 멤버십·역할이 없는 행위자는 등급이 없어 기본 등급 역할도 범위 밖이므로 ADMIN 으로 만든다.
    makeCallerAdmin();
    RoleResponse created = roleService.createRole("MODERATOR", "Moderator", callerId);

    roleService.deleteRole(created.id(), callerId);

    assertThatThrownBy(() -> roleService.getRoleById(created.id()))
        .isInstanceOf(RoleNotFoundException.class);
  }

  @Test
  void deleteRole_systemRole_throwsException() {
    Long adminRoleId =
        dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchOne(ROLE.ID);

    assertThatThrownBy(() -> roleService.deleteRole(adminRoleId, callerId))
        .isInstanceOf(SystemRoleModificationException.class);
  }

  @Test
  void setRolePermissions_customRole_success() {
    // 자신에게 없는 권한은 역할에 추가할 수 없다(WD-116) — 행위자를 기본 테넌트 ADMIN 으로 만든다(테스트 트랜잭션과 함께 롤백).
    makeCallerAdmin();
    RoleResponse customRole =
        roleService.createRole("CUSTOM_ROLE", "Custom role for test", callerId);

    List<Long> permissionIds =
        dsl.select(PERMISSION.ID)
            .from(PERMISSION)
            .where(PERMISSION.CATEGORY.eq("user"))
            .fetch(PERMISSION.ID);

    roleService.setRolePermissions(customRole.id(), permissionIds, callerId);

    RoleDetailResponse detail = roleService.getRoleById(customRole.id());
    assertThat(detail.permissions()).hasSize(permissionIds.size());
  }

  @Test
  void setRolePermissions_systemRole_throwsException() {
    Long adminRoleId =
        dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchOne(ROLE.ID);

    List<Long> permissionIds =
        dsl.select(PERMISSION.ID)
            .from(PERMISSION)
            .where(PERMISSION.CATEGORY.eq("user"))
            .fetch(PERMISSION.ID);

    assertThatThrownBy(() -> roleService.setRolePermissions(adminRoleId, permissionIds, callerId))
        .isInstanceOf(SystemRoleModificationException.class)
        .hasMessageContaining("Cannot modify permissions of system role");
  }

  @Test
  void setRolePermissions_staleExpected_isRejectedAndKeepsRevokedPermissionRevoked() {
    // WD-440: 화면을 연 뒤 다른 관리자가 권한 하나를 회수했다 — 옛 화면이 본 목록(회수 전)으로 저장하면 409, 회수된 권한은 되살아나지 않는다.
    makeCallerAdmin();
    RoleResponse role = roleService.createRole("STALE_ROLE", "동시 편집", callerId);
    List<Long> userPerms =
        dsl.select(PERMISSION.ID)
            .from(PERMISSION)
            .where(PERMISSION.CATEGORY.eq("user"))
            .orderBy(PERMISSION.ID)
            .fetch(PERMISSION.ID);
    assertThat(userPerms).hasSizeGreaterThanOrEqualTo(2);
    roleService.setRolePermissions(role.id(), userPerms, callerId);
    List<Long> screenSaw = userPerms; // 옛 화면이 본 목록

    // 다른 관리자가 첫 권한을 회수
    List<Long> afterRevoke = userPerms.subList(1, userPerms.size());
    roleService.setRolePermissions(role.id(), afterRevoke, userPerms, callerId);

    // 옛 화면: 본 목록 그대로 저장(회수된 권한 포함) → 거부
    assertThatThrownBy(
            () -> roleService.setRolePermissions(role.id(), screenSaw, screenSaw, callerId))
        .isInstanceOf(EditConflictException.class)
        .hasMessage(RoleService.PERMISSIONS_EDIT_CONFLICT_MESSAGE);
    assertThat(roleService.getRoleById(role.id()).permissions()).hasSize(afterRevoke.size());

    // 기준 없이(「내 변경으로 덮어쓰기」) 보내면 예전처럼 저장된다
    roleService.setRolePermissions(role.id(), screenSaw, null, callerId);
    assertThat(roleService.getRoleById(role.id()).permissions()).hasSize(userPerms.size());
  }

  /** 행위자를 기본 테넌트 ADMIN 으로 만든다(범위 가드 WD-116/WD-141 를 통과시키기 위해 — 테스트 트랜잭션과 함께 롤백). */
  private void makeCallerAdmin() {
    TenantRlsTestSupport.insertActiveMembership(dsl, callerId, DEFAULT_TEST_TENANT_ID);
    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, callerId)
        .set(
            USER_ROLE.ROLE_ID,
            dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchOne(ROLE.ID))
        .execute();
  }
}
