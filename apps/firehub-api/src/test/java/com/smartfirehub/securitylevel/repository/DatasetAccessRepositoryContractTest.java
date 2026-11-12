package com.smartfirehub.securitylevel.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository.AccessFacts;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository.ClearanceRow;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DatasetAccessRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-711).
 *
 * <p>dataset·security_level·dataset_access_grant·role·user_role 은 테넌트 정책(RLS) 아래 있어 리포지토리 호출은 늘 테넌트
 * 컨텍스트({@code inA}/{@code inB})로 감싼다. membership·사용자는 정책 없는 전역 테이블이라 자격 원자료는 명시
 * 조건(user_role.tenant_id + ACTIVE 멤버십 + 활성 계정)으로만 걸러진다(Review Focus 1). 테넌트 생성 때 기본 등급 하나(rank 2,
 * is_default)가 심겨 있으므로 시험이 만드는 등급은 rank 3 이상을 쓴다.
 */
public abstract class DatasetAccessRepositoryContractTest extends TenantPairContractBase {

  protected abstract DatasetAccessRepository repository();

  /**
   * 문자열 술어 렌더({@code visibleSql})를 구현하는가 — jOOQ 참, MyBatis 거짓(소비자가 Tibero 에서 꺼진 벡터·트라이그램 경로뿐이라
   * VECTOR_SEARCH 꺼진 기능 예외, 스펙 D3).
   */
  protected boolean supportsRenderedSql() {
    return true;
  }

  /** 데이터셋을 만든 사람(dataset.created_by FK) — 시험마다 하나. */
  private long creator;

  @BeforeEach
  void createCreator() {
    creator = fx.createUser(prefix + "-creator", null);
  }

  /** 테넌트 A 의 등급(rank·허용 목록 필요·ADMIN 우회 지정). */
  private long level(long tenantId, String suffix, int rank, boolean allowlist, boolean bypass) {
    long id = fx.createSecurityLevel(tenantId, rank, prefix + "-" + suffix);
    fx.setSecurityLevelFlags(id, allowlist, bypass);
    return id;
  }

  /** 테이블 이름을 정해 데이터셋 하나를 만든다(levelId null 이면 테넌트 기본 등급). */
  private long dataset(long tenantId, String table, Long levelId) {
    return fx.createDataset(tenantId, creator, table, levelId);
  }

  /** 시험마다 겹치지 않는 소문자 테이블 이름. */
  private String table(String suffix) {
    return "t_" + prefix + "_" + suffix;
  }

  /** 자격 — 권한 코드는 이 리포지토리와 무관하다. */
  private Clearance clearance(long userId, int rank, Set<Long> roleIds, boolean admin) {
    return new Clearance(userId, tenantA, rank, roleIds, admin, Set.of());
  }

  /** 테넌트 A 의 ACTIVE 멤버 사용자. */
  private long member(String suffix) {
    long u = fx.createUser(prefix + "-" + suffix, null);
    fx.createMembership(u, tenantA, "ACTIVE");
    return u;
  }

  /** 테넌트 A 역할 하나(최대 등급 지정)를 만들어 사용자에게 준다. */
  private long roleFor(long userId, String suffix, long levelId) {
    long r = fx.createRole(tenantA, prefix + "-" + suffix, null);
    fx.setRoleMaxLevel(r, levelId);
    fx.grantRole(tenantA, userId, r);
    return r;
  }

  // ------------------------------------------------------------------ visibleSql

  @Test
  @DisplayName("visibleSql — 렌더하는 구현은 별칭으로 술어를 만들고, 꺼진 구현은 VECTOR_SEARCH 꺼진 기능 예외를 던진다")
  void visibleSql_rendersOrIsOff() {
    DatasetVisibility v = new DatasetVisibility(clearance(1L, 3, Set.of(), false), null);
    if (supportsRenderedSql()) {
      assertThat(inA(() -> repository().visibleSql(v, "d"))).contains("\"d\".\"id\"");
      return;
    }
    assertThatThrownBy(() -> inA(() -> repository().visibleSql(v, "d")))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("VECTOR_SEARCH");
  }

