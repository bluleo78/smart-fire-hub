package com.smartfirehub.securitylevel.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 가드의 VIEW 판정과 목록 SQL 조각이 <b>같은 규칙</b>인지 고정한다(Review Focus 1).
 *
 * <p>순수 Policy 는 Task 2 가 매트릭스로 고정했다. 여기서는 DB 에서 사실(등급·허용 목록)을 읽어 Policy 에 넣는 배선과, 그 Policy 와 SQL
 * 조각({@code visibleCondition})이 모든 조합에서 같은 답을 내는지를 본다.
 */
class DatasetAccessGuardTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private DatasetAccessGuard guard;
  @Autowired private ClearanceResolver clearanceResolver;

  private SecurityFixture fx;
  private final List<Long> datasets = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> users = new ArrayList<>();
  private long secretId;
  private long creatorId;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    secretId = fx.levelId("기밀");
    // dataset.created_by 는 "user" FK — 데이터셋 메타 행의 작성자용 사용자.
    creatorId = fx.createUser("creator");
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    fx.setLevelFlags(secretId, true, false); // V133 시드값으로 원복
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
    fx.deleteUser(creatorId);
  }

  private long user(String levelName, boolean keepUserRole) {
    long uid = fx.createUser("g");
    users.add(uid);
    if (!keepUserRole) {
      fx.removeUserRole(uid);
    }
    long rid = fx.createRole("g_role_" + System.nanoTime(), fx.levelId(levelName), "dataset:read");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private long dataset(String levelName) {
    long id = fx.createDatasetRow("g_ds_" + System.nanoTime(), fx.levelId(levelName), creatorId);
    datasets.add(id);
    return id;
  }

  @Test
  void resolve_takesMaxRankAcrossRoles() {
    long uid = user("공개", true); // USER(내부) + 공개 → 최대 = 내부(2)
    Clearance c = clearanceResolver.resolve(uid);
    assertThat(c.rank()).isEqualTo(2);
    assertThat(c.tenantAdmin()).isFalse();
    assertThat(c.roleIds()).hasSize(2);
  }

  @Test
  void resolve_userWithoutRoles_hasNoRank() {
    long uid = fx.createUser("g0");
    users.add(uid);
    fx.removeUserRole(uid);
    assertThat(clearanceResolver.resolve(uid).rank()).isEqualTo(Clearance.NO_RANK);
  }

  @Test
  void requireView_hiddenDataset_throwsSameNotFoundAsMissing() {
    long uid = user("공개", false);
    long sensitive = dataset("민감");
    Clearance c = clearanceResolver.resolve(uid);
    assertThatThrownBy(() -> guard.requireView(c, sensitive))
        .isInstanceOf(DatasetNotFoundException.class)
        .hasMessage("Dataset not found: " + sensitive);
    long missing = 9_000_000_000L;
    assertThatThrownBy(() -> guard.requireView(c, missing))
        .isInstanceOf(DatasetNotFoundException.class)
        .hasMessage("Dataset not found: " + missing);
  }

  @Test
  void allowlist_directUserAndRoleGrant_bothCount() {
    long uid = user("기밀", false);
    long ds1 = dataset("기밀");
    long ds2 = dataset("기밀");
    Clearance c = clearanceResolver.resolve(uid);
    assertThat(guard.check(c, ds1, DatasetAction.VIEW, null).reasonCode())
        .isEqualTo("NOT_ON_ALLOWLIST");
    fx.grantUser(ds1, uid);
    fx.grantRole(ds2, c.roleIds().iterator().next());
    assertThat(guard.check(c, ds1, DatasetAction.VIEW, null).allowed()).isTrue();
    assertThat(guard.check(c, ds2, DatasetAction.VIEW, null).allowed()).isTrue();
  }

  /**
   * Review Focus 1: 등급 4 × 허용 목록(없음/직접/역할) × ADMIN 우회(켜짐/꺼짐) × ADMIN 여부 전 조합에서 SQL 조각 결과 = Policy
   * 결과.
   */
  @Test
  void visibleCondition_agreesWithPolicy_acrossMatrix() {
    // 컨트롤러 판정 C2: 최상위 자격만으로는 rank 비교가 실행되지 않는다 — 공개·민감 자격 사용자도 포함한다.
    long pub = user("공개", false);
    long sens = user("민감", false);
    long plain = user("기밀", false);
    long admin = user("기밀", false);
    fx.assignRole(admin, adminRoleId());
    List<Long> subjects = List.of(pub, sens, plain, admin);
    List<Long> ids = new ArrayList<>();
    for (String lv : List.of("공개", "내부", "민감", "기밀")) {
      for (int grantMode = 0; grantMode < 3; grantMode++) {
        long ds = dataset(lv);
        ids.add(ds);
        for (long subject : subjects) {
          Clearance sc = clearanceResolver.resolve(subject);
          if (grantMode == 1) fx.grantUser(ds, subject);
          if (grantMode == 2) fx.grantRole(ds, sc.roleIds().iterator().next());
        }
      }
    }
    for (boolean bypass : List.of(false, true)) {
      fx.setLevelFlags(secretId, true, bypass);
      for (long uid : subjects) {
        Clearance c = clearanceResolver.resolve(uid);
        List<Long> sqlVisible =
            inTenantFixture(
                () ->
                    dsl.select(field(name("dataset", "id"), Long.class))
                        .from(table(name("dataset")))
                        .where(field(name("dataset", "id"), Long.class).in(ids))
                        .and(
                            guard.visibleCondition(
                                c,
                                field(name("dataset", "id"), Long.class),
                                field(name("dataset", "security_level_id"), Long.class)))
                        .fetch(field(name("dataset", "id"), Long.class)));
        for (long ds : ids) {
          boolean policy = guard.check(c, ds, DatasetAction.VIEW, null).allowed();
          assertThat(sqlVisible.contains(ds))
              .as("dataset=%d user=%d bypass=%s", ds, uid, bypass)
              .isEqualTo(policy);
        }
      }
    }
  }

  @Test
  void visibleSql_rendersSameRuleForRawSqlCallers() {
    long uid = user("공개", false);
    long internal = dataset("내부");
    long pub = dataset("공개");
    Clearance c = clearanceResolver.resolve(uid);
    String frag = guard.visibleSql(c, "d");
    List<Long> visible =
        inTenantFixture(
            () ->
                dsl.fetch(
                        "select d.id from dataset d where d.id in (?, ?) and " + frag,
                        internal,
                        pub)
                    .getValues(0, Long.class));
    assertThat(visible).containsExactly(pub);
  }

  private long adminRoleId() {
    return inTenantFixture(
        () ->
            dsl.select(field(name("role", "id"), Long.class))
                .from(table(name("role")))
                .where(field(name("role", "name"), String.class).eq("ADMIN"))
                .fetchSingle(field(name("role", "id"), Long.class)));
  }
}
