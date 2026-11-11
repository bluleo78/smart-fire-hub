package com.smartfirehub.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.smartfirehub.user.dto.UserResponse;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * UserRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-706).
 *
 * <p>user 는 전역 테이블이라 사용자 조회(로그인 경로)는 테넌트 없이 돈다(리뷰 포인트 1) — {@code fx.noTenant}. 테넌트 경계는 tenantId 를
 * 받는 목록·활성 ADMIN 문장의 membership 조인과 user_role·role 의 RLS 가 세운다.
 */
abstract class UserRepositoryContractTest extends AccessContractBase {

  protected abstract UserRepository repository();

  private long tenantA;
  private long tenantB;

  @BeforeEach
  void createTenants() {
    tenantA = fx.createTenant();
    tenantB = fx.createTenant();
  }

  /** 테넌트 없이(로그인 경로) 호출한다. */
  private <T> T login(java.util.function.Supplier<T> call) {
    return fx.noTenant(call);
  }

  private long member(String suffix, long tenantId, String status) {
    long id = fx.createUser(prefix + "-" + suffix, prefix + "-" + suffix + "@example.com");
    fx.createMembership(id, tenantId, status);
    return id;
  }

  // ── 저장·조회 ──────────────────────────────────────────────

  /** null id 는 jOOQ(`= NULL`)처럼 행 없음이다 — 매퍼 인자 언박싱 NPE 가 아니다(WD-706 리뷰). */
  @Test
  void findById_nullId_returnsEmpty() {
    assertThat(login(() -> repository().findById(null))).isEmpty();
  }

  @Test
  void save_returnsRow_andLookupsWorkWithoutTenant() {
    UserResponse saved =
        login(() -> repository().save(prefix + "-a", prefix + "-a@example.com", "pw1", "홍길동"));

    assertThat(saved.id()).isNotNull();
    assertThat(saved.username()).isEqualTo(prefix + "-a");
    assertThat(saved.email()).isEqualTo(prefix + "-a@example.com");
    assertThat(saved.name()).isEqualTo("홍길동");
    assertThat(saved.isActive()).isTrue();
    assertThat(saved.mustChangePassword()).isFalse();
    // DB 기본 시각 — DB 세션 시간대와 JVM 시간대가 어긋나면(예: 9시간) 여기서 드러나도록 몇 분 안쪽만 본다.
    assertThat(saved.createdAt()).isCloseTo(LocalDateTime.now(), within(3, ChronoUnit.MINUTES));
    // users 는 test 가 끝나면 지워지도록 추적한다.
    track(saved.id());

    assertThat(login(() -> repository().findByUsername(prefix + "-a"))).contains(saved);
    assertThat(login(() -> repository().findById(saved.id()))).contains(saved);
    assertThat(login(() -> repository().findByUsername(prefix + "-none"))).isEmpty();
    assertThat(login(() -> repository().findById(-1L))).isEmpty();
    assertThat(login(() -> repository().findPasswordByUsername(prefix + "-a"))).contains("pw1");
    assertThat(login(() -> repository().findPasswordById(saved.id()))).contains("pw1");
    assertThat(login(() -> repository().findPasswordById(-1L))).isEmpty();
    assertThat(login(() -> repository().findPasswordByUsername(prefix + "-none"))).isEmpty();
    assertThat(login(() -> repository().existsByUsername(prefix + "-a"))).isTrue();
    assertThat(login(() -> repository().existsByUsername(prefix + "-none"))).isFalse();
    assertThat(login(() -> repository().existsAnyUser())).isTrue();
  }

  /** save 로 만든 행도 픽스처 정리 대상으로 삼는다 — 사용자 id 를 추적 목록에 넣는다. */
  private void track(long userId) {
    fx.trackUser(userId);
  }

  @Test
  void saveWithTemporaryPassword_setsFlag_andUpdatePasswordClearsIt() {
    UserResponse temp =
        login(
            () ->
                repository()
                    .saveWithTemporaryPassword(
                        prefix + "-t", prefix + "-t@example.com", "tmp", "임시"));
    track(temp.id());
    assertThat(temp.mustChangePassword()).isTrue();

    login(
        () -> {
          repository().updatePassword(temp.id(), "newpw");
          return null;
        });

    assertThat(login(() -> repository().findPasswordById(temp.id()))).contains("newpw");
    assertThat(login(() -> repository().findById(temp.id())).orElseThrow().mustChangePassword())
        .isFalse();
  }