  // ------------------------------------------------------------------ 접근 사실

  @Test
  @DisplayName("findFactsByDatasetIds — 테이블 이름·등급 정책·허용 목록(본인 직접·보유 역할)을 담고 없는 id·다른 테넌트 id 는 빠진다")
  void findFactsByDatasetIds() {
    long hi = level(tenantA, "hi", 5, true, true);
    fx.setSecurityLevelAiPolicy(hi, "DENY");
    long u = member("u");
    long role = fx.createRole(tenantA, prefix + "-r", null);
    long direct = dataset(tenantA, table("direct"), hi);
    long byRole = dataset(tenantA, table("byrole"), null);
    long none = dataset(tenantA, table("none"), null);
    long others = dataset(tenantB, table("others"), null);
    fx.grantDatasetAccess(tenantA, direct, null, u);
    fx.grantDatasetAccess(tenantA, byRole, role, null);
    // 다른 사용자 직접 허용은 이 자격의 허용 목록이 아니다.
    fx.grantDatasetAccess(tenantA, none, null, creator);

    Clearance withRole = clearance(u, 5, Set.of(role), false);
    Map<Long, AccessFacts> facts =
        inA(
            () ->
                repository()
                    .findFactsByDatasetIds(List.of(direct, byRole, none, others, -1L), withRole));

    assertThat(facts).containsOnlyKeys(direct, byRole, none);
    AccessFacts d = facts.get(direct);
    assertThat(d.datasetId()).isEqualTo(direct);
    assertThat(d.tableName()).isEqualTo(table("direct"));
    assertThat(d.onAllowlist()).isTrue();
    LevelPolicy lp = d.level();
    assertThat(lp.id()).isEqualTo(hi);
    assertThat(lp.name()).isEqualTo(prefix + "-hi");
    assertThat(lp.rank()).isEqualTo(5);
    assertThat(lp.isDefault()).isFalse();
    assertThat(lp.allowlistRequired()).isTrue();
    assertThat(lp.adminBypass()).isTrue();
    assertThat(lp.aiPolicy()).isEqualTo(LevelPolicy.AiPolicy.DENY);
    assertThat(facts.get(byRole).onAllowlist()).isTrue();
    assertThat(facts.get(byRole).level().isDefault()).isTrue();
    assertThat(facts.get(byRole).level().rank()).isEqualTo(2);
    assertThat(facts.get(none).onAllowlist()).isFalse();

    // 역할이 없는 자격이면 역할 허용은 허용 목록이 아니다.
    Clearance noRole = clearance(u, 5, Set.of(), false);
    Map<Long, AccessFacts> plain =
        inA(() -> repository().findFactsByDatasetIds(List.of(direct, byRole), noRole));
    assertThat(plain.get(direct).onAllowlist()).isTrue();
    assertThat(plain.get(byRole).onAllowlist()).isFalse();

    assertThat(inA(() -> repository().findFactsByDatasetIds(List.of(), withRole))).isEmpty();
    // B 컨텍스트에서는 A 의 데이터셋이 보이지 않는다.
    assertThat(inB(() -> repository().findFactsByDatasetIds(List.of(direct), withRole))).isEmpty();
  }

  @Test
  @DisplayName("findFactsByDatasetIds — id 1001 개(Tibero IN 1000 개 상한)도 조각을 넘나들며 돌려준다")
  void findFactsByDatasetIds_1001Ids() {
    long first = dataset(tenantA, table("first"), null);
    long late = dataset(tenantA, table("late"), null);
    List<Long> ids = new ArrayList<>();
    ids.add(first);
    for (long i = 1; ids.size() < 700; i++) {
      ids.add(-i);
    }
    ids.add(late);
    for (long i = 1000; ids.size() < 1001; i++) {
      ids.add(-i);
    }

    Map<Long, AccessFacts> facts =
        inA(() -> repository().findFactsByDatasetIds(ids, clearance(1L, 2, Set.of(), false)));

    assertThat(facts).containsOnlyKeys(first, late);
  }

