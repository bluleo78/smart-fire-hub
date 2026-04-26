package com.smartfirehub.permission.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * PermissionService 통합 테스트.
 *
 * <p>getAllPermissions, getPermissionsByCategory, getUserPermissions 핵심 메서드 전체 커버. Flyway seed
 * 데이터(38개 권한, ADMIN/USER 역할)를 활용하여 실제 DB에서 검증한다.
 */
@Transactional
class PermissionServiceTest extends IntegrationTestBase {

  @Autowired private PermissionService permissionService;
  @Autowired private DSLContext dsl;

  /** 테스트 사용자 ID (ADMIN 역할 부여) */
  private Long adminUserId;

  /** 테스트 사용자 ID (USER 역할 부여) */
  private Long userUserId;

  /** 테스트 사용자 ID (역할 없음) */
  private Long noRoleUserId;

  // =========================================================================
  // Setup
  // =========================================================================

  /**
   * 각 테스트 전 사용자를 생성하고 역할을 할당한다. Flyway seed에 의해 ADMIN(id=1), USER(id=2) 역할과 role_permission 매핑이 미리
   * 존재한다.
   */
  @BeforeEach
  void setUp() {
    // ADMIN 역할을 부여받을 사용자 생성
    adminUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "perm_admin")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Perm Admin")
            .set(DSL.field(DSL.name("user", "email"), String.class), "perm_admin@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    // USER 역할을 부여받을 사용자 생성
    userUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "perm_user")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Perm User")
            .set(DSL.field(DSL.name("user", "email"), String.class), "perm_user@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    // 역할 없는 사용자 생성
    noRoleUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "perm_norole")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Perm NoRole")
            .set(DSL.field(DSL.name("user", "email"), String.class), "perm_norole@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    // adminUserId에 ADMIN 역할(id=1) 할당
    dsl.insertInto(DSL.table(DSL.name("user_role")))
        .set(DSL.field(DSL.name("user_role", "user_id"), Long.class), adminUserId)
        .set(DSL.field(DSL.name("user_role", "role_id"), Long.class), 1L)
        .execute();

    // userUserId에 USER 역할(id=2) 할당
    dsl.insertInto(DSL.table(DSL.name("user_role")))
        .set(DSL.field(DSL.name("user_role", "user_id"), Long.class), userUserId)
        .set(DSL.field(DSL.name("user_role", "role_id"), Long.class), 2L)
        .execute();
  }

  // =========================================================================
  // getAllPermissions
  // =========================================================================

  /** Flyway seed 데이터(38개)가 모두 반환되어야 한다. */
  @Test
  void getAllPermissions_returnAllSeedPermissions() {
    List<PermissionResponse> result = permissionService.getAllPermissions();

    assertThat(result).hasSize(38);
  }

  /** 반환 목록은 id 오름차순으로 정렬되어야 한다. */
  @Test
  void getAllPermissions_orderedById() {
    List<PermissionResponse> result = permissionService.getAllPermissions();

    assertThat(result).isNotEmpty();
    // id가 오름차순인지 확인 (연속 쌍 비교)
    for (int i = 0; i < result.size() - 1; i++) {
      assertThat(result.get(i).id()).isLessThan(result.get(i + 1).id());
    }
  }

  /** 각 PermissionResponse의 code, description, category 필드가 null이 아니어야 한다. */
  @Test
  void getAllPermissions_fieldsAreNotNull() {
    List<PermissionResponse> result = permissionService.getAllPermissions();

    assertThat(result)
        .allSatisfy(
            p -> {
              assertThat(p.id()).isNotNull();
              assertThat(p.code()).isNotBlank();
              assertThat(p.description()).isNotBlank();
              assertThat(p.category()).isNotBlank();
            });
  }

  // =========================================================================
  // getPermissionsByCategory
  // =========================================================================

  /**
   * "user" 카테고리 권한 5개(user:read, user:read:self, user:write:self, user:write, user:delete)가 반환되어야
   * 한다.
   */
  @Test
  void getPermissionsByCategory_userCategory_returns5Permissions() {
    List<PermissionResponse> result = permissionService.getPermissionsByCategory("user");

    assertThat(result).hasSize(5);
    assertThat(result).extracting(PermissionResponse::category).containsOnly("user");
  }

