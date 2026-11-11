package com.smartfirehub.permission.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.user.repository.AccessContractBase;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PermissionRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>permission 은 전역 카탈로그(테넌트 없이 조회), role_permission·user_role 은 테넌트 정책 대상이라 역할·사용자 단위 조회는 테넌트
 * 컨텍스트 안에서 하고 다른 테넌트의 역할·배정은 보이지 않아야 한다. 카탈로그는 시드와 섞이지 않도록 이 시험이 만든 권한(고유 코드·카테고리)만 본다.
 */
public abstract class PermissionRepositoryContractTest extends AccessContractBase {

  protected abstract PermissionRepository repository();

  private long tenantA;
  private long tenantB;
  private String category;
  private String otherCategory;
  private long pRead;
  private long pWrite;
  private long pAdmin;

  @BeforeEach
  void createCatalog() {
    tenantA = fx.createTenant();
    tenantB = fx.createTenant();
    category = "cat-" + prefix;
    otherCategory = "oth-" + prefix;
    pRead = fx.createPermission(prefix + ":read", category, "읽기");
    pWrite = fx.createPermission(prefix + ":write", category, "쓰기");
    pAdmin = fx.createPermission(prefix + ":admin", otherCategory, "관리");
  }

  private String code(String suffix) {
    return prefix + ":" + suffix;
  }

  @Test
  void catalogReads_findAll_byCategory_byId_byCode() {
    List<PermissionResponse> all = fx.noTenant(() -> repository().findAll());
    List<PermissionResponse> mine = all.stream().filter(p -> p.code().startsWith(prefix)).toList();

    assertThat(mine).extracting(PermissionResponse::id).containsExactly(pRead, pWrite, pAdmin);
    assertThat(all).extracting(PermissionResponse::id).isSorted();
    assertThat(fx.noTenant(() -> repository().findByCategory(category)))
        .extracting(PermissionResponse::code)
        .containsExactly(code("read"), code("write"));
    assertThat(fx.noTenant(() -> repository().findByCategory("none-" + prefix))).isEmpty();

    PermissionResponse read = fx.noTenant(() -> repository().findById(pRead)).orElseThrow();
    assertThat(read.code()).isEqualTo(code("read"));
    assertThat(read.description()).isEqualTo("읽기");
    assertThat(read.category()).isEqualTo(category);
    assertThat(fx.noTenant(() -> repository().findByCode(code("write"))))
        .map(PermissionResponse::id)
        .contains(pWrite);
    assertThat(fx.noTenant(() -> repository().findById(-1L))).isEmpty();
    assertThat(fx.noTenant(() -> repository().findByCode(code("none")))).isEmpty();
  }

  @Test
  void idLookups_findCodesByIds_existsInCategory() {
    assertThat(fx.noTenant(() -> repository().findCodesByIds(List.of(pRead, pAdmin, -1L))))
        .containsExactlyInAnyOrder(code("read"), code("admin"));
    assertThat(fx.noTenant(() -> repository().findCodesByIds(List.of()))).isEmpty();
    assertThat(
            fx.noTenant(() -> repository().existsInCategory(List.of(pRead, pAdmin), otherCategory)))
        .isTrue();
    assertThat(
            fx.noTenant(() -> repository().existsInCategory(List.of(pRead, pWrite), otherCategory)))
        .isFalse();
    assertThat(fx.noTenant(() -> repository().existsInCategory(List.of(), category))).isFalse();
  }