  @Test
  @DisplayName("findFactsByTableNames — 테이블 이름으로 사실을 찾고 다른 테넌트의 같은 이름 테이블은 섞이지 않는다")
  void findFactsByTableNames() {
    long u = member("u");
    long a = dataset(tenantA, table("a"), null);
    long b = dataset(tenantA, table("b"), null);
    // 테이블 이름은 테넌트 안에서만 유일하다 — B 의 같은 이름 테이블이 A 의 결과를 덮으면 안 된다.
    dataset(tenantB, table("a"), null);
    fx.grantDatasetAccess(tenantA, a, null, u);

    Map<String, AccessFacts> facts =
        inA(
            () ->
                repository()
                    .findFactsByTableNames(
                        List.of(table("a"), table("b"), table("missing")),
                        clearance(u, 2, Set.of(), false)));

    assertThat(facts).containsOnlyKeys(table("a"), table("b"));
    assertThat(facts.get(table("a")).datasetId()).isEqualTo(a);
    assertThat(facts.get(table("a")).onAllowlist()).isTrue();
    assertThat(facts.get(table("b")).datasetId()).isEqualTo(b);
    assertThat(facts.get(table("b")).onAllowlist()).isFalse();
    assertThat(
            inA(
                () ->
                    repository()
                        .findFactsByTableNames(List.of(), clearance(u, 2, Set.of(), false))))
        .isEmpty();
  }

  @Test
  @DisplayName("findFactsByTableNames — 이름 1001 개(Tibero IN 1000 개 상한)도 조각을 넘나들며 돌려준다")
  void findFactsByTableNames_1001Names() {
    dataset(tenantA, table("first"), null);
    dataset(tenantA, table("late"), null);
    List<String> names = new ArrayList<>();
    names.add(table("first"));
    for (int i = 1; names.size() < 700; i++) {
      names.add(table("x" + i));
    }
    names.add(table("late"));
    for (int i = 1000; names.size() < 1001; i++) {
      names.add(table("x" + i));
    }

    Map<String, AccessFacts> facts =
        inA(() -> repository().findFactsByTableNames(names, clearance(1L, 2, Set.of(), false)));

    assertThat(facts).containsOnlyKeys(table("first"), table("late"));
  }

  // ------------------------------------------------------------------ 역할 부여 범위

