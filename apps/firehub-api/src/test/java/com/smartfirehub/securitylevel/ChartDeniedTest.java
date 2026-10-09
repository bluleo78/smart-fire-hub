package com.smartfirehub.securitylevel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.ChartDataResponse;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.DashboardDataResponse;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.analytics.service.AnalyticsDashboardService;
import com.smartfirehub.analytics.service.ChartService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.util.NormalizedSql;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 스펙 §4.2 4행: 차트·대시보드는 조회자 기준으로 판정하고, 위반 위젯은 denied(페이지 전체 실패 X)로 응답하며, 캐시 히트도 판정을 건너뛰지 않는다.
 *
 * <p>민감 테이블에는 실제 행('sec-row')을 넣는다 — 빈 테이블이면 "행이 없다"는 단언이 누출 여부와 무관하게 통과해 공허해진다(판정 C5).
 */
class ChartDeniedTest extends IntegrationTestBase {

  private static final String SEC_VALUE = "sec-row";
  private static final String PUB_VALUE = "pub-row";

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private ChartService chartService;
  @Autowired private AnalyticsDashboardService dashboardService;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private final List<Long> dashboards = new ArrayList<>();
  private String schema;
  private String secTable;
  private String pubTable;
  private long owner;
  private long secChart;
  private long pubChart;
  private long dashboardId;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    owner = fx.createUser("cd_owner");
    users.add(owner);
    schema = DataSchema.current();
    String m = "cd" + System.nanoTime();
    secTable = m + "_sec";
    pubTable = m + "_pub";
    // 실행까지 확인하므로 물리 테이블과 실제 행을 만든다. SEC_VALUE 가 자격 없는 조회자 결과에 나오면 누출이다.
    dsl.execute("CREATE TABLE " + schema + "." + secTable + " (v text)");
    dsl.execute("INSERT INTO " + schema + "." + secTable + " VALUES ('" + SEC_VALUE + "')");
    dsl.execute("CREATE TABLE " + schema + "." + pubTable + " (v text)");
    dsl.execute("INSERT INTO " + schema + "." + pubTable + " VALUES ('" + PUB_VALUE + "')");
    datasets.add(fx.createDatasetRow(secTable, fx.levelId("민감"), owner));
    datasets.add(fx.createDatasetRow(pubTable, fx.levelId("공개"), owner));
    secChart = chart(m + "_secq", "SELECT v FROM " + secTable);
    pubChart = chart(m + "_pubq", "SELECT v FROM " + pubTable);
    dashboardId = dashboard(m, secChart, pubChart);
  }

  /** 공유 대시보드 + 위젯. 위젯 순서 = 인자 순서. */
  private long dashboard(String name, long... chartIds) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          long id =
              dsl.fetchOne(
                      "insert into dashboard (name, is_shared, created_by) values (?, true, ?)"
                          + " returning id",
                      name,
                      owner)
                  .get(0, Long.class);
          for (long c : chartIds) {
            dsl.execute(
                "insert into dashboard_widget (dashboard_id, chart_id) values (?, ?)", id, c);
          }
          dashboards.add(id);
          return id;
        });
  }

  /** 공유 저장 쿼리 + 공유 차트. 저장 쿼리 id 는 테스트마다 새로 생겨 싱글턴 대시보드 캐시 키가 테스트 간에 겹치지 않는다. */
  private long chart(String name, String sql) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          long q =
              dsl.fetchOne(
                      "insert into saved_query (name, sql_text, is_shared, created_by)"
                          + " values (?, ?, true, ?) returning id",
                      name,
                      sql,
                      owner)
                  .get(0, Long.class);
          return dsl.fetchOne(
                  "insert into chart (name, saved_query_id, chart_type, is_shared, created_by)"
                      + " values (?, ?, 'BAR', true, ?) returning id",
                  name,
                  q,
                  owner)
              .get(0, Long.class);
        });
  }

  /** 기존 역할을 떼고 지정 등급 자격 + analytics:read 만 가진 조회자. */
  private long viewerAt(String level) {
    long uid = fx.createUser("cd_v");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("cd_r_" + System.nanoTime(), fx.levelId(level), "analytics:read");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dashboards.forEach(id -> dsl.execute("delete from dashboard where id = ?", id));
          dsl.execute("delete from chart where created_by = ?", owner);
          reassignedOwners.forEach(u -> dsl.execute("delete from chart where created_by = ?", u));
          dsl.execute("delete from saved_query where created_by = ?", owner);
        });
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
    dsl.execute("DROP TABLE IF EXISTS " + schema + "." + secTable);
    dsl.execute("DROP TABLE IF EXISTS " + schema + "." + pubTable);
  }

  private static Map<Long, ChartDataResponse> byChart(DashboardDataResponse d) {
    Map<Long, ChartDataResponse> m = new HashMap<>();
    d.widgetData().forEach(w -> m.put(w.chartData().chart().id(), w.chartData()));
    return m;
  }

  /** 결과 행에 들어 있는 값 전부(누출 확인용). */
  private static List<Object> values(ChartDataResponse r) {
    List<Object> out = new ArrayList<>();
    r.queryResult().rows().forEach(row -> out.addAll(row.values()));
    return out;
  }

  @Test
  void chartData_deniedForUnclearedViewer_withEmptyResultNotError() {
    ChartDataResponse r = chartService.getChartData(secChart, viewerAt("공개"));
    assertThat(r.denied()).isTrue();
    assertThat(r.queryResult()).isNotNull();
    assertThat(r.queryResult().columns()).isEmpty();
    assertThat(r.queryResult().rows()).isEmpty();
    assertThat(r.queryResult().error()).isNull();

    // 양성 대조: 자격 있는 조회자는 같은 차트에서 실제 행을 본다.
    ChartDataResponse cleared = chartService.getChartData(secChart, viewerAt("민감"));
    assertThat(cleared.denied()).isFalse();
    assertThat(values(cleared)).containsExactly(SEC_VALUE);
  }

  @Test
  void dashboard_marksOnlyViolatingWidgetDenied() {
    DashboardDataResponse d = dashboardService.getDashboardData(dashboardId, viewerAt("공개"));
    assertThat(d.widgetData()).hasSize(2);
    Map<Long, ChartDataResponse> m = byChart(d);
    assertThat(m.get(secChart).denied()).isTrue();
    assertThat(m.get(secChart).queryResult().rows()).isEmpty();
    assertThat(m.get(secChart).queryResult().error()).isNull();
    assertThat(m.get(pubChart).denied()).isFalse();
    assertThat(values(m.get(pubChart))).containsExactly(PUB_VALUE);
  }

  /**
   * Task 3 E1 — denied 페이로드의 chart 는 최소 메타만: config(원본 테이블 컬럼명이 들어 있다)는 빈 맵, savedQueryName 은 null.
   * 단건·대시보드 일괄 두 경로 모두. 양성 대조: 자격 있는 조회자는 config·저장 쿼리 이름을 그대로 받는다.
   */
  @Test
  void deniedPayload_stripsConfigAndSavedQueryName() {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.execute(
                "update chart set config = '{\"xAxis\":\"secret_col\"}'::jsonb where id in (?, ?)",
                secChart,
                pubChart));
    ChartDataResponse single = chartService.getChartData(secChart, viewerAt("공개"));
    assertThat(single.denied()).isTrue();
    assertThat(single.chart().id()).isEqualTo(secChart);
    assertThat(single.chart().config()).isEmpty();
    assertThat(single.chart().savedQueryName()).isNull();

    ChartDataResponse inDashboard =
        byChart(dashboardService.getDashboardData(dashboardId, viewerAt("공개"))).get(secChart);
    assertThat(inDashboard.denied()).isTrue();
    assertThat(inDashboard.chart().config()).isEmpty();
    assertThat(inDashboard.chart().savedQueryName()).isNull();

    ChartDataResponse cleared = chartService.getChartData(secChart, viewerAt("민감"));
    assertThat(cleared.denied()).isFalse();
    assertThat(cleared.chart().config()).containsEntry("xAxis", "secret_col");
    assertThat(cleared.chart().savedQueryName()).isNotNull();
  }

  /** 판단 사항 19 — 파싱 불가 SQL 은 기존처럼 200 + error. 가드 예외가 읽기 트랜잭션을 rollback-only 로 만들어 500 이 나면 안 된다. */
  @Test
  void unparseableChartSql_keepsLegacyErrorResponse() {
    long viewer = viewerAt("공개");
    long broken = chart("cd_broken_" + System.nanoTime(), "SELEC v FRM x");
    ChartDataResponse r = chartService.getChartData(broken, viewer);
    assertThat(r.denied()).isFalse();
    assertThat(r.queryResult().error()).isNotNull();
  }

  /**
   * Review Focus 2 / 판정 C5 — 자격 있는 조회자가 캐시를 데운 직후에도 자격 없는 조회자는 denied, 행이 새지 않는다. 양성 대조(데운 쪽은 실제 행을
   * 봄)가 먼저 성립해야 뒤 단언이 공허하지 않다.
   */
  @Test
  void cacheWarmedByClearedViewer_stillDeniedForUnclearedViewer() {
    DashboardDataResponse warm = dashboardService.getDashboardData(dashboardId, viewerAt("민감"));
    ChartDataResponse warmSec = byChart(warm).get(secChart);
    assertThat(warmSec.denied()).isFalse();
    assertThat(values(warmSec)).containsExactly(SEC_VALUE);

    DashboardDataResponse d = dashboardService.getDashboardData(dashboardId, viewerAt("공개"));
    ChartDataResponse sec = byChart(d).get(secChart);
    assertThat(sec.denied()).isTrue();
    assertThat(sec.queryResult().rows()).isEmpty();
    assertThat(sec.queryResult().columns()).isEmpty();
    assertThat(values(sec)).doesNotContain(SEC_VALUE);
    // 같은 대시보드의 허용 위젯은 캐시든 아니든 정상.
    assertThat(values(byChart(d).get(pubChart))).containsExactly(PUB_VALUE);
  }

  /**
   * 수정 1차(Critical) — 자격 있는 조회자가 캐시를 데운 뒤 소유자가 저장 쿼리 SQL 을 공개 테이블로 바꾸면, 자격 없는 조회자는 새 SQL 로 판정을
   * 통과한다. 캐시 키가 saved_query_id 뿐이면 옛 SQL(민감 테이블)의 결과가 히트로 새어 나온다 — 키에 판정한 SQL 을 넣어야 한다.
   */
  @Test
  void sqlEditedAfterCacheWarm_unclearedViewerGetsNewSqlResult_notStaleRestrictedRows() {
    DashboardDataResponse warm = dashboardService.getDashboardData(dashboardId, viewerAt("민감"));
    assertThat(values(byChart(warm).get(secChart))).containsExactly(SEC_VALUE);

    // 소유자의 SQL 수정을 흉내 낸다(SavedQueryService.update 도 캐시를 비우지 않는다).
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.execute(
                "update saved_query set sql_text = ? where id = (select saved_query_id from chart"
                    + " where id = ?)",
                "SELECT v FROM " + pubTable,
                secChart));

    ChartDataResponse edited =
        byChart(dashboardService.getDashboardData(dashboardId, viewerAt("공개"))).get(secChart);
    assertThat(edited.denied()).isFalse();
    assertThat(values(edited)).doesNotContain(SEC_VALUE);
    // 양성 대조: 새 SQL 의 결과(공개 행)는 정상으로 보인다.
    assertThat(values(edited)).containsExactly(PUB_VALUE);
  }

  /**
   * 원문은 공개 테이블만 참조하지만 정규화본(실행 문자열)은 민감 테이블을 참조하는 SQL(GuardedSqlExecutorTest 와 같은 형태). 원문으로 판정하면 통과
   * 후 실행 관문이 403 을 던져 단건은 403, 대시보드는 전체 실패가 된다 — 판정도 실행 문자열 기준이어야 위젯 하나만 denied 가 된다.
   */
  @Test
  void normalizationDivergentSql_isDeniedPerWidget_notWholeRequestFailure() {
    String leak =
        "SELECT '/*' /* */ || ', (SELECT v FROM "
            + secTable
            + " LIMIT 1) AS y FROM "
            + pubTable
            + " --' AS x FROM "
            + pubTable;
    // 전제: 정규화본이 민감 테이블을 드러낸다(깨지면 이 테스트가 공허해진다).
    assertThat(NormalizedSql.of(leak).text()).contains("FROM " + secTable);
    long leakChart = chart("cd_leak_" + System.nanoTime(), leak);
    long board = dashboard("cd_leakboard_" + System.nanoTime(), leakChart, pubChart);
    long viewer = viewerAt("공개");

    ChartDataResponse single = chartService.getChartData(leakChart, viewer);
    assertThat(single.denied()).isTrue();
    assertThat(single.queryResult().rows()).isEmpty();

    Map<Long, ChartDataResponse> m = byChart(dashboardService.getDashboardData(board, viewer));
    assertThat(m.get(leakChart).denied()).isTrue();
    assertThat(m.get(pubChart).denied()).isFalse();
    assertThat(values(m.get(pubChart))).containsExactly(PUB_VALUE);
  }

  /** 기존 역할을 떼고 지정 등급 자격 + 지정 권한만 가진 조회자(내보내기 플래그 확인용). */
  private long viewerWith(String level, String... perms) {
    long uid = fx.createUser("cd_e");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("cd_re_" + System.nanoTime(), fx.levelId(level), perms);
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  /** Review Focus 2 — 공유 캐시를 다른 조회자가 데워도 exportAllowed 는 조회자별이다(설계 결정 5). */
  @Test
  void exportAllowed_isPerViewer_evenOnCacheHit() {
    long permitted = viewerWith("민감", "analytics:read", "data:export", "data:export_restricted");
    long restricted = viewerWith("민감", "analytics:read", "data:export");
    // 내보내기 권한자가 먼저 캐시를 데운다.
    Map<Long, ChartDataResponse> warm =
        byChart(dashboardService.getDashboardData(dashboardId, permitted));
    assertThat(warm.get(secChart).exportAllowed()).isTrue();
    // 같은 캐시를 읽는 제한 조회자 — 결과는 보지만('민감' VIEW 통과) 내보내기 플래그는 자기 것('민감' PERMISSION, 권한 없음).
    Map<Long, ChartDataResponse> hit =
        byChart(dashboardService.getDashboardData(dashboardId, restricted));
    assertThat(hit.get(secChart).denied()).isFalse();
    assertThat(values(hit.get(secChart))).containsExactly(SEC_VALUE);
    assertThat(hit.get(secChart).exportAllowed()).isFalse();
    assertThat(hit.get(pubChart).exportAllowed()).isTrue();
    // 단건 위젯 경로도 같은 계약.
    assertThat(chartService.getChartData(secChart, restricted).exportAllowed()).isFalse();
    assertThat(chartService.getChartData(secChart, permitted).exportAllowed()).isTrue();
  }

  /** 이 조회자의 '민감' 데이터셋 감사 등급 접근(DATASET_ACCESS) 행 수(비동기 기록이 끝난 뒤). */
  private int secAccessRows(long uid) {
    awaitSecurityAudit();
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE user_id = ? AND action_type ="
                        + " 'DATASET_ACCESS' AND resource_id = ?",
                    uid,
                    String.valueOf(datasets.get(0)))
                .get(0, Integer.class));
  }

  /**
   * code-review 3 — 감사 등급 접근은 결과를 실제로 받은 조회자마다 남는다. 공유 캐시를 데운 조회자(실행)뿐 아니라 캐시 히트로 받은 조회자도 남고, 위젯이
   * denied 인 조회자(실행·전달 없음)는 남지 않는다.
   */
  @Test
  void auditAccess_isRecordedPerViewer_onCacheMissAndHit_butNotForDeniedWidget() {
    long warmer = viewerAt("민감");
    long hitter = viewerAt("민감");
    long denied = viewerAt("공개");
    dashboardService.getDashboardData(dashboardId, warmer);
    Map<Long, ChartDataResponse> hit =
        byChart(dashboardService.getDashboardData(dashboardId, hitter));
    assertThat(values(hit.get(secChart))).containsExactly(SEC_VALUE);
    assertThat(
            byChart(dashboardService.getDashboardData(dashboardId, denied)).get(secChart).denied())
        .isTrue();
    assertThat(secAccessRows(warmer)).isEqualTo(1);
    assertThat(secAccessRows(hitter)).isEqualTo(1);
    assertThat(secAccessRows(denied)).isZero();
  }

  // ------------------------------------------------------------ WD-31② config 가림

  private static final ObjectMapper JSON = new ObjectMapper();
  private final List<Long> reassignedOwners = new ArrayList<>();

  /** WD-31②: 저장 쿼리를 통과하지 못하는 조회자에게는 config(컬럼명)를 주지 않는다 — 단건·목록 모두. 볼 수 있는 차트는 그대로. */
  @Test
  void getAndList_withholdConfigForViewerWhoCannotRunSavedQuery() {
    setConfig(secChart, Map.of("xAxis", "v", "yAxis", List.of("v")));
    setConfig(pubChart, Map.of("xAxis", "v", "yAxis", List.of("v")));
    long viewer = viewerAt("공개");
    ChartResponse one = chartService.getById(secChart, viewer);
    assertThat(one.config()).isNull();
    assertThat(one.configWithheld()).isTrue();
    var page = chartService.list(null, null, null, null, viewer, 0, 200);
    ChartResponse listed =
        page.content().stream().filter(c -> c.id().equals(secChart)).findFirst().orElseThrow();
    assertThat(listed.config()).isNull();
    assertThat(listed.configWithheld()).isTrue();
    // 양성 대조: 볼 수 있는 차트는 단건·목록 모두 config 를 그대로 받는다.
    ChartResponse pub = chartService.getById(pubChart, viewer);
    assertThat(pub.configWithheld()).isFalse();
    assertThat(pub.config()).containsEntry("xAxis", "v");
    ChartResponse listedPub =
        page.content().stream().filter(c -> c.id().equals(pubChart)).findFirst().orElseThrow();
    assertThat(listedPub.configWithheld()).isFalse();
    assertThat(listedPub.config()).containsEntry("xAxis", "v");
  }

  @Test
  void viewerWhoCanRun_seesConfig() {
    setConfig(secChart, Map.of("xAxis", "v", "yAxis", List.of("v")));
    ChartResponse c = chartService.getById(secChart, viewerAt("민감"));
    assertThat(c.configWithheld()).isFalse();
    assertThat(c.config()).containsEntry("xAxis", "v");
  }

  /** 가려진 소유자가 config 없이 저장해도 기존 config 는 유지되고, 응답도 가린다. */
  @Test
  void update_withNullConfig_keepsStoredConfig_andResponseIsWithheld() {
    setConfig(secChart, Map.of("xAxis", "v", "yAxis", List.of("v")));
    long lowOwner = viewerAt("공개");
    reassignOwner(secChart, lowOwner);
    ChartResponse res =
        chartService.update(
            secChart, new UpdateChartRequest("새 이름", null, null, null, null), lowOwner);
    assertThat(res.name()).isEqualTo("새 이름");
    assertThat(res.config()).isNull();
    assertThat(res.configWithheld()).isTrue();
    assertThat(storedConfig(secChart)).containsEntry("xAxis", "v");
  }

  private void setConfig(long chartId, Map<String, Object> config) {
    String json;
    try {
      json = JSON.writeValueAsString(config);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.execute("update chart set config = ?::jsonb where id = ?", json, chartId));
  }

  /** 소유자를 바꾼다 — 자격이 낮은 소유자가 저장하는 경우를 만든다. tearDown 이 이 사용자 소유 차트도 지우도록 기록한다. */
  private void reassignOwner(long chartId, long userId) {
    reassignedOwners.add(userId);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> dsl.execute("update chart set created_by = ? where id = ?", userId, chartId));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> storedConfig(long chartId) {
    String json =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.fetchOne("select config::text from chart where id = ?", chartId)
                    .get(0, String.class));
    try {
      return JSON.readValue(json, Map.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
