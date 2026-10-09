package com.smartfirehub.securitylevel.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.ai.AiCall;
import com.smartfirehub.securitylevel.ai.PolicyBlockedException;
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

  /** user(...) 가 만든 사용자 → 그 사용자의 자격 역할 id(역할 허용 목록 부여를 결정적으로 하기 위함). */
  private final java.util.Map<Long, Long> ownRole = new java.util.HashMap<>();

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
    ownRole.put(uid, rid);
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
    // 앵커: 관리자 탐지가 깨지면 SQL·Policy 가 똑같이 거부해 일치 매트릭스가 통과해 버린다 — 탐지 자체를 직접 단언한다.
    assertThat(clearanceResolver.resolve(admin).tenantAdmin()).isTrue();
    assertThat(clearanceResolver.resolve(plain).tenantAdmin()).isFalse();
    long ungrantedSecret = -1;
    List<Long> ids = new ArrayList<>();
    for (String lv : List.of("공개", "내부", "민감", "기밀")) {
      for (int grantMode = 0; grantMode < 3; grantMode++) {
        long ds = dataset(lv);
        ids.add(ds);
        if (lv.equals("기밀") && grantMode == 0) ungrantedSecret = ds;
        for (long subject : subjects) {
          if (grantMode == 1) fx.grantUser(ds, subject);
          if (grantMode == 2) fx.grantRole(ds, ownRole.get(subject));
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
        // 앵커: 허용 목록에 없는 기밀 데이터셋 — ADMIN 은 우회 켜짐일 때만, 그 외는 항상 안 보인다.
        boolean expected = uid == admin && bypass;
        assertThat(guard.check(c, ungrantedSecret, DatasetAction.VIEW, null).allowed())
            .as("anchor policy user=%d bypass=%s", uid, bypass)
            .isEqualTo(expected);
        assertThat(sqlVisible.contains(ungrantedSecret))
            .as("anchor sql user=%d bypass=%s", uid, bypass)
            .isEqualTo(expected);
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

  /**
   * S3 §4.3: AI 문맥 SQL 조각(ai_policy·share_policy 술어 포함)이 호스팅 2종 × share 여부 전 조합에서 가드 판정(AI
   * [+SHARE])과 같은 답을 낸다. 기밀은 허용 목록에 올려 VIEW 를 통과시켜야 share 술어가 실제로 검증된다.
   */
  @Test
  void aiVisibleCondition_agreesWithPolicy_acrossHostingAndShare() {
    long uid = user("기밀", false);
    Clearance c = clearanceResolver.resolve(uid);
    List<Long> ids = new ArrayList<>();
    for (String lv : List.of("공개", "내부", "민감", "기밀")) {
      ids.add(dataset(lv));
    }
    long sens = ids.get(2);
    long secret = ids.get(3);
    fx.grantUser(secret, uid);
    for (ProviderHosting h : ProviderHosting.values()) {
      for (boolean share : List.of(false, true)) {
        AiCall call = new AiCall(h, share);
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
                                field(name("dataset", "security_level_id"), Long.class),
                                call))
                        .fetch(field(name("dataset", "id"), Long.class)));
        for (long ds : ids) {
          boolean policy =
              guard.check(c, ds, DatasetAction.AI, h).allowed()
                  && (!share || guard.check(c, ds, DatasetAction.SHARE, null).allowed());
          assertThat(sqlVisible.contains(ds))
              .as("ds=%d hosting=%s share=%s", ds, h, share)
              .isEqualTo(policy);
        }
      }
    }
    // 앵커(시드 정책): VIEW 는 전부 통과, 외부 호스팅에서 민감은 AI 불가, 자체 호스팅이면 가능, 기밀은 SHARE 불가.
    for (long ds : ids) {
      assertThat(guard.check(c, ds, DatasetAction.VIEW, null).allowed()).isTrue();
    }
    assertThat(guard.check(c, sens, DatasetAction.AI, ProviderHosting.EXTERNAL).allowed())
        .isFalse();
    assertThat(guard.check(c, sens, DatasetAction.AI, ProviderHosting.SELF_HOSTED).allowed())
        .isTrue();
    assertThat(guard.check(c, secret, DatasetAction.SHARE, null).reasonCode())
        .isEqualTo("SHARE_DENIED");
    // 목록(관례 이름)·문자열 SQL 렌더링도 같은 규칙: 외부 호스팅이면 공개·내부만.
    List<Long> viaPublic =
        inTenantFixture(
            () ->
                dsl.select(field(name("dataset", "id"), Long.class))
                    .from(table(name("dataset")))
                    .where(field(name("dataset", "id"), Long.class).in(ids))
                    .and(
                        guard.visibleCondition(
                            c,
                            field(name("dataset", "id"), Long.class),
                            field(name("dataset", "security_level_id"), Long.class),
                            new AiCall(ProviderHosting.EXTERNAL, false)))
                    .fetch(field(name("dataset", "id"), Long.class)));
    assertThat(viaPublic).containsExactlyInAnyOrder(ids.get(0), ids.get(1));
    String frag =
        dsl.renderInlined(
            guard.visibleCondition(
                c,
                field(name("d", "id"), Long.class),
                field(name("d", "security_level_id"), Long.class),
                new AiCall(ProviderHosting.SELF_HOSTED, false)));
    List<Long> viaSql =
        inTenantFixture(
            () ->
                dsl.fetch(
                        "select d.id from dataset d where d.id in (?, ?, ?, ?) and " + frag,
                        ids.toArray())
                    .getValues(0, Long.class));
    assertThat(viaSql).containsExactlyInAnyOrderElementsOf(ids);
  }

  /**
   * S3 §4.3: 볼 수 없는 데이터셋은 기존과 같은 404(존재 은닉), 볼 수 있지만 AI 불허면 403 POLICY_BLOCKED + 등급 이름. 단건 입력도 운영
   * 경로({@link DatasetAccessGuard#requireViewThenAiForDatasets})로 검증한다.
   */
  @Test
  void requireAi_hiddenIs404_visibleButBlockedIs403WithDetails() {
    long low = user("공개", false);
    long ds = dataset("민감");
    Clearance lowC = clearanceResolver.resolve(low);
    assertThatThrownBy(
            () ->
                guard.requireViewThenAiForDatasets(
                    lowC, List.of(ds), new AiCall(ProviderHosting.SELF_HOSTED, false)))
        .isInstanceOf(DatasetNotFoundException.class)
        .hasMessage("Dataset not found: " + ds);
    long high = user("기밀", false);
    Clearance highC = clearanceResolver.resolve(high);
    assertThatThrownBy(
            () ->
                guard.requireViewThenAiForDatasets(
                    highC, List.of(ds), new AiCall(ProviderHosting.EXTERNAL, false)))
        .isInstanceOfSatisfying(
            PolicyBlockedException.class,
            e -> {
              assertThat(e.code()).isEqualTo("POLICY_BLOCKED");
              assertThat(e.status().value()).isEqualTo(403);
              assertThat(e.details())
                  .containsEntry("action", "AI")
                  .containsEntry("levelName", "민감")
                  .containsEntry("policyKey", "ai_policy");
            });
    guard.requireViewThenAiForDatasets(
        highC, List.of(ds), new AiCall(ProviderHosting.SELF_HOSTED, false)); // 통과
  }

  /** S3 §4.3: 목록 강제 — 공유 목적이면 기밀(SHARE DENY)이 POLICY_BLOCKED(SHARE), 숨김 id 는 구분 불가 403. */
  @Test
  void requireAiForDatasets_shareDeniedForSecret_hiddenIsSqlAccessDenied() {
    long high = user("기밀", false);
    Clearance c = clearanceResolver.resolve(high);
    long secret = dataset("기밀");
    fx.grantUser(secret, high);
    assertThatThrownBy(
            () ->
                guard.requireAiForDatasets(
                    c, List.of(secret), new AiCall(ProviderHosting.SELF_HOSTED, true)))
        .isInstanceOfSatisfying(
            PolicyBlockedException.class,
            e ->
                assertThat(e.details())
                    .containsEntry("action", "SHARE")
                    .containsEntry("levelName", "기밀")
                    .containsEntry("policyKey", "share_policy"));
    guard.requireAiForDatasets(c, List.of(secret), new AiCall(ProviderHosting.SELF_HOSTED, false));

    long low = user("공개", false);
    Clearance lowC = clearanceResolver.resolve(low);
    assertThatThrownBy(
            () ->
                guard.requireAiForDatasets(
                    lowC, List.of(secret), new AiCall(ProviderHosting.SELF_HOSTED, false)))
        .isInstanceOfSatisfying(
            CodedApiException.class,
            e -> {
              assertThat(e).isNotInstanceOf(PolicyBlockedException.class);
              assertThat(e.code()).isEqualTo(DatasetAccessGuard.SQL_ACCESS_DENIED_CODE);
            });
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