  @Test
  void roleScoped_findByRoleId_findCodesByRoleIds_areTenantScoped() {
    long roleA = fx.createRole(tenantA, prefix + "-a", "d");
    long emptyRole = fx.createRole(tenantA, prefix + "-e", "d");
    long roleB = fx.createRole(tenantB, prefix + "-b", "d");
    fx.grantPermission(tenantA, roleA, pWrite);
    fx.grantPermission(tenantA, roleA, pRead);
    fx.grantPermission(tenantB, roleB, pAdmin);

    assertThat(fx.inTenant(tenantA, () -> repository().findByRoleId(roleA)))
        .extracting(PermissionResponse::id)
        .containsExactly(pRead, pWrite);
    // 다른 테넌트 역할의 권한은 보이지 않는다.
    assertThat(fx.inTenant(tenantA, () -> repository().findByRoleId(roleB))).isEmpty();
    assertThat(fx.inTenant(null, () -> repository().findByRoleId(roleA))).isEmpty();

    Map<Long, Set<String>> codes =
        fx.inTenant(
            tenantA, () -> repository().findCodesByRoleIds(List.of(roleA, emptyRole, roleB)));
    // 권한이 없거나 보이지 않는 역할은 키가 없다.
    assertThat(codes.keySet()).containsExactly(roleA);
    assertThat(codes.get(roleA)).containsExactlyInAnyOrder(code("read"), code("write"));
    assertThat(fx.inTenant(tenantA, () -> repository().findCodesByRoleIds(List.of()))).isEmpty();
  }

  @Test
  void userScoped_codesAndMemberIds_requireActiveMembershipActiveAccountAndSameTenantRole() {
    long roleA = fx.createRole(tenantA, prefix + "-a", "d");
    long roleB = fx.createRole(tenantB, prefix + "-b", "d");
    fx.grantPermission(tenantA, roleA, pRead);
    fx.grantPermission(tenantA, roleA, pWrite);
    fx.grantPermission(tenantB, roleB, pAdmin);

    long ok = fx.createUser(prefix + "-ok", null);
    long suspended = fx.createUser(prefix + "-sus", null);
    long inactive = fx.createUser(prefix + "-ina", null);
    long noMembership = fx.createUser(prefix + "-nom", null);
    long both = fx.createUser(prefix + "-both", null);
    for (long u : new long[] {ok, inactive, both}) {
      fx.createMembership(u, tenantA, "ACTIVE");
    }
    fx.createMembership(suspended, tenantA, "SUSPENDED");
    fx.createMembership(both, tenantB, "ACTIVE");
    for (long u : new long[] {ok, suspended, inactive, noMembership, both}) {
      fx.grantRole(tenantA, u, roleA);
    }
    fx.grantRole(tenantB, both, roleB);
    fx.setUserActive(inactive, false);

    assertThat(fx.inTenant(tenantA, () -> repository().findPermissionCodesByUserId(ok)))
        .containsExactlyInAnyOrder(code("read"), code("write"));
    assertThat(fx.inTenant(tenantA, () -> repository().findPermissionCodesByUserId(suspended)))
        .isEmpty();
    assertThat(fx.inTenant(tenantA, () -> repository().findPermissionCodesByUserId(inactive)))
        .isEmpty();
    assertThat(fx.inTenant(tenantA, () -> repository().findPermissionCodesByUserId(noMembership)))
        .isEmpty();
    // 두 테넌트에 속한 사용자는 현재 테넌트의 역할 권한만 받는다.
    assertThat(fx.inTenant(tenantA, () -> repository().findPermissionCodesByUserId(both)))
        .containsExactlyInAnyOrder(code("read"), code("write"));
    assertThat(fx.inTenant(tenantB, () -> repository().findPermissionCodesByUserId(both)))
        .containsExactly(code("admin"));
    // 테넌트 없는 세션에는 아무 권한도 보이지 않는다.
    assertThat(fx.inTenant(null, () -> repository().findPermissionCodesByUserId(ok))).isEmpty();

    assertThat(
            fx.inTenant(
                tenantA,
                () -> repository().findActiveMemberIdsWithPermission(tenantA, code("read"))))
        .containsExactlyInAnyOrder(ok, both);
    assertThat(
            fx.inTenant(
                tenantA,
                () -> repository().findActiveMemberIdsWithPermission(tenantA, code("admin"))))
        .isEmpty();
    assertThat(
            fx.inTenant(
                tenantB,
                () -> repository().findActiveMemberIdsWithPermission(tenantB, code("admin"))))
        .containsExactly(both);
  }
}
