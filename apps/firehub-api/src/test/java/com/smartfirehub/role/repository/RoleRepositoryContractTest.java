package com.smartfirehub.role.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.user.repository.AccessContractBase;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * RoleRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>role·user_role·role_permission 은 테넌트 정책 대상이다 — 다른 테넌트의 역할·배정은 보이지 않고, 테넌트 없는 세션에는 아무것도 보이지
 * 않는다. 호출은 모두 테넌트 컨텍스트(트랜잭션) 안에서 한다. 기존 {@code RoleRepositoryTenantScopeContractTest} 는 그대로 두고
 * 인터페이스의 나머지 메서드를 여기서 덮는다.
 */
public abstract class RoleRepositoryContractTest extends AccessContractBase {

  protected abstract RoleRepository repository();

  private long tenantA;
  private long tenantB;

  @BeforeEach
  void createTenants() {
    tenantA = fx.createTenant();
    tenantB = fx.createTenant();
  }

  private <T> T inA(java.util.function.Supplier<T> call) {
    return fx.inTenant(tenantA, call);
  }

  private void inA(Runnable call) {
    fx.inTenant(
        tenantA,
        () -> {
          call.run();
          return null;
        });
  }

  @Test
  void save_returnsRow_findByIdNameExistsAndFindAll() {
    RoleResponse saved = inA(() -> repository().save(prefix + "-r1", "설명"));
    RoleResponse second = inA(() -> repository().save(prefix + "-r2", "두번째"));

    assertThat(saved.id()).isNotNull();
    assertThat(saved.name()).isEqualTo(prefix + "-r1");
    assertThat(saved.description()).isEqualTo("설명");
    assertThat(saved.isSystem()).isFalse();
    assertThat(inA(() -> repository().findById(saved.id()))).contains(saved);
    assertThat(inA(() -> repository().findByName(prefix + "-r1"))).contains(saved);
    assertThat(inA(() -> repository().existsByName(prefix + "-r1"))).isTrue();
    assertThat(inA(() -> repository().findById(-1L))).isEmpty();
    assertThat(inA(() -> repository().findByName(prefix + "-none"))).isEmpty();
    assertThat(inA(() -> repository().existsByName(prefix + "-none"))).isFalse();
    // 새 테넌트에는 이 두 역할뿐이고 id 오름차순이다.
    assertThat(inA(() -> repository().findAll())).containsExactly(saved, second);
  }

  /** 다른 테넌트·테넌트 없는 세션에는 역할이 보이지 않는다(리뷰: 정책 테이블). */
  @Test
  void rolesOfOtherTenantAreInvisible_andNoTenantSeesNothing() {
    long inB = fx.createRole(tenantB, prefix + "-b", "B 전용");

    assertThat(inA(() -> repository().findById(inB))).isEmpty();
    assertThat(inA(() -> repository().findByName(prefix + "-b"))).isEmpty();
    assertThat(inA(() -> repository().existsByName(prefix + "-b"))).isFalse();
    assertThat(inA(() -> repository().findAll())).isEmpty();
    assertThat(inA(() -> repository().findExistingIds(List.of(inB)))).isEmpty();
    assertThat(inA(() -> repository().deleteById(inB))).isZero();

    assertThat(fx.inTenant(null, () -> repository().findById(inB))).isEmpty();
    assertThat(fx.inTenant(null, () -> repository().findAll())).isEmpty();
    assertThat(fx.inTenant(tenantB, () -> repository().findById(inB))).isPresent();
  }

  @Test
  void findExistingIds_returnsOnlyVisibleIds() {
    long mine1 = fx.createRole(tenantA, prefix + "-1", "d");
    long mine2 = fx.createRole(tenantA, prefix + "-2", "d");
    long foreign = fx.createRole(tenantB, prefix + "-3", "d");

    assertThat(inA(() -> repository().findExistingIds(List.of(mine1, mine2, foreign, -5L))))
        .containsExactlyInAnyOrder(mine1, mine2);
    assertThat(inA(() -> repository().findExistingIds(List.of()))).isEmpty();
  }

  /** 중복 이름은 같은 테넌트에서만 막히고 두 DB 모두 DuplicateKeyException 이다(리뷰 포인트 3). */
  @Test
  void save_duplicateNameInSameTenant_throwsDuplicateKey_butOtherTenantMayReuse() {
    inA(() -> repository().save(prefix + "-dup", "d"));

    assertThatThrownBy(() -> inA(() -> repository().save(prefix + "-dup", "d")))
        .isInstanceOf(DuplicateKeyException.class);
    RoleResponse inOther = fx.inTenant(tenantB, () -> repository().save(prefix + "-dup", "d"));
    assertThat(inOther.name()).isEqualTo(prefix + "-dup");
  }