  @Test
  @DisplayName(
      "findRoleIdsWithAllowlistNotViewable — 허용 목록 필요 등급에서 rank 초과·허용 목록 미충족 역할만 돌려주고 ADMIN 우회·직접 허용·일반 등급은 뺀다")
  void findRoleIdsWithAllowlistNotViewable() {
    // rank 는 테넌트 안에서 유일하다 — 호출자 rank 4 를 기준으로 hi 만 넘는다.
    long hi = level(tenantA, "hi", 5, true, false);
    long lo = level(tenantA, "lo", 3, true, false);
    long bypass = level(tenantA, "bp", 4, true, true);
    long plain = level(tenantA, "pl", 6, false, false);
    long u = member("u");

    long rHi = fx.createRole(tenantA, prefix + "-rhi", null);
    long rLo = fx.createRole(tenantA, prefix + "-rlo", null);
    long rListed = fx.createRole(tenantA, prefix + "-rlisted", null);
    long rBypass = fx.createRole(tenantA, prefix + "-rbp", null);
    long rPlain = fx.createRole(tenantA, prefix + "-rpl", null);
    long rNone = fx.createRole(tenantA, prefix + "-rnone", null);

    long dsHi = dataset(tenantA, table("hi"), hi);
    long dsLo = dataset(tenantA, table("lo"), lo);
    long dsListed = dataset(tenantA, table("listed"), lo);
    long dsBypass = dataset(tenantA, table("bp"), bypass);
    long dsPlain = dataset(tenantA, table("pl"), plain);
    fx.grantDatasetAccess(tenantA, dsHi, rHi, null);
    fx.grantDatasetAccess(tenantA, dsLo, rLo, null);
    fx.grantDatasetAccess(tenantA, dsListed, rListed, null);
    // 호출자 본인이 직접 허용 목록에 있으면 볼 수 있다.
    fx.grantDatasetAccess(tenantA, dsListed, null, u);
    fx.grantDatasetAccess(tenantA, dsBypass, rBypass, null);
    fx.grantDatasetAccess(tenantA, dsPlain, rPlain, null);

    List<Long> asked = List.of(rHi, rLo, rListed, rBypass, rPlain, rNone);

    Set<Long> nonAdmin =
        inA(
            () ->
                repository()
                    .findRoleIdsWithAllowlistNotViewable(asked, clearance(u, 4, Set.of(), false)));
    assertThat(nonAdmin).containsExactlyInAnyOrder(rHi, rLo, rBypass);

    Set<Long> admin =
        inA(
            () ->
                repository()
                    .findRoleIdsWithAllowlistNotViewable(asked, clearance(u, 4, Set.of(), true)));
    assertThat(admin).containsExactlyInAnyOrder(rHi, rLo);

    // 호출자의 보유 역할이 허용 목록에 있으면 그 데이터셋도 볼 수 있다(역할 rLo 를 가진 호출자).
    Set<Long> holdsLo =
        inA(
            () ->
                repository()
                    .findRoleIdsWithAllowlistNotViewable(
                        asked, clearance(u, 4, Set.of(rLo), false)));
    assertThat(holdsLo).containsExactlyInAnyOrder(rHi, rBypass);

    assertThat(
            inA(
                () ->
                    repository()
                        .findRoleIdsWithAllowlistNotViewable(
                            List.of(), clearance(u, 4, Set.of(), false))))
        .isEmpty();
    // B 컨텍스트에서는 A 의 허용 항목이 보이지 않는다.
    assertThat(
            inB(
                () ->
                    repository()
                        .findRoleIdsWithAllowlistNotViewable(
                            asked, clearance(u, 4, Set.of(), false))))
        .isEmpty();
  }

  // ------------------------------------------------------------------ 자격 원자료

  @Test
  @DisplayName("findClearanceRow — 이 테넌트 ACTIVE 멤버·활성 계정의 역할 rank 최댓값과 역할 id 를 돌려준다")
  void findClearanceRow_activeMember() {
    long l3 = level(tenantA, "l3", 3, false, false);
    long l5 = level(tenantA, "l5", 5, false, false);
    long u = member("u");
    long r3 = roleFor(u, "r3", l3);
    long r5 = roleFor(u, "r5", l5);

    ClearanceRow row = inA(() -> repository().findClearanceRow(u, tenantA));

    assertThat(row.maxRank()).isEqualTo(5);
    assertThat(row.roleIds()).containsExactlyInAnyOrder(r3, r5);
    assertThat(row.tenantAdmin()).isFalse();
  }

  @Test
  @DisplayName(
      "findClearanceRow — 다른 테넌트에만 ACTIVE 멤버십이 있는 사용자·정지 멤버·비활성 계정·역할 없는 사용자는 자격이 없다(Review Focus 1)")
  void findClearanceRow_excludesNonMembers() {
    long l5 = level(tenantA, "l5", 5, false, false);

    long onlyB = fx.createUser(prefix + "-onlyb", null);
    fx.createMembership(onlyB, tenantB, "ACTIVE");
    roleFor(onlyB, "rb", l5);

    long suspended = fx.createUser(prefix + "-susp", null);
    fx.createMembership(suspended, tenantA, "SUSPENDED");
    roleFor(suspended, "rs", l5);

    long inactive = member("inact");
    roleFor(inactive, "ri", l5);
    fx.setUserActive(inactive, false);

    long noRole = member("norole");

    for (long user : List.of(onlyB, suspended, inactive, noRole)) {
      ClearanceRow row = inA(() -> repository().findClearanceRow(user, tenantA));
      assertThat(row.maxRank()).as("user %d", user).isNull();
      assertThat(row.roleIds()).as("user %d", user).isEmpty();
      assertThat(row.tenantAdmin()).as("user %d", user).isFalse();
    }
  }