  @Test
  void save_duplicateUsername_throwsDuplicateKeyException() {
    UserResponse first = login(() -> repository().save(prefix + "-d", null, "p", "n"));
    track(first.id());

    assertThatThrownBy(() -> login(() -> repository().save(prefix + "-d", null, "p", "n")))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void save_nullEmail_isReadBackAsNull() {
    UserResponse saved = login(() -> repository().save(prefix + "-ne", null, "p", "n"));
    track(saved.id());

    assertThat(saved.email()).isNull();
    assertThat(login(() -> repository().findById(saved.id())).orElseThrow().email()).isNull();
  }

  /**
   * 빈 문자열(리뷰 포인트 2, controller Ruling) — 값을 변환하지 않고 DB 고유 의미를 그대로 계약으로 둔다. PG 는 {@code ""} 를 그대로
   * 저장·조회한다. Tibero 는 {@code ''} 를 NULL 로 저장하므로 NULL 허용 열(email)은 null 로 돌아오고, NOT NULL
   * 열(username·name·password)은 거절된다 ({@link #emptyStringIsNull()}).
   */
  @Test
  void emptyString_nullableEmail_roundTripsPerDb() {
    UserResponse saved = login(() -> repository().save(prefix + "-em", "", "p", "n"));
    track(saved.id());

    String expected = emptyStringIsNull() ? null : "";
    assertThat(saved.email()).isEqualTo(expected);
    assertThat(login(() -> repository().findById(saved.id())).orElseThrow().email())
        .isEqualTo(expected);
    // 값이 있던 이메일을 빈 값으로 고쳐도 같다.
    UserResponse other = login(() -> repository().save(prefix + "-e2", "x@example.com", "p", "n"));
    track(other.id());
    assertThat(login(() -> repository().update(other.id(), "이름", ""))).isTrue();
    assertThat(login(() -> repository().findById(other.id())).orElseThrow().email())
        .isEqualTo(expected);
    assertThat(login(() -> repository().update(other.id(), "이름", null))).isTrue();
    assertThat(login(() -> repository().findById(other.id())).orElseThrow().email()).isNull();
  }

  /**
   * NOT NULL 열(username·name·password)에 빈 문자열: PG 는 저장·조회, Tibero 는
   * 거절(DataIntegrityViolationException 계열).
   */
  @Test
  void emptyString_notNullColumns_perDb() {
    if (emptyStringIsNull()) {
      assertThatThrownBy(() -> login(() -> repository().save(prefix + "-n1", null, "p", "")))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(() -> login(() -> repository().save(prefix + "-n2", null, "", "n")))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(() -> login(() -> repository().save("", null, "p", "n")))
          .isInstanceOf(DataIntegrityViolationException.class);
      UserResponse ok = login(() -> repository().save(prefix + "-n3", null, "p", "n"));
      track(ok.id());
      assertThatThrownBy(() -> login(() -> repository().update(ok.id(), "", null)))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(
              () ->
                  login(
                      () -> {
                        repository().updatePassword(ok.id(), "");
                        return null;
                      }))
          .isInstanceOf(DataIntegrityViolationException.class);
      return;
    }
    UserResponse saved = login(() -> repository().save("", null, "", ""));
    track(saved.id());
    assertThat(saved.username()).isEmpty();
    assertThat(saved.name()).isEmpty();
    assertThat(login(() -> repository().findByUsername(""))).isPresent();
    assertThat(login(() -> repository().existsByUsername(""))).isTrue();
    assertThat(login(() -> repository().findPasswordById(saved.id()))).contains("");
    assertThat(login(() -> repository().findPasswordByUsername(""))).contains("");
    assertThat(login(() -> repository().update(saved.id(), "", null))).isTrue();
  }

  // ── 로그인 이름 해석 ───────────────────────────────────────

  @Test
  void findByUsernameIgnoreCase_andResolveLoginUsername() {
    String stored = "Mixed-" + prefix + "@Example.com";
    UserResponse saved = login(() -> repository().save(stored, null, "p", "n"));
    track(saved.id());

    assertThat(login(() -> repository().findByUsernameIgnoreCase(stored.toUpperCase())))
        .map(UserResponse::id)
        .contains(saved.id());
    assertThat(login(() -> repository().findByUsernameIgnoreCase("nobody-" + prefix))).isEmpty();
    // 정확 일치가 먼저 — 앞뒤 공백은 잘라 낸다.
    assertThat(login(() -> repository().resolveLoginUsername("  " + stored + " ")))
        .isEqualTo(stored);
    // 대소문자만 다르면 저장된 이름으로 해석한다.
    assertThat(login(() -> repository().resolveLoginUsername(stored.toLowerCase())))
        .isEqualTo(stored);
    // 찾지 못하면 소문자로 정규화한 입력(잠금 카운터 키).
    assertThat(login(() -> repository().resolveLoginUsername("Nobody-" + prefix + "@X.COM")))
        .isEqualTo("nobody-" + prefix + "@x.com");
  }

  @Test
  void findByUsernameIgnoreCase_picksSmallestId_whenOnlyCaseDiffers() {
    UserResponse first = login(() -> repository().save("dup-" + prefix, null, "p", "n"));
    UserResponse second = login(() -> repository().save("DUP-" + prefix, null, "p", "n"));
    track(first.id());
    track(second.id());

    assertThat(login(() -> repository().findByUsernameIgnoreCase("Dup-" + prefix)))
        .map(UserResponse::id)
        .contains(first.id());
    assertThat(login(() -> repository().resolveLoginUsername("dUP-" + prefix)))
        .isEqualTo("dup-" + prefix);
  }

  // ── 수정·활성 ──────────────────────────────────────────────

  @Test
  void update_changesNameAndEmail_returnsFalseForUnknownId() {
    UserResponse saved = login(() -> repository().save(prefix + "-u", "a@example.com", "p", "옛"));
    track(saved.id());

    assertThat(login(() -> repository().update(saved.id(), "새", "b@example.com"))).isTrue();
    UserResponse after = login(() -> repository().findById(saved.id())).orElseThrow();
    assertThat(after.name()).isEqualTo("새");
    assertThat(after.email()).isEqualTo("b@example.com");
    assertThat(login(() -> repository().update(-1L, "x", "y"))).isFalse();
  }

  @Test
  void setActive_returnsChangedRowCount_zeroWhenAlreadyThatValue() {
    UserResponse saved = login(() -> repository().save(prefix + "-s", null, "p", "n"));
    track(saved.id());

    assertThat(login(() -> repository().setActive(saved.id(), true))).isZero();
    assertThat(login(() -> repository().setActive(saved.id(), false))).isEqualTo(1);
    assertThat(login(() -> repository().setActive(saved.id(), false))).isZero();
    assertThat(login(() -> repository().findById(saved.id())).orElseThrow().isActive()).isFalse();
    assertThat(login(() -> repository().setActive(saved.id(), true))).isEqualTo(1);
    assertThat(login(() -> repository().findById(saved.id())).orElseThrow().isActive()).isTrue();
  }

  // ── 목록·검색·페이지 ───────────────────────────────────────

  @Test
  void findAllPaginated_secondPage_orderedById_includesSuspended_excludesOtherTenants() {
    long u1 = member("1", tenantA, "ACTIVE");
    long u2 = member("2", tenantA, "SUSPENDED");
    long u3 = member("3", tenantA, "ACTIVE");
    long u4 = member("4", tenantA, "ACTIVE");
    long u5 = member("5", tenantA, "ACTIVE");
    member("other", tenantB, "ACTIVE");
    fx.createUser(prefix + "-nomember", null);

    List<Long> page0 = ids(login(() -> repository().findAllPaginated(tenantA, prefix, 0, 2)));
    List<Long> page1 = ids(login(() -> repository().findAllPaginated(tenantA, prefix, 1, 2)));
    List<Long> page2 = ids(login(() -> repository().findAllPaginated(tenantA, prefix, 2, 2)));
    List<Long> page3 = ids(login(() -> repository().findAllPaginated(tenantA, prefix, 3, 2)));

    assertThat(page0).containsExactly(u1, u2);
    assertThat(page1).containsExactly(u3, u4);
    assertThat(page2).containsExactly(u5);
    assertThat(page3).isEmpty();
    assertThat(login(() -> repository().countAll(tenantA, prefix))).isEqualTo(5);
    assertThat(login(() -> repository().countAll(tenantB, prefix))).isEqualTo(1);
  }

  @Test
  void findAllPaginated_search_isCaseInsensitive_acrossUsernameNameEmail_andEscapesWildcards() {
    long byUsername = member("MiXed", tenantA, "ACTIVE");
    long plain = member("plain", tenantA, "ACTIVE");
    // 이름에 와일드카드 문자가 든 사용자 — 검색어의 % 는 글자 그대로 맞아야 한다.
    UserResponse percent =
        login(
            () -> repository().save(prefix + "-pct", prefix + "-pct@example.com", "p", "100%sure"));
    track(percent.id());
    fx.createMembership(percent.id(), tenantA, "ACTIVE");

    assertThat(ids(login(() -> repository().findAllPaginated(tenantA, prefix + "-MIXED", 0, 10))))
        .containsExactly(byUsername);
    assertThat(ids(login(() -> repository().findAllPaginated(tenantA, "100%", 0, 10))))
        .containsExactly(percent.id());
    assertThat(login(() -> repository().countAll(tenantA, "100%"))).isEqualTo(1);
    assertThat(login(() -> repository().countAll(tenantA, prefix + "-PLAIN@EXAMPLE"))).isEqualTo(1);
    // 검색어가 비면 필터 없음.
    assertThat(ids(login(() -> repository().findAllPaginated(tenantA, "  ", 0, 50))))
        .contains(byUsername, plain, percent.id());
    assertThat(ids(login(() -> repository().findAllPaginated(tenantA, null, 0, 50))))
        .contains(byUsername, plain, percent.id());
  }

  @Test
  void findByIds_onlyActiveMembersOfTenant() {
    long active = member("act", tenantA, "ACTIVE");
    long suspended = member("sus", tenantA, "SUSPENDED");
    long otherTenant = member("oth", tenantB, "ACTIVE");

    List<UserResponse> found =
        login(() -> repository().findByIds(tenantA, List.of(active, suspended, otherTenant, -1L)));

    assertThat(ids(found)).containsExactly(active);
    assertThat(login(() -> repository().findByIds(tenantA, List.of()))).isEmpty();
    assertThat(login(() -> repository().findByIds(tenantA, null))).isEmpty();
  }

  /** IN 목록이 한도(Tibero 1000)를 넘어도 나눠 조회해 끝쪽 id 까지 찾는다. */
  @Test
  void findByIds_manyIds_areChunked_andFindMemberAtTheEnd() {
    long active = member("many", tenantA, "ACTIVE");
    List<Long> ids = new java.util.ArrayList<>();
    for (long i = 1; i <= 1200; i++) {
      ids.add(-i);
    }
    ids.add(active);

    assertThat(ids(login(() -> repository().findByIds(tenantA, ids)))).containsExactly(active);
  }

  // ── 활성 ADMIN·역할 배정 ───────────────────────────────────

  @Test
  void activeAdmins_countAndIds_requireAdminRoleActiveMembershipAndActiveAccount() {
    long roleA = fx.createRole(tenantA, "ADMIN", "관리자");
    long roleB = fx.createRole(tenantB, "ADMIN", "관리자");
    long ok1 = member("ok1", tenantA, "ACTIVE");
    long ok2 = member("ok2", tenantA, "ACTIVE");
    long suspended = member("sus", tenantA, "SUSPENDED");
    long inactive = member("ina", tenantA, "ACTIVE");
    long noRole = member("nor", tenantA, "ACTIVE");
    long inB = member("inb", tenantB, "ACTIVE");
    for (long u : new long[] {ok1, ok2, suspended, inactive}) {
      fx.grantRole(tenantA, u, roleA);
    }
    fx.grantRole(tenantB, inB, roleB);
    fx.setUserActive(inactive, false);

    assertThat(fx.inTenant(tenantA, () -> repository().countActiveAdmins(tenantA))).isEqualTo(2);
    assertThat(fx.inTenant(tenantA, () -> repository().findActiveAdminIds(tenantA)))
        .containsExactly(ok1, ok2);
    assertThat(fx.inTenant(tenantB, () -> repository().countActiveAdmins(tenantB))).isEqualTo(1);
    assertThat(fx.inTenant(tenantB, () -> repository().findActiveAdminIds(tenantB)))
        .containsExactly(inB);
    assertThat(noRole).isNotEqualTo(ok1);
  }

  @Test
  void roleAssignment_addRemoveSetRoles_hasAdminRole_andDuplicateAssignmentThrows() {
    long admin = fx.createRole(tenantA, "ADMIN", "관리자");
    long other = fx.createRole(tenantA, "SUPPORT", "지원");
    long user = member("r", tenantA, "ACTIVE");

    assertThat(fx.inTenant(tenantA, () -> repository().hasAdminRole(user))).isFalse();
    fx.inTenant(
        tenantA,
        () -> {
          repository().addRole(user, admin);
          return null;
        });
    assertThat(fx.inTenant(tenantA, () -> repository().hasAdminRole(user))).isTrue();
    assertThatThrownBy(
            () ->
                fx.inTenant(
                    tenantA,
                    () -> {
                      repository().addRole(user, admin);
                      return null;
                    }))
        .isInstanceOf(DuplicateKeyException.class);

    fx.inTenant(
        tenantA,
        () -> {
          repository().removeRole(user, admin);
          return null;
        });
    assertThat(fx.inTenant(tenantA, () -> repository().hasAdminRole(user))).isFalse();

    fx.inTenant(
        tenantA,
        () -> {
          repository().setRoles(user, List.of(admin, other));
          return null;
        });
    assertThat(fx.inTenant(tenantA, () -> repository().hasAdminRole(user))).isTrue();
    fx.inTenant(
        tenantA,
        () -> {
          repository().setRoles(user, List.of(other));
          return null;
        });
    assertThat(fx.inTenant(tenantA, () -> repository().hasAdminRole(user))).isFalse();
    // 남은 배정 수 = 1 (SUPPORT 하나).
    assertThat(fx.inTenant(tenantA, () -> repository().deleteRolesInTenant(user, tenantA)))
        .isEqualTo(1);
    // 빈 목록은 모두 지운다.
    fx.inTenant(
        tenantA,
        () -> {
          repository().setRoles(user, List.of(admin, other));
          repository().setRoles(user, List.of());
          return null;
        });
    assertThat(fx.inTenant(tenantA, () -> repository().deleteRolesInTenant(user, tenantA)))
        .isZero();
  }

  @Test
  void deleteRolesInTenant_removesOnlyThatTenantsAssignments_andOtherTenantRolesAreHidden() {
    long adminA = fx.createRole(tenantA, "ADMIN", "관리자");
    long adminB = fx.createRole(tenantB, "ADMIN", "관리자");
    long user = member("m", tenantA, "ACTIVE");
    fx.createMembership(user, tenantB, "ACTIVE");
    fx.grantRole(tenantA, user, adminA);
    fx.grantRole(tenantB, user, adminB);

    // 테넌트 A 컨텍스트에서는 B 의 배정이 보이지 않는다(RLS) — 있다고 세지 않는다.
    assertThat(fx.inTenant(tenantA, () -> repository().hasAdminRole(user))).isTrue();
    assertThat(fx.inTenant(tenantA, () -> repository().deleteRolesInTenant(user, tenantA)))
        .isEqualTo(1);
    assertThat(fx.inTenant(tenantA, () -> repository().hasAdminRole(user))).isFalse();
    assertThat(fx.inTenant(tenantB, () -> repository().hasAdminRole(user))).isTrue();
    // 테넌트 없는 세션에는 어느 쪽 배정도 보이지 않는다.
    assertThat(fx.inTenant(null, () -> repository().hasAdminRole(user))).isFalse();
  }

  // ── 직렬화 잠금 ────────────────────────────────────────────

  /** 트랜잭션 안에서 두 잠금을 잡는다 — 예외 없이 지나가야 한다(Tibero 계약 시험은 가짜 잠금을 쓴다). */
  @Test
  void locks_canBeTakenInsideTransaction() {
    fixtureTransactionTemplate.executeWithoutResult(
        s -> {
          repository().acquireFirstUserLock();
          repository().acquireMemberAdminGuardLock(tenantA);
        });
  }

  private static List<Long> ids(List<UserResponse> users) {
    return users.stream().map(UserResponse::id).toList();
  }
}
