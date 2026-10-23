package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.DeleteImpactResponse;
import com.smartfirehub.analytics.exception.ChartNotFoundException;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.StreamSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 저장 쿼리·차트 삭제 영향 범위(WD-86) 통합 테스트.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 테스트 트랜잭션이 GUC 를 공급하면 "서비스가 스스로 트랜잭션을 열지 않아 RLS 가 전 행을
 * 막고 영향 0건(=연결 없음, 일반 확인 창)으로 위장되는" 배선 결함이 가려진다. 픽스처만 {@link #inTenantFixture} 로 심고, 검증 대상 호출은 그 블록
 * 밖에서 한다.
 *
 * <p>WD-199: 워크스페이스 분석 자산 관리(analytics:admin)의 공유 해제·삭제도 여기서 검증한다 — 관리자 삭제가 작성자 삭제와 같은 CASCADE·영향
 * 범위를 갖는지가 이 클래스의 주제와 맞닿아 있고, 권한·직렬화(최소 필드)는 실제 HTTP 응답으로만 확인되므로 MockMvc 를 함께 쓴다(새 통합 테스트 클래스를 늘리지
 * 않으려고 기존 클래스에 붙였다 — {@code @AutoConfigureMockMvc} 컨텍스트는 다른 통합 테스트와 공유된다).
 */
@AutoConfigureMockMvc
class AnalyticsDeleteImpactTest extends IntegrationTestBase {

  @Autowired private SavedQueryService savedQueryService;
  @Autowired private ChartService chartService;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MockMvc mockMvc;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;

  private final List<Long> users = new ArrayList<>();
  // WD-199 HTTP 검증용 — 멤버십·역할까지 갖춘 사용자(SecurityFixture)와 그 역할
  private final List<Long> members = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private SecurityFixture fx;
  private Long owner;
  private Long other;
  private Long otherTenant;

  @BeforeEach
  void setUp() {
    owner = TenantRlsTestSupport.insertUser(dsl, "wd86_owner");
    other = TenantRlsTestSupport.insertUser(dsl, "wd86_other");
    users.add(owner);
    users.add(other);
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // 대시보드를 지우면 위젯이, 쿼리를 지우면 차트(와 그 위젯)가 CASCADE 로 함께 사라진다.
    inTenantFixture(
        () -> {
          for (Long u : concat(users, members)) {
            dsl.execute("DELETE FROM dashboard WHERE created_by = ?", u);
            dsl.execute("DELETE FROM saved_query WHERE created_by = ?", u);
          }
        });
    if (otherTenant != null) {
      // 다른 워크스페이스에 심은 저장 쿼리(WD-199 경계 검증) — 테넌트를 지우기 전에 그 테넌트 컨텍스트로 지운다
      inTenantFixture(
          otherTenant,
          () -> {
            for (Long u : concat(users, members)) {
              dsl.execute("DELETE FROM saved_query WHERE created_by = ?", u);
            }
          });
    }
    users.forEach(u -> TenantRlsTestSupport.deleteUser(dsl, u));
    members.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
    if (otherTenant != null) {
      TenantRlsTestSupport.deleteTenants(dsl, otherTenant);
    }
  }

  // ── 픽스처(기본 테넌트, 트랜잭션 안) ─────────────────────────────────────────

