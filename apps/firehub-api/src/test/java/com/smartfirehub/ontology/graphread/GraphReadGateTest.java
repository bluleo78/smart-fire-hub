package com.smartfirehub.ontology.graphread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WD-28 GraphReadGate — "온톨로지 출처를 <b>전부</b> VIEW 할 수 있어야 그래프를 읽는다".
 *
 * <p>모든 거부 단언에는 같은 픽스처의 양성 대조(볼 수 있는 사용자는 true)를 붙인다. 그래야 RLS 0행·조인 누락으로 "항상 false"인 구현과 구분된다. 반대로
 * "항상 true"(fail-open)는 거부 단언이 잡는다. 게이트 호출은 픽스처 블록 밖에서 한다 — 게이트(리포지토리)가 스스로 트랜잭션·GUC 를 세우는지가 검증
 * 대상이다.
 */
@AutoConfigureMockMvc
class GraphReadGateTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private GraphReadGate gate;
  @Autowired private ClearanceResolver clearanceResolver;
  @Autowired private MockMvc mockMvc;
  @Autowired private JwtTokenProvider jwt;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private final List<Long> ontologies = new ArrayList<>();
  private Long otherTenant;
  private long creator;
  private long secretId;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    secretId = fx.levelId("기밀");
    creator = fx.createUser("gr_creator");
    users.add(creator);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    fx.setLevelFlags(secretId, true, false); // V133 시드값(허용 목록 필수·ADMIN 우회 꺼짐)으로 원복
    if (otherTenant != null) {
      TenantRlsTestSupport.runInTenantTransaction(
          fixtureTransactionTemplate,
          otherTenant,
          () -> dsl.execute("delete from graph_ontology_source where tenant_id = ?", otherTenant));
      TenantRlsTestSupport.deleteTenants(dsl, otherTenant);
    }
    inTenantFixture(
        () ->
            ontologies.forEach(
                o -> {
                  dsl.execute("delete from graph_ontology_source where ontology_id = ?", o);
                  dsl.execute("delete from ontology where id = ?", o);
                }));
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private long ontology() {
    long id =
        inTenantFixture(
            () ->
                (Long)
                    dsl.fetchValue(
                        "insert into ontology (domain, status) values (?, 'active') returning id",
                        "게이트-" + TenantRlsTestSupport.nextTenantId()));
    ontologies.add(id);
    return id;
  }

  private long dataset(String level) {
    long id = fx.createDatasetRow("gr_" + System.nanoTime(), fx.levelId(level), creator);
    datasets.add(id);
    return id;
  }

  /** 기본 USER 역할을 떼고 지정 등급 자격 + dataset:read 만 가진 사용자. */
  private long userAt(String level) {
    long uid = fx.createUser("gr_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("gr_r_" + System.nanoTime(), fx.levelId(level), "dataset:read");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private void source(long ontologyId, long datasetId) {
    inTenantFixture(
        () ->
            dsl.execute(
                "insert into graph_ontology_source (ontology_id, dataset_id) values (?, ?)",
                ontologyId,
                datasetId));
  }

  private boolean canRead(long uid, long ontologyId) {
    return gate.canRead(clearanceResolver.resolve(uid), ontologyId);
  }

  @Test
  void 출처를_전부_볼_수_있으면_true() {
    long o = ontology();
    source(o, dataset("내부"));
    source(o, dataset("공개"));

    assertThat(canRead(userAt("내부"), o)).isTrue();
  }

  @Test
  void 숨김_출처가_하나라도_있으면_false_이고_볼_수_있는_사용자는_true() {
    long o = ontology();
    source(o, dataset("내부"));
    source(o, dataset("민감"));

    assertThat(canRead(userAt("내부"), o)).as("민감 출처를 못 보는 내부 사용자").isFalse();
    assertThat(canRead(userAt("민감"), o)).as("양성 대조 — 민감 사용자").isTrue();
  }

  @Test
  void 허용_목록에서_빠진_관리자는_false_이고_허용_목록에_넣으면_true() {
    long o = ontology();
    long secret = dataset("기밀"); // V133 시드: 허용 목록 필수 + ADMIN 우회 꺼짐
    source(o, secret);
    long admin = userAt("기밀");
    fx.assignRole(admin, adminRoleId());
    assertThat(clearanceResolver.resolve(admin).tenantAdmin()).as("픽스처 전제: 테넌트 관리자").isTrue();

    assertThat(canRead(admin, o)).as("관리자여도 허용 목록 밖").isFalse();
    fx.grantUser(secret, admin);
    assertThat(canRead(admin, o)).as("양성 대조 — 허용 목록에 넣음").isTrue();
  }

  @Test
  void 삭제된_데이터셋_출처는_판정에서_뺀다() {
    long o = ontology();
    long sensitive = dataset("민감");
    source(o, sensitive);
    long internalUser = userAt("내부");
    assertThat(canRead(internalUser, o)).as("사전 — 삭제 전에는 막힌다").isFalse();

    fx.deleteDatasetRow(sensitive);
    datasets.remove(sensitive);

    assertThat(canRead(internalUser, o)).as("등급이 없어진 출처는 판정 대상이 아니다(알려진 한계)").isTrue();
  }

  @Test
  void 출처가_없으면_true() {
    assertThat(canRead(userAt("공개"), ontology())).isTrue();
  }

  @Test
  void 다른_테넌트의_출처_행은_판정에_영향이_없다() {
    long o = ontology();
    long sensitive = dataset("민감");
    long internalUser = userAt("내부");
    otherTenant = TenantRlsTestSupport.createActiveTenant(dsl, "gr-other");
    // 같은 (온톨로지, 데이터셋)을 다른 테넌트 행으로 심는다 — RLS 가 가리면 판정에 보이지 않아야 한다.
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        otherTenant,
        () ->
            dsl.execute(
                "insert into graph_ontology_source (ontology_id, dataset_id) values (?, ?)",
                o,
                sensitive));

    assertThat(canRead(internalUser, o)).as("남의 테넌트 행").isTrue();
    source(o, sensitive);
    assertThat(canRead(internalUser, o)).as("양성 대조 — 같은 행을 내 테넌트에 심으면 막힌다").isFalse();
  }

  @Test
  void 사용자_신원이_없는_자격은_출처가_없어도_false() {
    assertThat(gate.canRead(Clearance.none(-1L, DEFAULT_TEST_TENANT_ID), ontology())).isFalse();
  }

  /** Review Focus 1: 요청 경로(JWT → ClearanceResolver.current() → 게이트 → 리포지토리 트랜잭션)에서도 같은 판정. */
  @Test
  void 요청_경로에서도_숨김_출처면_graph_access_false_이고_graph_는_403() throws Exception {
    long o = ontology();
    source(o, dataset("민감"));
    String low =
        "Bearer " + jwt.generateAccessToken(userAt("내부"), "gr_low", DEFAULT_TEST_TENANT_ID);
    String high =
        "Bearer " + jwt.generateAccessToken(userAt("민감"), "gr_high", DEFAULT_TEST_TENANT_ID);

    mockMvc
        .perform(get("/api/v1/ontology/" + o + "/graph-access").header("Authorization", low))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.graphReadable").value(false));
    mockMvc
        .perform(get("/api/v1/ontology/" + o + "/graph-access").header("Authorization", high))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.graphReadable").value(true));
    // 시각화 프록시는 ai-agent 를 부르기 전에 막는다(테스트 환경에 ai-agent 가 없어도 403 이 나오는 것이 그 증거).
    mockMvc
        .perform(get("/api/v1/ontology/" + o + "/graph").header("Authorization", low))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("GRAPH_READ_RESTRICTED"))
        .andExpect(jsonPath("$.message").value("이 지식그래프에는 열람 권한이 없는 데이터가 포함되어 있어 표시할 수 없습니다."));
  }

  private long adminRoleId() {
    return inTenantFixture(
        () ->
            (Long)
                dsl.fetchValue(
                    "select id from role where name = 'ADMIN' and tenant_id = ?",
                    DEFAULT_TEST_TENANT_ID));
  }
}
