package com.smartfirehub.role.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    RoleResponse created = roleService.createRole("MODERATOR", "Moderator", callerId);

    roleService.updateRole(created.id(), "MOD", "Updated moderator", callerId);

    RoleDetailResponse updated = roleService.getRoleById(created.id());
    assertThat(updated.name()).isEqualTo("MOD");
    assertThat(updated.description()).isEqualTo("Updated moderator");
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
    TenantRlsTestSupport.insertActiveMembership(dsl, callerId, DEFAULT_TEST_TENANT_ID);
    dsl.insertInto(USER_ROLE)
        .set(USER_ROLE.USER_ID, callerId)
        .set(
            USER_ROLE.ROLE_ID,
            dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchOne(ROLE.ID))
        .execute();
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
}
