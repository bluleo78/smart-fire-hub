package com.smartfirehub.securitylevel.repository.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.AiCall;
import com.smartfirehub.securitylevel.repository.jooq.JooqDatasetVisibility;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * MyBatis 가시성 조각({@code DatasetVisibility.visible})이 jOOQ 술어({@link
 * JooqDatasetVisibility#condition(DatasetVisibility)})와 <b>같은 행 집합</b>을 고르는지 고정한다(WD-487 PoC).
 *
 * <p>왜 행 집합 비교인가: 두 렌더러의 SQL 문자열은 다르다. 의미가 같다는 것은 같은 픽스처에서 같은 데이터셋 id 를 고르는 것으로만 확인할 수 있다. 픽스처는
 * DatasetAccessGuardTest 와 같은 모양(등급 4 × 허용 목록 없음/직접/역할 × ADMIN 우회 × ADMIN 여부, AI 호스팅 × share)이다. 각
 * 경우에 앵커 단언을 하나씩 둬서 양쪽이 똑같이 빈 집합을 내며 공허하게 통과하는 일을 막는다.
 */
class MyBatisDatasetVisibilityTest extends IntegrationTestBase {

  /** 비교용 테스트 전용 질의(src/test/resources/mybatis/test/DatasetVisibilityTestMapper.xml). */
  private static final String VISIBLE_IDS =
      "com.smartfirehub.securitylevel.repository.mybatis.DatasetVisibilityTest.visibleIds";

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private ClearanceResolver clearanceResolver;

  @Autowired
  @Qualifier("platformSqlSessionTemplate")
  private SqlSessionTemplate sql;

  private SecurityFixture fx;
  private final List<Long> datasets = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> users = new ArrayList<>();
  private final Map<Long, Long> ownRole = new HashMap<>();
  private long secretId;
  private long creatorId;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    secretId = fx.levelId("기밀");
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

  /** 지정 등급의 자격 역할 하나만 가진 사용자(기본 USER 역할 제거). */
  private long user(String levelName) {
    long uid = fx.createUser("mv");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("mv_role_" + System.nanoTime(), fx.levelId(levelName), "dataset:read");
    roles.add(rid);
    fx.assignRole(uid, rid);
    ownRole.put(uid, rid);
    return uid;
  }

  private long dataset(String levelName) {
    long id = fx.createDatasetRow("mv_ds_" + System.nanoTime(), fx.levelId(levelName), creatorId);
    datasets.add(id);
    return id;
  }

  /** jOOQ 술어로 고른 픽스처 데이터셋 id(공유 DB 라 픽스처 id 로 한정한다). */
  private Set<Long> viaJooq(DatasetVisibility v, List<Long> ids) {
    return inTenantFixture(
        () ->
            new HashSet<>(
                dsl.select(field(name("dataset", "id"), Long.class))
                    .from(table(name("dataset")))
                    .where(field(name("dataset", "id"), Long.class).in(ids))
                    .and(JooqDatasetVisibility.condition(v))
                    .fetch(field(name("dataset", "id"), Long.class))));
  }

  /** MyBatis 조각으로 고른 픽스처 데이터셋 id. */
  private Set<Long> viaMyBatis(DatasetVisibility v, List<Long> ids) {
    Map<String, Object> param = new HashMap<>();
    param.put("vis", MyBatisDatasetVisibility.params(v));
    param.put("ids", ids);
    return inTenantFixture(() -> new HashSet<Long>(sql.selectList(VISIBLE_IDS, param)));
  }

  /** 두 렌더러가 같은 집합을 내는지 단언하고, 앵커 단언용으로 그 집합을 돌려준다. */
  private Set<Long> assertSame(DatasetVisibility v, List<Long> ids, String label) {
    Set<Long> jooq = viaJooq(v, ids);
    Set<Long> mybatis = viaMyBatis(v, ids);
    assertThat(mybatis).as(label).isEqualTo(jooq);
    return mybatis;
  }

  /** 등급 4 × 허용 목록(없음/직접/역할) × ADMIN 우회 × ADMIN 여부 — VIEW 규칙. 제한 없음·NO_RANK 도 같은 픽스처에서 본다. */
  @Test
  void viewMatrix_sameRowsAsJooq() {
    long pub = user("공개");
    long sens = user("민감");
    long plain = user("기밀");
    long admin = user("기밀");
    fx.assignRole(admin, adminRoleId());
    List<Long> subjects = List.of(pub, sens, plain, admin);
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

    // 제한 없음 → 전부, NO_RANK → 없음.
    assertThat(assertSame(DatasetVisibility.UNRESTRICTED, ids, "unrestricted"))
        .containsExactlyInAnyOrderElementsOf(ids);
    assertThat(
            assertSame(
                new DatasetVisibility(Clearance.none(pub, DEFAULT_TEST_TENANT_ID), null),
                ids,
                "no rank"))
        .isEmpty();

    // 자격(rank·역할·ADMIN 여부)은 등급 플래그와 무관하므로 우회 루프 밖에서 한 번만 계산한다.
    Map<Long, Clearance> clearances = new HashMap<>();
    for (long uid : subjects) clearances.put(uid, clearanceResolver.resolve(uid));

    for (boolean bypass : List.of(false, true)) {
      fx.setLevelFlags(secretId, true, bypass);
      for (long uid : subjects) {
        Clearance c = clearances.get(uid);
        Set<Long> visible =
            assertSame(new DatasetVisibility(c, null), ids, "user=" + uid + " bypass=" + bypass);
        // 앵커: 허용 목록에 없는 기밀 — ADMIN 이고 우회 켜짐일 때만 보인다.
        assertThat(visible.contains(ungrantedSecret))
            .as("anchor user=%d bypass=%s", uid, bypass)
            .isEqualTo(uid == admin && bypass);
        // 앵커: 공개 등급은 역할이 있는 누구에게나 보인다(집합이 비지 않음).
        assertThat(visible).contains(ids.get(0));
      }
    }
  }

  /**
   * 역할이 하나도 없는 자격(roleIds 비어 있음) + 직접 부여 — 리졸버가 만든 자격은 늘 역할을 갖기 때문에 손으로 만든다. 역할 IN 절이 빠지는 분기(빈
   * {@code IN ()} 은 PG 문법 오류)를 검증한다.
   */
  @Test
  void emptyRoleIds_directGrantOnly_sameRowsAsJooq() {
    long uid = user("기밀");
    long granted = dataset("기밀");
    long ungranted = dataset("기밀");
    long pubDs = dataset("공개");
    fx.grantUser(granted, uid);
    List<Long> ids = List.of(granted, ungranted, pubDs);
    Clearance base = clearanceResolver.resolve(uid);
    Clearance noRoles =
        new Clearance(uid, DEFAULT_TEST_TENANT_ID, base.rank(), Set.of(), false, Set.of());

    assertThat(assertSame(new DatasetVisibility(noRoles, null), ids, "empty roles"))
        .containsExactlyInAnyOrder(granted, pubDs);
  }

  /**
   * AI 호스팅 2종 × share 여부 — ai_policy·share_policy 술어. 기밀은 허용 목록에 올려 VIEW 를 통과시켜야 share 술어가 검증된다.
   */
  @Test
  void aiHostingAndShare_sameRowsAsJooq() {
    long uid = user("기밀");
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
        Set<Long> visible =
            assertSame(
                new DatasetVisibility(c, new AiCall(h, share)),
                ids,
                "hosting=" + h + " share=" + share);
        // 앵커(시드 정책): 외부 호스팅이면 민감 제외, 자체 호스팅이면 포함 / share 면 기밀 제외.
        assertThat(visible.contains(sens)).isEqualTo(h == ProviderHosting.SELF_HOSTED);
        assertThat(visible.contains(secret)).isEqualTo(!share && h == ProviderHosting.SELF_HOSTED);
        assertThat(visible).contains(ids.get(0));
      }
    }
    // hosting null 은 외부로 취급한다(aiPolicyAllows 규칙).
    assertThat(assertSame(new DatasetVisibility(c, new AiCall(null, false)), ids, "hosting=null"))
        .doesNotContain(sens);
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