  private Long query(String name, Long by, boolean shared) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "INSERT INTO saved_query (name, sql_text, is_shared, created_by)"
                        + " VALUES (?, 'SELECT 1', ?, ?) RETURNING id",
                    name,
                    shared,
                    by)
                .get(0, Long.class));
  }

  private Long chart(String name, Long queryId, Long by, boolean shared) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "INSERT INTO chart (name, saved_query_id, chart_type, is_shared, created_by)"
                        + " VALUES (?, ?, 'BAR', ?, ?) RETURNING id",
                    name,
                    queryId,
                    shared,
                    by)
                .get(0, Long.class));
  }

  private Long dashboard(String name, Long by, boolean shared) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "INSERT INTO dashboard (name, is_shared, created_by) VALUES (?, ?, ?) RETURNING id",
                    name,
                    shared,
                    by)
                .get(0, Long.class));
  }

  private void widget(Long dashboardId, Long chartId) {
    inTenantFixture(
        () ->
            dsl.execute(
                "INSERT INTO dashboard_widget (dashboard_id, chart_id) VALUES (?, ?)",
                dashboardId,
                chartId));
  }

  private static List<String> names(List<DeleteImpactResponse.ItemRef> refs) {
    return refs.stream().map(DeleteImpactResponse.ItemRef::name).toList();
  }

  // ── 영향 범위 정확성 ─────────────────────────────────────────────────────

  @Test
  void queryImpact_countsChartsWidgetsAndDistinctDashboards() {
    Long q = query("월별 매출", owner, false);
    Long c1 = chart("지역별 매출", q, owner, false);
    Long c2 = chart("월 추이", q, owner, true);
    Long d1 = dashboard("영업 현황", owner, false);
    Long d2 = dashboard("경영 요약", owner, true);
    widget(d1, c1);
    widget(d1, c2);
    widget(d2, c1);
    // 소음: 다른 쿼리의 차트가 같은 대시보드에 있어도 세지 않아야 한다(saved_query_id 필터).
    Long qOther = query("다른 쿼리", owner, false);
    Long cNoise = chart("무관 차트", qOther, owner, false);
    widget(d1, cNoise);

    DeleteImpactResponse impact = savedQueryService.getDeleteImpact(q, owner);

    // 표시 순서는 DB 콜레이션을 따르므로 순서 무관으로 단언한다.
    assertThat(names(impact.charts())).containsExactlyInAnyOrder("월 추이", "지역별 매출");
    assertThat(impact.hiddenChartCount()).isZero();
    assertThat(impact.widgetCount()).isEqualTo(3);
    // d1 에 위젯이 2개 있어도 대시보드는 한 곳으로 센다.
    assertThat(names(impact.dashboards())).containsExactlyInAnyOrder("경영 요약", "영업 현황");
    assertThat(impact.hiddenDashboardCount()).isZero();
  }

  @Test
  void chartImpact_countsWidgetsAndDashboards_withoutCharts() {
    Long q = query("월별 매출", owner, false);
    Long c1 = chart("지역별 매출", q, owner, false);
    Long c2 = chart("월 추이", q, owner, false);
    Long d1 = dashboard("영업 현황", owner, false);
    Long d2 = dashboard("경영 요약", owner, false);
    widget(d1, c1);
    widget(d2, c1);
    widget(d1, c2);

    DeleteImpactResponse impact = chartService.getDeleteImpact(c1, owner);

    assertThat(impact.charts()).isEmpty();
    assertThat(impact.hiddenChartCount()).isZero();
    assertThat(impact.widgetCount()).isEqualTo(2);
    assertThat(names(impact.dashboards())).containsExactlyInAnyOrder("경영 요약", "영업 현황");
    assertThat(impact.hiddenDashboardCount()).isZero();
  }

  @Test
  void noLinks_returnsEmptyImpact() {
    Long q = query("홀로 쿼리", owner, false);
    Long c = chart("홀로 차트", query("다른 쿼리", owner, false), owner, false);

    DeleteImpactResponse queryImpact = savedQueryService.getDeleteImpact(q, owner);
    DeleteImpactResponse chartImpact = chartService.getDeleteImpact(c, owner);

    for (DeleteImpactResponse impact : List.of(queryImpact, chartImpact)) {
      assertThat(impact.charts()).isEmpty();
      assertThat(impact.hiddenChartCount()).isZero();
      assertThat(impact.widgetCount()).isZero();
      assertThat(impact.dashboards()).isEmpty();
      assertThat(impact.hiddenDashboardCount()).isZero();
    }
  }

  // ── 가시성: 볼 수 없는 항목은 이름 없이 개수만 ─────────────────────────────────

  @Test
  void queryImpact_hidesNamesOfItemsViewerCannotSee_butCountsThem() throws Exception {
    // 공유 쿼리에 다른 사용자가 차트를 만들었다 — CASCADE 는 그것까지 지운다.
    Long q = query("공유 쿼리", owner, true);
    Long privateChart = chart("남의 비공개 차트", q, other, false);
    Long sharedChart = chart("남의 공유 차트", q, other, true);
    Long privateDash = dashboard("남의 비공개 대시보드", other, false);
    Long sharedDash = dashboard("남의 공유 대시보드", other, true);
    widget(privateDash, privateChart);
    widget(sharedDash, sharedChart);

    DeleteImpactResponse impact = savedQueryService.getDeleteImpact(q, owner);

    assertThat(names(impact.charts())).containsExactly("남의 공유 차트");
    assertThat(impact.hiddenChartCount()).isEqualTo(1);
    assertThat(impact.widgetCount()).isEqualTo(2);
    assertThat(names(impact.dashboards())).containsExactly("남의 공유 대시보드");
    assertThat(impact.hiddenDashboardCount()).isEqualTo(1);
    // 응답 직렬화에도 볼 수 없는 이름·id 가 실리지 않는다.
    String json = objectMapper.writeValueAsString(impact);
    assertThat(json).doesNotContain("남의 비공개 차트", "남의 비공개 대시보드");
    // id 도 싣지 않는다 — 차트·대시보드 id 는 테이블마다 따로 매겨져 겹칠 수 있어 목록별로 본다.
    assertThat(impact.charts())
        .extracting(DeleteImpactResponse.ItemRef::id)
        .containsExactly(sharedChart);
    assertThat(impact.dashboards())
        .extracting(DeleteImpactResponse.ItemRef::id)
        .containsExactly(sharedDash);
  }

  @Test
  void chartImpact_hidesDashboardNameViewerCannotSee() {
    Long q = query("내 쿼리", owner, false);
    Long c = chart("내 공유 차트", q, owner, true);
    // 다른 사용자가 내 공유 차트를 자기 비공개 대시보드에 올렸다.
    Long privateDash = dashboard("남의 비공개 대시보드", other, false);
    widget(privateDash, c);

    DeleteImpactResponse impact = chartService.getDeleteImpact(c, owner);

    assertThat(impact.widgetCount()).isEqualTo(1);
    assertThat(impact.dashboards()).isEmpty();
    assertThat(impact.hiddenDashboardCount()).isEqualTo(1);
  }

  // ── 관문: 삭제와 같은 소유자 전용·같은 404 ────────────────────────────────────

  @Test
  void nonOwner_andMissingId_getSameNotFound() {
    Long q = query("공유 쿼리", owner, true);
    Long c = chart("공유 차트", q, owner, true);

    // 공유돼 있어 볼 수는 있어도 지울 수 없는 사람에게는 영향 범위를 주지 않는다(삭제와 같은 404).
    assertThatThrownBy(() -> savedQueryService.getDeleteImpact(q, other))
        .isInstanceOf(SavedQueryNotFoundException.class);
    assertThatThrownBy(() -> chartService.getDeleteImpact(c, other))
        .isInstanceOf(ChartNotFoundException.class);
    assertThatThrownBy(() -> savedQueryService.getDeleteImpact(Long.MAX_VALUE, owner))
        .isInstanceOf(SavedQueryNotFoundException.class);
    assertThatThrownBy(() -> chartService.getDeleteImpact(Long.MAX_VALUE, owner))
        .isInstanceOf(ChartNotFoundException.class);
  }

  @Test
  void otherTenantContext_cannotSeeImpact() {
    Long q = query("A 테넌트 쿼리", owner, false);
    Long c = chart("A 테넌트 차트", q, owner, false);
    widget(dashboard("A 테넌트 대시보드", owner, false), c);
    otherTenant = TenantRlsTestSupport.createActiveTenant(dsl, "wd86");

    // 소유자 id 그대로라도 다른 테넌트 컨텍스트에서는 RLS 가 행을 가려 404 다(소유자 판정만으로는 통과했을 것).
    TenantContext.runScoped(
        otherTenant,
        () -> {
          assertThatThrownBy(() -> savedQueryService.getDeleteImpact(q, owner))
              .isInstanceOf(SavedQueryNotFoundException.class);
          assertThatThrownBy(() -> chartService.getDeleteImpact(c, owner))
              .isInstanceOf(ChartNotFoundException.class);
        });
  }

  // ── WD-199: 워크스페이스 분석 자산 관리(analytics:admin) ─────────────────────────────

  private static final String ADMIN = "/api/v1/analytics/admin/";

  /** 관리자 목록 행이 가져야 하는 필드 — 이보다 많아지면(내용 필드 추가) 테스트가 깨져야 한다. */
  private static final Set<String> ADMIN_ITEM_FIELDS =
      Set.of("id", "name", "ownerId", "ownerName", "ownerActive", "isShared", "updatedAt");

  private static <T> List<T> concat(List<T> a, List<T> b) {
    List<T> all = new ArrayList<>(a);
    all.addAll(b);
    return all;
  }

  /** 테넌트 1 ACTIVE 멤버 + 지정 권한만 가진 역할(기본 USER 역할은 뗀다). */
  private long member(String... perms) {
    long uid = fx.createUser("wd199_u");
    members.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("wd199_r_" + System.nanoTime(), fx.levelId("공개"), perms);
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  /** 실제 ADMIN 처럼 분석 읽기·쓰기도 함께 가진 관리자 — 쓰기가 있어도 남의 비공개 내용은 못 봐야 한다. */
  private long admin() {
    return member("analytics:read", "analytics:write", "analytics:admin");
  }

  private String token(long uid) {
    return "Bearer " + jwt.generateAccessToken(uid, "wd199", DEFAULT_TEST_TENANT_ID);
  }

  private MockHttpServletResponse send(MockHttpServletRequestBuilder req, long uid, String body)
      throws Exception {
    req.header("Authorization", token(uid));
    if (body != null) {
      req.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mockMvc.perform(req).andReturn().getResponse();
  }

  private int status(MockHttpServletRequestBuilder req, long uid) throws Exception {
    return send(req, uid, null).getStatus();
  }

  /** 관리자 목록(해당 종류)에서 id 행 — 없으면 null. */
  private JsonNode adminRow(String type, long adminId, long id) throws Exception {
    MockHttpServletResponse res = send(get(ADMIN + type), adminId, null);
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
    JsonNode list = objectMapper.readTree(res.getContentAsString());
    return StreamSupport.stream(list.spliterator(), false)
        .filter(n -> n.path("id").asLong() == id)
        .findFirst()
        .orElse(null);
  }

  private static Set<String> fieldNames(JsonNode node) {
    Set<String> keys = new TreeSet<>();
    node.fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  /** 관리자 행위 감사 행 수 — action·resource·resourceId·행위자로 좁힌다. */
  private int auditRows(String action, String resource, long resourceId, long actor) {
    return inTenantFixture(
        () ->
            dsl.fetchCount(
                table(name("audit_log")),
                field(name("action_type"), String.class)
                    .eq(action)
                    .and(field(name("resource"), String.class).eq(resource))
                    .and(field(name("resource_id"), String.class).eq(String.valueOf(resourceId)))
                    .and(field(name("user_id"), Long.class).eq(actor))));
  }

  private boolean exists(String tableName, long id) {
    return inTenantFixture(() -> TenantRlsTestSupport.rowExists(dsl, tableName, "id", id));
  }

  private Boolean isShared(String tableName, long id) {
    return inTenantFixture(
        () ->
            dsl.select(field(name("is_shared"), Boolean.class))
                .from(table(name(tableName)))
                .where(field(name("id"), Long.class).eq(id))
                .fetchOne(field(name("is_shared"), Boolean.class)));
  }

  /** 내용(SQL·설명)에 표식을 심은 저장 쿼리 — 관리자 응답에 표식이 실리면 내용이 샌 것이다. */
  private Long secretQuery(String name, Long by, boolean shared) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "INSERT INTO saved_query (name, description, sql_text, is_shared, created_by)"
                        + " VALUES (?, 'WD199_SECRET_DESC', 'SELECT ''WD199_SECRET_SQL''', ?, ?)"
                        + " RETURNING id",
                    name,
                    shared,
                    by)
                .get(0, Long.class));
  }

  @Test
  void admin_listsWorkspaceItems_withMinimalFieldsOnly_andOwnerStatus() throws Exception {
    long u = member("analytics:read", "analytics:write");
    long admin = admin();
    Long privateQuery = secretQuery("U 비공개 쿼리", u, false);
    Long sharedQuery = secretQuery("U 공유 쿼리", u, true);
    Long c =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "INSERT INTO chart (name, description, saved_query_id, chart_type, config,"
                            + " is_shared, created_by) VALUES ('U 차트', 'WD199_SECRET_DESC', ?,"
                            + " 'BAR', '{\"xAxis\":\"WD199_SECRET_COL\"}'::jsonb, true, ?)"
                            + " RETURNING id",
                        sharedQuery,
                        u)
                    .get(0, Long.class));
    Long d =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "INSERT INTO dashboard (name, description, is_shared, created_by)"
                            + " VALUES ('U 대시보드', 'WD199_SECRET_DESC', true, ?) RETURNING id",
                        u)
                    .get(0, Long.class));
    widget(d, c);

    for (String type : List.of("queries", "charts", "dashboards")) {
      String body = send(get(ADMIN + type), admin, null).getContentAsString();
      // 비공개 자산도 목록에 오르지만 내용(SQL·설명·설정·위젯)은 어느 종류에도 실리지 않는다
      assertThat(body).doesNotContain("WD199_SECRET");
    }
    JsonNode priv = adminRow("queries", admin, privateQuery);
    assertThat(priv).as("비공개 쿼리도 관리자 목록에 보인다").isNotNull();
    assertThat(fieldNames(priv)).isEqualTo(new TreeSet<>(ADMIN_ITEM_FIELDS));
    assertThat(priv.path("name").asText()).isEqualTo("U 비공개 쿼리");
    assertThat(priv.path("ownerId").asLong()).isEqualTo(u);
    assertThat(priv.path("isShared").asBoolean()).isFalse();
    assertThat(priv.path("ownerActive").asBoolean()).isTrue();
    assertThat(adminRow("queries", admin, sharedQuery).path("isShared").asBoolean()).isTrue();
    assertThat(fieldNames(adminRow("charts", admin, c)))
        .isEqualTo(new TreeSet<>(ADMIN_ITEM_FIELDS));
    assertThat(fieldNames(adminRow("dashboards", admin, d)))
        .isEqualTo(new TreeSet<>(ADMIN_ITEM_FIELDS));

    // 작성자가 정지되면 「활성 멤버 아님」으로 보인다 — 정리 대상을 고르는 근거
    dsl.execute(
        "update membership set status = 'SUSPENDED' where user_id = ? and tenant_id = ?",
        u,
        DEFAULT_TEST_TENANT_ID);
    assertThat(adminRow("queries", admin, privateQuery).path("ownerActive").asBoolean()).isFalse();
    assertThat(adminRow("dashboards", admin, d).path("ownerActive").asBoolean()).isFalse();
  }

  @Test
  void admin_unshare_hidesFromOthers_andWritesAudit() throws Exception {
    long u = member("analytics:read", "analytics:write");
    long viewer = member("analytics:read");
    long admin = admin();
    Long q = query("U 공유 쿼리", u, true);
    Long c = chart("U 공유 차트", q, u, true);
    Long d = dashboard("U 공유 대시보드", u, true);
    widget(d, c);
    assertThat(status(get("/api/v1/analytics/charts/" + c), viewer)).isEqualTo(200);

    assertThat(status(post(ADMIN + "charts/" + c + "/unshare"), admin)).isEqualTo(204);

    // 대상 차트만 비공개 — 대시보드·쿼리는 그대로(WD-194 공유 연쇄의 역방향은 하지 않는다)
    assertThat(isShared("chart", c)).isFalse();
    assertThat(isShared("dashboard", d)).isTrue();
    assertThat(isShared("saved_query", q)).isTrue();
    assertThat(status(get("/api/v1/analytics/charts/" + c), viewer)).isEqualTo(404);
    assertThat(status(get("/api/v1/analytics/charts/" + c + "/data"), viewer)).isEqualTo(404);
    assertThat(auditRows("ANALYTICS_CHART_ADMIN_UNSHARE", "chart", c, admin)).isEqualTo(1);

    // 대시보드·쿼리도 같은 경로로 공유 해제된다. 이미 비공개여도 성공(멱등)이며 감사는 남는다
    assertThat(status(post(ADMIN + "dashboards/" + d + "/unshare"), admin)).isEqualTo(204);
    assertThat(status(get("/api/v1/analytics/dashboards/" + d), viewer)).isEqualTo(404);
    assertThat(status(post(ADMIN + "queries/" + q + "/unshare"), admin)).isEqualTo(204);
    assertThat(status(get("/api/v1/analytics/queries/" + q), viewer)).isEqualTo(404);
    assertThat(status(post(ADMIN + "queries/" + q + "/unshare"), admin)).isEqualTo(204);
    assertThat(auditRows("ANALYTICS_DASHBOARD_ADMIN_UNSHARE", "dashboard", d, admin)).isEqualTo(1);
    assertThat(auditRows("ANALYTICS_QUERY_ADMIN_UNSHARE", "saved_query", q, admin)).isEqualTo(2);
    // 작성자 본인은 여전히 자기 자산을 본다
    assertThat(status(get("/api/v1/analytics/charts/" + c), u)).isEqualTo(200);
  }

  @Test
  void admin_delete_cascadesLikeOwnerDelete_andWritesAudit() throws Exception {
    long u = member("analytics:read", "analytics:write");
    long admin = admin();
    // U 의 공유 쿼리로 다른 사용자가 비공개 차트를 만들어 자기 대시보드에 올렸다 — 작성자 삭제도 그것까지 CASCADE 로 지운다
    Long q = query("U 공유 쿼리", u, true);
    Long ownChart = chart("U 차트", q, u, true);
    Long othersChart = chart("남의 비공개 차트", q, other, false);
    Long othersDash = dashboard("남의 비공개 대시보드", other, false);
    widget(othersDash, othersChart);
    Long uDash = dashboard("U 대시보드", u, true);
    widget(uDash, ownChart);

    // 삭제 영향: 개수는 전부, 이름은 관리자에게 보이는 것(공유된 것)만 — 관리 권한이 남의 비공개 이름을 열지 않는다
    MockHttpServletResponse impactRes =
        send(get(ADMIN + "queries/" + q + "/delete-impact"), admin, null);
    assertThat(impactRes.getStatus()).isEqualTo(200);
    JsonNode impact = objectMapper.readTree(impactRes.getContentAsString());
    assertThat(impact.path("charts").size()).isEqualTo(1);
    assertThat(impact.path("hiddenChartCount").asInt()).isEqualTo(1);
    assertThat(impact.path("widgetCount").asInt()).isEqualTo(2);
    assertThat(impact.path("hiddenDashboardCount").asInt()).isEqualTo(1);
    assertThat(impactRes.getContentAsString()).doesNotContain("남의 비공개");

    // 대시보드 삭제 — 위젯만 함께 사라지고 차트는 남는다
    assertThat(status(delete(ADMIN + "dashboards/" + uDash), admin)).isEqualTo(204);
    assertThat(exists("dashboard", uDash)).isFalse();
    assertThat(exists("chart", ownChart)).isTrue();
    assertThat(auditRows("ANALYTICS_DASHBOARD_ADMIN_DELETE", "dashboard", uDash, admin))
        .isEqualTo(1);

    // 쿼리 삭제 — 작성자 삭제와 같은 CASCADE: 그 쿼리의 모든 차트(남의 것 포함)와 그 위젯
    assertThat(status(delete(ADMIN + "queries/" + q), admin)).isEqualTo(204);
    assertThat(exists("saved_query", q)).isFalse();
    assertThat(exists("chart", ownChart)).isFalse();
    assertThat(exists("chart", othersChart)).isFalse();
    assertThat(exists("dashboard", othersDash)).as("대시보드 자체는 남는다").isTrue();
    assertThat(
            (int)
                inTenantFixture(
                    () ->
                        dsl.fetchCount(
                            table(name("dashboard_widget")),
                            field(name("dashboard_id"), Long.class).eq(othersDash))))
        .isZero();
    assertThat(auditRows("ANALYTICS_QUERY_ADMIN_DELETE", "saved_query", q, admin)).isEqualTo(1);

    // 지워진 것을 다시 지우면 작성자 경로와 같은 404
    assertThat(status(delete(ADMIN + "queries/" + q), admin)).isEqualTo(404);
    // 차트 삭제도 같은 경로 — 차트만 지워지고 쿼리는 남는다
    Long q2 = query("U 쿼리2", u, false);
    Long c2 = chart("U 차트2", q2, u, false);
    assertThat(status(delete(ADMIN + "charts/" + c2), admin)).isEqualTo(204);
    assertThat(exists("chart", c2)).isFalse();
    assertThat(exists("saved_query", q2)).isTrue();
    assertThat(auditRows("ANALYTICS_CHART_ADMIN_DELETE", "chart", c2, admin)).isEqualTo(1);
  }

  @Test
  void admin_cannotReadEditOrRunPrivateContent_viaOwnerEndpoints() throws Exception {
    long u = member("analytics:read", "analytics:write");
    long admin = admin();
    Long q = secretQuery("U 비공개 쿼리", u, false);
    Long c = chart("U 비공개 차트", q, u, false);
    Long d = dashboard("U 비공개 대시보드", u, false);

    // 관리 권한은 작성자 경로의 가시성을 넓히지 않는다 — 열람·실행·편집·작성자 삭제 모두 그대로 404
    assertThat(status(get("/api/v1/analytics/queries/" + q), admin)).isEqualTo(404);
    assertThat(status(post("/api/v1/analytics/queries/" + q + "/execute"), admin)).isEqualTo(404);
    assertThat(status(get("/api/v1/analytics/charts/" + c), admin)).isEqualTo(404);
    assertThat(status(get("/api/v1/analytics/charts/" + c + "/data"), admin)).isEqualTo(404);
    assertThat(status(get("/api/v1/analytics/dashboards/" + d), admin)).isEqualTo(404);
    assertThat(send(put("/api/v1/analytics/queries/" + q), admin, "{\"name\":\"고침\"}").getStatus())
        .isEqualTo(404);
    assertThat(status(delete("/api/v1/analytics/queries/" + q), admin)).isEqualTo(404);
    assertThat(exists("saved_query", q)).isTrue();
  }

  @Test
  void nonAdmin_isForbidden_onEveryAdminEndpoint() throws Exception {
    long u = member("analytics:read", "analytics:write");
    Long q = query("U 공유 쿼리", u, true);
    Long c = chart("U 공유 차트", q, u, true);
    Long d = dashboard("U 공유 대시보드", u, true);

    // 분석 읽기·쓰기만 가진 일반 멤버(작성자 본인 포함)는 관리 API 전부 403
    for (long caller : List.of(member("analytics:read", "analytics:write"), u)) {
      for (String type : List.of("dashboards", "charts", "queries")) {
        assertThat(status(get(ADMIN + type), caller)).isEqualTo(403);
      }
      assertThat(status(post(ADMIN + "dashboards/" + d + "/unshare"), caller)).isEqualTo(403);
      assertThat(status(post(ADMIN + "charts/" + c + "/unshare"), caller)).isEqualTo(403);
      assertThat(status(post(ADMIN + "queries/" + q + "/unshare"), caller)).isEqualTo(403);
      assertThat(status(delete(ADMIN + "dashboards/" + d), caller)).isEqualTo(403);
      assertThat(status(delete(ADMIN + "charts/" + c), caller)).isEqualTo(403);
      assertThat(status(delete(ADMIN + "queries/" + q), caller)).isEqualTo(403);
      assertThat(status(get(ADMIN + "charts/" + c + "/delete-impact"), caller)).isEqualTo(403);
      assertThat(status(get(ADMIN + "queries/" + q + "/delete-impact"), caller)).isEqualTo(403);
    }
    assertThat(isShared("chart", c)).isTrue();
    assertThat(exists("dashboard", d)).isTrue();
  }

  @Test
  void admin_otherWorkspaceItem_isNotFound_andUntouched() throws Exception {
    long admin = admin();
    otherTenant = TenantRlsTestSupport.createActiveTenant(dsl, "wd199");
    Long foreign =
        inTenantFixture(
            otherTenant,
            () ->
                dsl.fetchOne(
                        "INSERT INTO saved_query (name, sql_text, is_shared, created_by)"
                            + " VALUES ('다른 워크스페이스 쿼리', 'SELECT 1', true, ?) RETURNING id",
                        owner)
                    .get(0, Long.class));

    assertThat(adminRow("queries", admin, foreign)).isNull();
    assertThat(status(post(ADMIN + "queries/" + foreign + "/unshare"), admin)).isEqualTo(404);
    assertThat(status(delete(ADMIN + "queries/" + foreign), admin)).isEqualTo(404);
    assertThat(status(get(ADMIN + "queries/" + foreign + "/delete-impact"), admin)).isEqualTo(404);
    Boolean stillShared =
        inTenantFixture(
            otherTenant,
            () ->
                dsl.select(field(name("is_shared"), Boolean.class))
                    .from(table(name("saved_query")))
                    .where(field(name("id"), Long.class).eq(foreign))
                    .fetchOne(field(name("is_shared"), Boolean.class)));
    assertThat(stillShared).isTrue();
    assertThat(auditRows("ANALYTICS_QUERY_ADMIN_DELETE", "saved_query", foreign, admin)).isZero();
    // 종류 경로는 정해진 셋만 매핑된다
    assertThat(status(get(ADMIN + "datasets"), admin)).isEqualTo(404);
  }
}