  @Test
  void update_duplicateName_throwsDuplicateKey() {
    inA(() -> repository().save(prefix + "-x", "d"));
    RoleResponse y = inA(() -> repository().save(prefix + "-y", "d"));

    assertThatThrownBy(() -> inA(() -> repository().update(y.id(), prefix + "-x", "d")))
        .isInstanceOf(DuplicateKeyException.class);
  }

  /**
   * 역할 설명 빈 문자열(리뷰 포인트 2, controller Ruling) — 변환하지 않고 DB 고유 의미를 계약으로 둔다. PG 는 {@code ""} 를 그대로,
   * Tibero 는 {@code ''} 를 NULL 로 저장해 null 로 돌려준다({@link #emptyStringIsNull()}). null 은 두 DB 모두
   * null.
   */
  @Test
  void emptyAndNullDescription_roundTripPerDb() {
    String expectedEmpty = emptyStringIsNull() ? null : "";
    RoleResponse empty = inA(() -> repository().save(prefix + "-empty", ""));
    RoleResponse nul = inA(() -> repository().save(prefix + "-null", null));

    assertThat(empty.description()).isEqualTo(expectedEmpty);
    assertThat(nul.description()).isNull();
    assertThat(inA(() -> repository().findById(empty.id())).orElseThrow().description())
        .isEqualTo(expectedEmpty);

    inA(() -> repository().update(nul.id(), prefix + "-null", ""));
    inA(() -> repository().update(empty.id(), prefix + "-empty", null));
    assertThat(inA(() -> repository().findById(nul.id())).orElseThrow().description())
        .isEqualTo(expectedEmpty);
    assertThat(inA(() -> repository().findById(empty.id())).orElseThrow().description()).isNull();
  }

  /** NOT NULL 인 이름에 빈 문자열: PG 는 저장·조회, Tibero 는 거절(DataIntegrityViolationException 계열). */
  @Test
  void emptyName_perDb() {
    if (emptyStringIsNull()) {
      assertThatThrownBy(() -> inA(() -> repository().save("", "d")))
          .isInstanceOf(DataIntegrityViolationException.class);
      RoleResponse ok = inA(() -> repository().save(prefix + "-n", "d"));
      assertThatThrownBy(() -> inA(() -> repository().update(ok.id(), "", "d")))
          .isInstanceOf(DataIntegrityViolationException.class);
      return;
    }
    RoleResponse saved = inA(() -> repository().save("", "d"));

    assertThat(saved.name()).isEmpty();
    assertThat(inA(() -> repository().findByName(""))).contains(saved);
    assertThat(inA(() -> repository().existsByName(""))).isTrue();
  }

  @Test
  void update_changesNameAndDescription_andStampsUpdatedAt() {
    RoleResponse saved = inA(() -> repository().save(prefix + "-u", "옛"));

    inA(() -> repository().update(saved.id(), prefix + "-u2", "새"));

    RoleResponse after = inA(() -> repository().findById(saved.id())).orElseThrow();
    assertThat(after.name()).isEqualTo(prefix + "-u2");
    assertThat(after.description()).isEqualTo("새");
    // 수정 시각은 JVM 의 벽시계로 찍힌다 — 왕복해도 어긋나지 않는다(리뷰 포인트 5).
    LocalDateTime updatedAt = inA(() -> repository().findUpdatedAtById(saved.id()));
    assertThat(updatedAt)
        .isCloseTo(
            LocalDateTime.now(), org.assertj.core.api.Assertions.within(2, ChronoUnit.MINUTES));
  }

  @Test
  void updatedAtReaders_handleMissingRow_andNullTimestamp() {
    long id = fx.createRole(tenantA, prefix + "-v", "d");

    assertThat(inA(() -> repository().findUpdatedAtById(-1L))).isNull();
    assertThat(inA(() -> repository().lockUpdatedAtById(-1L))).isEmpty();

    Optional<LockedRowVersion> locked = inA(() -> repository().lockUpdatedAtById(id));
    assertThat(locked).isPresent();
    assertThat(locked.get().updatedAt()).isNotNull();

    fx.clearRoleUpdatedAt(tenantA, id);
    // 행은 있는데 수정 시각이 비어 있으면 "없음" 과 구분된다.
    assertThat(inA(() -> repository().findUpdatedAtById(id))).isNull();
    Optional<LockedRowVersion> blank = inA(() -> repository().lockUpdatedAtById(id));
    assertThat(blank).isPresent();
    assertThat(blank.get().updatedAt()).isNull();
    // 다른 테넌트 행은 잠글 수도 없다.
    assertThat(fx.inTenant(tenantB, () -> repository().lockUpdatedAtById(id))).isEmpty();
  }