  /** "role" 카테고리 권한만 반환되어야 한다. */
  @Test
  void getPermissionsByCategory_roleCategory_returnsOnlyRolePermissions() {
    List<PermissionResponse> result = permissionService.getPermissionsByCategory("role");

    assertThat(result).isNotEmpty();
    assertThat(result).extracting(PermissionResponse::category).containsOnly("role");
  }

  /** "pipeline" 카테고리 권한 코드 목록에 핵심 코드들이 포함되어야 한다. */
  @Test
  void getPermissionsByCategory_pipelineCategory_containsExpectedCodes() {
    List<PermissionResponse> result = permissionService.getPermissionsByCategory("pipeline");

    assertThat(result)
        .extracting(PermissionResponse::code)
        .contains("pipeline:read", "pipeline:write", "pipeline:execute", "pipeline:delete");
  }

  /** 존재하지 않는 카테고리는 빈 목록을 반환해야 한다. */
  @Test
  void getPermissionsByCategory_nonExistentCategory_returnsEmpty() {
    List<PermissionResponse> result = permissionService.getPermissionsByCategory("nonexistent");

    assertThat(result).isEmpty();
  }

  /** 결과는 id 오름차순으로 정렬되어야 한다. */
  @Test
  void getPermissionsByCategory_orderedById() {
    List<PermissionResponse> result = permissionService.getPermissionsByCategory("dataset");

    assertThat(result).hasSizeGreaterThan(1);
    for (int i = 0; i < result.size() - 1; i++) {
      assertThat(result.get(i).id()).isLessThan(result.get(i + 1).id());
    }
  }

  // =========================================================================
  // getUserPermissions
  // =========================================================================

  /**
   * ADMIN 역할(id=1)을 가진 사용자는 ADMIN에 할당된 모든 권한 코드를 반환해야 한다. Flyway seed 기준 ADMIN 역할에는 permission:read
   * 코드가 포함된다.
   */
  @Test
  void getUserPermissions_adminRole_containsPermissionRead() {
    Set<String> result = permissionService.getUserPermissions(adminUserId);

    assertThat(result).isNotEmpty();
    assertThat(result).contains("permission:read");
  }

  /** ADMIN 역할을 가진 사용자의 권한 코드는 Set<String> 형태로 반환되어야 한다. */
  @Test
  void getUserPermissions_adminRole_returnsSetOfStrings() {
    Set<String> result = permissionService.getUserPermissions(adminUserId);

    // ADMIN 역할에 할당된 모든 권한 코드가 포함되어야 함
    assertThat(result).contains("user:read", "role:read", "dataset:read", "pipeline:read");
  }

  /**
   * USER 역할(id=2)을 가진 사용자는 USER 역할에 할당된 권한만 반환해야 한다. Flyway seed 기준 USER 역할에는 user:read:self,
   * user:write:self 등 최소 권한이 포함된다.
   */
  @Test
  void getUserPermissions_userRole_returnsUserRolePermissions() {
    Set<String> result = permissionService.getUserPermissions(userUserId);

    // USER 역할에 할당된 권한 코드 확인
    assertThat(result).isNotEmpty();
    // ADMIN 전용 권한(user:delete 등)은 포함되지 않아야 함
    assertThat(result).doesNotContain("user:delete", "role:delete", "dataset:delete");
  }

  /** 역할이 없는 사용자는 빈 Set을 반환해야 한다. */
  @Test
  void getUserPermissions_noRole_returnsEmptySet() {
    Set<String> result = permissionService.getUserPermissions(noRoleUserId);

    assertThat(result).isEmpty();
  }

  /** 존재하지 않는 사용자 ID는 빈 Set을 반환해야 한다. */
  @Test
  void getUserPermissions_nonExistentUser_returnsEmptySet() {
    Set<String> result = permissionService.getUserPermissions(-999L);

    assertThat(result).isEmpty();
  }

  /** 반환된 권한 코드는 중복 없이 유일해야 한다 (Set 특성 검증). */
  @Test
  void getUserPermissions_noDuplicateCodes() {
    Set<String> result = permissionService.getUserPermissions(adminUserId);

    // Set이므로 size와 stream distinct count가 일치해야 함
    long distinctCount = result.stream().distinct().count();
    assertThat(distinctCount).isEqualTo(result.size());
  }
}