  @Test
  @DisplayName("findClearanceRow — 인자 테넌트의 역할만 센다(A 컨텍스트에서 B 를 물으면 A 의 역할이 섞이지 않는다)")
  void findClearanceRow_explicitTenant() {
    long l5 = level(tenantA, "l5", 5, false, false);
    long u = member("u");
    fx.createMembership(u, tenantB, "ACTIVE");
    roleFor(u, "r", l5);

    ClearanceRow row = inA(() -> repository().findClearanceRow(u, tenantB));

    assertThat(row.maxRank()).isNull();
    assertThat(row.roleIds()).isEmpty();
  }

  @Test
  @DisplayName("findClearanceRow — 시스템 ADMIN 역할을 가지면 tenantAdmin 이 참이다")
  void findClearanceRow_systemAdmin() {
    fx.provisionDefaults(tenantA);
    long admin = fx.roleIdByName(tenantA, SystemAdminRole.NAME);
    long u = member("adm");
    fx.grantRole(tenantA, u, admin);
    // 이름만 ADMIN 인 비시스템 역할은 ADMIN 이 아니다 — 다른 사용자로 확인한다.
    long fake = member("fake");
    long fakeRole = fx.createRole(tenantB, SystemAdminRole.NAME, null);
    fx.createMembership(fake, tenantB, "ACTIVE");
    fx.grantRole(tenantB, fake, fakeRole);

    ClearanceRow row = inA(() -> repository().findClearanceRow(u, tenantA));
    assertThat(row.tenantAdmin()).isTrue();
    assertThat(row.roleIds()).containsExactly(admin);
    assertThat(row.maxRank()).isNotNull();

    ClearanceRow fakeRow = inB(() -> repository().findClearanceRow(fake, tenantB));
    assertThat(fakeRow.tenantAdmin()).isFalse();
    assertThat(fakeRow.roleIds()).containsExactly(fakeRole);
  }

  @Test
  @DisplayName("findMaxRanksByUser — 사용자별 최대 rank, 자격 없는 사용자는 키가 없고 id 1001 개도 조각을 넘나든다")
  void findMaxRanksByUser() {
    long l3 = level(tenantA, "l3", 3, false, false);
    long l5 = level(tenantA, "l5", 5, false, false);
    long u1 = member("u1");
    roleFor(u1, "r13", l3);
    roleFor(u1, "r15", l5);
    long u2 = member("u2");
    roleFor(u2, "r23", l3);
    long suspended = fx.createUser(prefix + "-susp", null);
    fx.createMembership(suspended, tenantA, "SUSPENDED");
    roleFor(suspended, "rs", l5);
    long onlyB = fx.createUser(prefix + "-onlyb", null);
    fx.createMembership(onlyB, tenantB, "ACTIVE");
    roleFor(onlyB, "rb", l5);

    assertThat(
            inA(
                () ->
                    repository()
                        .findMaxRanksByUser(List.of(u1, u2, suspended, onlyB, -1L), tenantA)))
        .isEqualTo(Map.of(u1, 5, u2, 3));
    assertThat(inA(() -> repository().findMaxRanksByUser(List.of(), tenantA))).isEmpty();
    // 인자 테넌트가 다르면 A 의 역할을 세지 않는다.
    assertThat(inA(() -> repository().findMaxRanksByUser(List.of(u1, u2), tenantB))).isEmpty();

    // Review Focus 3 — 실제 사용자 하나는 첫 조각(500) 밖에 둔다.
    List<Long> ids = new ArrayList<>();
    ids.add(u1);
    for (long i = 1; ids.size() < 700; i++) {
      ids.add(-i);
    }
    ids.add(u2);
    for (long i = 1000; ids.size() < 1001; i++) {
      ids.add(-i);
    }
    assertThat(inA(() -> repository().findMaxRanksByUser(ids, tenantA)))
        .isEqualTo(Map.of(u1, 5, u2, 3));
  }
}