  @Test
  void lockById_existingAndMissing_doNotThrow() {
    long id = fx.createRole(tenantA, prefix + "-l", "d");

    inA(() -> repository().lockById(id));
    inA(() -> repository().lockById(-1L));
  }

  @Test
  void deleteById_returnsDeletedCount_andCascadesAssignments() {
    long role = fx.createRole(tenantA, prefix + "-del", "d");
    long user = fx.createUser(prefix + "-du", null);
    fx.createMembership(user, tenantA, "ACTIVE");
    fx.grantRole(tenantA, user, role);

    assertThat(inA(() -> repository().findUserIdsByRoleId(role))).containsExactly(user);
    assertThat(inA(() -> repository().deleteById(role))).isEqualTo(1);
    assertThat(inA(() -> repository().deleteById(role))).isZero();
    assertThat(inA(() -> repository().findById(role))).isEmpty();
    assertThat(inA(() -> repository().findByUserId(user))).isEmpty();
  }

  @Test
  void findUserIdsByRoleId_isAscending_andTenantScoped() {
    long role = fx.createRole(tenantA, prefix + "-ids", "d");
    long u1 = fx.createUser(prefix + "-1", null);
    long u2 = fx.createUser(prefix + "-2", null);
    long u3 = fx.createUser(prefix + "-3", null);
    fx.grantRole(tenantA, u3, role);
    fx.grantRole(tenantA, u1, role);
    fx.grantRole(tenantA, u2, role);

    assertThat(inA(() -> repository().findUserIdsByRoleId(role))).containsExactly(u1, u2, u3);
    assertThat(fx.inTenant(tenantB, () -> repository().findUserIdsByRoleId(role))).isEmpty();
  }

  @Test
  void permissions_addRemoveSet_andDuplicateAddThrows() {
    long role = fx.createRole(tenantA, prefix + "-p", "d");
    long p1 = fx.createPermission(prefix + ":a", "cat-" + prefix, "d");
    long p2 = fx.createPermission(prefix + ":b", "cat-" + prefix, "d");
    long p3 = fx.createPermission(prefix + ":c", "cat-" + prefix, "d");

    inA(() -> repository().addPermission(role, p1));
    assertThat(fx.permissionIdsOfRole(tenantA, role)).containsExactly(p1);
    assertThatThrownBy(() -> inA(() -> repository().addPermission(role, p1)))
        .isInstanceOf(DuplicateKeyException.class);

    inA(() -> repository().setPermissions(role, List.of(p2, p3)));
    assertThat(fx.permissionIdsOfRole(tenantA, role)).containsExactly(p2, p3);
    inA(() -> repository().removePermission(role, p2));
    assertThat(fx.permissionIdsOfRole(tenantA, role)).containsExactly(p3);
    inA(() -> repository().setPermissions(role, List.of()));
    assertThat(fx.permissionIdsOfRole(tenantA, role)).isEmpty();
  }

  @Test
  void findByUserId_andFindByUserIds_orderedByRoleId_missingUsersHaveNoKey() {
    long r1 = fx.createRole(tenantA, prefix + "-a", "설A");
    long r2 = fx.createRole(tenantA, prefix + "-b", "설B");
    long foreign = fx.createRole(tenantB, prefix + "-f", "F");
    long u1 = fx.createUser(prefix + "-1", null);
    long u2 = fx.createUser(prefix + "-2", null);
    long none = fx.createUser(prefix + "-3", null);
    fx.grantRole(tenantA, u1, r2);
    fx.grantRole(tenantA, u1, r1);
    fx.grantRole(tenantA, u2, r1);
    fx.grantRole(tenantB, u2, foreign);

    List<RoleResponse> forU1 = inA(() -> repository().findByUserId(u1));
    assertThat(forU1).extracting(RoleResponse::id).containsExactly(r1, r2);
    assertThat(forU1.get(0).description()).isEqualTo("설A");
    // 다른 테넌트 배정은 보이지 않는다.
    assertThat(inA(() -> repository().findByUserId(u2)))
        .extracting(RoleResponse::id)
        .containsExactly(r1);
    assertThat(inA(() -> repository().findByUserId(none))).isEmpty();

    Map<Long, List<RoleResponse>> map =
        inA(() -> repository().findByUserIds(List.of(u1, u2, none)));
    assertThat(map.keySet()).containsExactly(u1, u2);
    assertThat(map.get(u1)).extracting(RoleResponse::id).containsExactly(r1, r2);
    assertThat(map.get(u2)).extracting(RoleResponse::id).containsExactly(r1);
    assertThat(inA(() -> repository().findByUserIds(List.of()))).isEmpty();
    assertThat(inA(() -> repository().findByUserIds(null))).isEmpty();
    assertThat(Set.copyOf(map.keySet())).doesNotContain(none);
  }
}
