package com.smartfirehub.analytics.service;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.ROLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.ChartDataResponse;
import com.smartfirehub.analytics.dto.DashboardDataResponse;
import com.smartfirehub.analytics.repository.AnalyticsDashboardRepository;
import com.smartfirehub.analytics.repository.ChartRepository;
import com.smartfirehub.analytics.repository.DashboardWidgetRepository;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.global.config.TransactionTemplateConfig;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TxRecordingInteractiveGate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WD-684 — 차트 데이터·대시보드 일괄 데이터도 화면 쿼리 허가를 얻은 뒤 실행 직전에 새 자격으로 다시 판정해야 한다(데이터셋 /query 의 WD-638·WD-682
 * 와 같은 규칙).
 *
 * <p>허가 전 앞 판정은 통과했는데 허가를 기다리는 동안(최대 wait-ms) 관리자가 열람자 역할의 최대 등급을 낮추거나 데이터셋 등급을 올리면, 앞 판정 토큰을 그대로
 * 실행하던 구현은 이제 볼 수 없는 데이터를 돌려준다. 허가를 얻는 순간을 대기 중 변경 시점으로 흉내 낸다 — 게이트 대역이 acquire 에서 변경을 커밋한다(스레드 없이
 * 결정적으로, DatasetQueryClearanceRefreshTest 와 같은 방식).
 *
 * <p>실제 판정·자격 계산·실행·감사를 쓰고 게이트만 대역이다. 서비스를 테스트마다 새로 만들어 대시보드 결과 캐시도 테스트마다 비어 있다. 민감 테이블에는 실제
 * 행(SEC_VALUE)을 넣어 「행이 없다」 단언이 공허하지 않게 한다.
 */
class ChartDashboardRejudgeTest extends IntegrationTestBase {

  private static final String SEC_VALUE = "rj-sec-row";
  private static final String PUB_VALUE = "rj-pub-row";

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private ChartRepository chartRepository;
  @Autowired private SavedQueryRepository savedQueryRepository;
  @Autowired private GuardedSqlExecutor guardedSqlExecutor;
  @Autowired private ClearanceResolver clearanceResolver;
  @Autowired private AnalyticsDashboardRepository dashboardRepository;
  @Autowired private DashboardWidgetRepository widgetRepository;
  @Autowired private PipelineDataSchema pipelineDataSchema;
  @Autowired private AnalyticsAuditRecorder auditRecorder;

  @Autowired
  @Qualifier(TransactionTemplateConfig.READ_ONLY)
  private TransactionTemplate readOnlyTx;

  private SecurityFixture fx;
  private final List<Long> dashboards = new ArrayList<>();
  private String schema;
  private String secTable;
  private String pubTable;
  private long owner;
  private long viewer;
  private long roleId;
  private long secDatasetId;
  private long pubDatasetId;
  private long secChart;
  private long pubChart;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    owner = fx.createUser("rj_o");
    viewer = fx.createUser("rj_v");
    // 열람자 자격을 이 테스트의 역할 하나(민감)로만 정한다 — 대기 중에 이 역할의 최대 등급을 낮춘다.
    fx.removeUserRole(viewer);
    roleId = fx.createRole("rj_r_" + System.nanoTime(), fx.levelId("민감"), "analytics:read");
    fx.assignRole(viewer, roleId);
    schema = DataSchema.current();
    String m = "rj" + System.nanoTime();
    secTable = m + "_sec";
    pubTable = m + "_pub";
    dsl.execute("CREATE TABLE " + schema + "." + secTable + " (v text)");
    dsl.execute("INSERT INTO " + schema + "." + secTable + " VALUES ('" + SEC_VALUE + "')");
    dsl.execute("CREATE TABLE " + schema + "." + pubTable + " (v text)");
    dsl.execute("INSERT INTO " + schema + "." + pubTable + " VALUES ('" + PUB_VALUE + "')");
    secDatasetId = fx.createDatasetRow(secTable, fx.levelId("민감"), owner);
    pubDatasetId = fx.createDatasetRow(pubTable, fx.levelId("공개"), owner);
    secChart = chart(m + "_secq", "SELECT v FROM " + secTable);
    pubChart = chart(m + "_pubq", "SELECT v FROM " + pubTable);
  }

  @AfterEach
  void tearDown() {
    awaitSecurityAudit();
    inTenantFixture(
        () -> {
          dashboards.forEach(id -> dsl.execute("delete from dashboard where id = ?", id));
          dsl.execute("delete from chart where created_by = ?", owner);
          dsl.execute("delete from saved_query where created_by = ?", owner);
          dsl.execute("delete from audit_log where user_id = ?", viewer);
        });
    fx.deleteDatasetRow(secDatasetId);
    fx.deleteDatasetRow(pubDatasetId);
    fx.deleteUser(viewer);
    fx.deleteUser(owner);
    fx.deleteRole(roleId);
    dsl.execute("DROP TABLE IF EXISTS " + schema + "." + secTable);
    dsl.execute("DROP TABLE IF EXISTS " + schema + "." + pubTable);
  }

  // ---------------------------------------------------------------- 고정물

  /** 공유 저장 쿼리 + 공유 차트. */
  private long chart(String name, String sql) {
    return inTenantFixture(
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

  /** 공유 대시보드 + 위젯. */
  private long dashboard(long... chartIds) {
    return inTenantFixture(
        () -> {
          long id =
              dsl.fetchOne(
                      "insert into dashboard (name, is_shared, created_by) values (?, true, ?)"
                          + " returning id",
                      "rj_d_" + System.nanoTime(),
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

  /** 차트의 저장 쿼리 id. */
  private long savedQueryOf(long chartId) {
    return inTenantFixture(
        () ->
            dsl.fetchOne("select saved_query_id from chart where id = ?", chartId)
                .get(0, Long.class));
  }

  /** 차트의 저장 쿼리 SQL 을 바꾼다. */
  private void setSql(long chartId, String sql) {
    inTenantFixture(
        () ->
            dsl.execute(
                "update saved_query set sql_text = ? where id = (select saved_query_id from chart"
                    + " where id = ?)",
                sql,
                chartId));
  }

  /** 열람자 역할의 최대 등급을 공개로 낮춘다 — 관리자의 등급 하향을 흉내 낸다(즉시 커밋). */
  private void lowerViewerRoleToPublic() {
    long publicLevel = fx.levelId("공개");
    inTenantFixture(
        () ->
            dsl.update(ROLE)
                .set(ROLE.MAX_SECURITY_LEVEL_ID, publicLevel)
                .where(ROLE.ID.eq(roleId))
                .execute());
  }

  /** 민감 데이터셋의 등급을 기밀로 올린다 — 관리자의 데이터셋 등급 상향을 흉내 낸다(즉시 커밋). */
  private void raiseSecDatasetToSecret() {
    long secret = fx.levelId("기밀");
    inTenantFixture(
        () ->
            dsl.update(DATASET)
                .set(DATASET.SECURITY_LEVEL_ID, secret)
                .where(DATASET.ID.eq(secDatasetId))
                .execute());
  }

  /** 게이트만 대역인 실제 차트 서비스. */
  private ChartService chartService(TxRecordingInteractiveGate gate) {
    return new ChartService(
        chartRepository,
        savedQueryRepository,
        guardedSqlExecutor,
        clearanceResolver,
        dashboardRepository,
        pipelineDataSchema,
        readOnlyTx,
        auditRecorder,
        gate);
  }

  /** 게이트만 대역인 실제 대시보드 서비스(새 인스턴스 = 빈 결과 캐시). */
  private AnalyticsDashboardService dashboardService(TxRecordingInteractiveGate gate) {
    return new AnalyticsDashboardService(
        dashboardRepository,
        widgetRepository,
        chartService(gate),
        chartRepository,
        savedQueryRepository,
        clearanceResolver,
        guardedSqlExecutor,
        pipelineDataSchema,
        readOnlyTx,
        auditRecorder,
        gate);
  }

  /** 결과 행에 들어 있는 값 전부(누출 확인용). */
  private static List<Object> values(ChartDataResponse r) {
    List<Object> out = new ArrayList<>();
    r.queryResult().rows().forEach(row -> out.addAll(row.values()));
    return out;
  }

  private static Map<Long, ChartDataResponse> byChart(DashboardDataResponse d) {
    Map<Long, ChartDataResponse> m = new HashMap<>();
    d.widgetData().forEach(w -> m.put(w.chartData().chart().id(), w.chartData()));
    return m;
  }

  /** 열람자의 민감 데이터셋 감사 등급 접근(DATASET_ACCESS) 행 수(비동기 기록이 끝난 뒤) — 0 이면 실행·전달되지 않았다. */
  private int secAccessRows() {
    awaitSecurityAudit();
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE user_id = ? AND action_type ="
                        + " 'DATASET_ACCESS' AND resource_id = ?",
                    viewer,
                    String.valueOf(secDatasetId))
                .get(0, Integer.class));
  }

  /** 거부(denied) 위젯 — 결과는 빈 결과이고 민감 값이 없다. */
  private static void assertDeniedNoData(ChartDataResponse r) {
    assertThat(r.denied()).isTrue();
    assertThat(r.queryResult().rows()).isEmpty();
    assertThat(r.queryResult().error()).isNull();
    assertThat(values(r)).doesNotContain(SEC_VALUE);
  }

  // ---------------------------------------------------------------- 차트

  /** 양성 대조 — 대기 중 변경이 없으면 같은 열람자가 민감 차트의 실제 행을 본다(경로 자체가 고장 나 공허하게 denied 가 되지 않음). */
  @Test
  void chart_noChangeWhileWaiting_returnsRows() {
    TxRecordingInteractiveGate gate = new TxRecordingInteractiveGate();

    ChartDataResponse r = chartService(gate).getChartData(secChart, viewer);

    assertThat(gate.acquireTx).containsExactly(false);
    assertThat(r.denied()).isFalse();
    assertThat(values(r)).containsExactly(SEC_VALUE);
    assertThat(secAccessRows()).isEqualTo(1);
  }

  /**
   * 앞 판정은 통과(민감 자격) → 허가를 기다리는 동안 열람자 역할의 최대 등급을 공개로 낮춤 → 허가 구간의 재판정이 새 자격으로 거부 — 앞 판정 거부와 같은 200
   * denied 이고 실행되지 않는다. 앞 판정 토큰을 그대로 실행하던 구현은 민감 행을 돌려준다.
   */
  @Test
  void chart_clearanceLoweredWhileWaiting_deniedNotExecuted() {
    TxRecordingInteractiveGate gate = new TxRecordingInteractiveGate(this::lowerViewerRoleToPublic);

    ChartDataResponse r = chartService(gate).getChartData(secChart, viewer);

    // 앞 판정은 통과해 허가까지 왔다(앞 판정 거부였다면 acquire 가 불리지 않는다).
    assertThat(gate.acquireTx).containsExactly(false);
    assertDeniedNoData(r);
    assertThat(r.chart().config()).isEmpty();
    assertThat(r.exportAllowed()).isFalse();
    assertThat(secAccessRows()).isZero();
  }

  /** 허가를 기다리는 동안 데이터셋 등급이 열람자 자격 위로 올라감 → 재판정 거부 — 200 denied, 실행 없음. */
  @Test
  void chart_datasetLevelRaisedWhileWaiting_deniedNotExecuted() {
    TxRecordingInteractiveGate gate = new TxRecordingInteractiveGate(this::raiseSecDatasetToSecret);

    ChartDataResponse r = chartService(gate).getChartData(secChart, viewer);

    assertThat(gate.acquireTx).containsExactly(false);
    assertDeniedNoData(r);
    assertThat(secAccessRows()).isZero();
  }

  // ------------------------------------------------------------- 대시보드

  /**
   * 민감 위젯 하나뿐인 대시보드 — 허가를 기다리는 동안 열람자 등급 하향 → 그 위젯만 denied(응답 전체 403 아님), 실행 없음. 거부는 공유 결과 캐시에 남지
   * 않아, 뒤이어 대기 중 변경이 없는 요청에서는 (자격을 되돌린 뒤) 같은 서비스가 실제로 실행해 행을 돌려준다.
   */
  @Test
  void dashboard_clearanceLoweredWhileWaiting_widgetDeniedNotExecuted() {
    long board = dashboard(secChart);
    AtomicBoolean armed = new AtomicBoolean(true);
    TxRecordingInteractiveGate gate =
        new TxRecordingInteractiveGate(
            () -> {
              if (armed.get()) {
                lowerViewerRoleToPublic();
              }
            });
    AnalyticsDashboardService service = dashboardService(gate);

    DashboardDataResponse d = service.getDashboardData(board, viewer);

    assertThat(gate.acquireTx).containsExactly(false);
    ChartDataResponse sec = byChart(d).get(secChart);
    assertDeniedNoData(sec);
    assertThat(sec.exportAllowed()).isFalse();
    assertThat(secAccessRows()).isZero();

    // 자격을 되돌리면 같은 서비스(같은 캐시)가 다시 실행해 실제 행을 돌려준다 — 거부 결과가 캐시에 남지 않았다.
    armed.set(false);
    long sensitive = fx.levelId("민감");
    inTenantFixture(
        () ->
            dsl.update(ROLE)
                .set(ROLE.MAX_SECURITY_LEVEL_ID, sensitive)
                .where(ROLE.ID.eq(roleId))
                .execute());
    ChartDataResponse again = byChart(service.getDashboardData(board, viewer)).get(secChart);
    assertThat(again.denied()).isFalse();
    assertThat(values(again)).containsExactly(SEC_VALUE);
  }

  /** 민감 위젯 하나뿐인 대시보드 — 허가를 기다리는 동안 데이터셋 등급 상향 → 위젯 denied, 실행 없음. */
  @Test
  void dashboard_datasetLevelRaisedWhileWaiting_widgetDeniedNotExecuted() {
    long board = dashboard(secChart);
    TxRecordingInteractiveGate gate = new TxRecordingInteractiveGate(this::raiseSecDatasetToSecret);

    DashboardDataResponse d = dashboardService(gate).getDashboardData(board, viewer);

    assertThat(gate.acquireTx).containsExactly(false);
    assertDeniedNoData(byChart(d).get(secChart));
    assertThat(secAccessRows()).isZero();
  }

  /**
   * 한 대시보드 안에서 앞 위젯(캐시 미스)이 허가를 기다리는 동안 열람자 등급이 낮아지면, 그 뒤에 판정하는 위젯(다른 조회자가 데운 캐시 히트 — 허가 없이 바로
   * 나간다)도 낮아진 자격으로 판정해야 한다. 요청 시작 때 한 번 계산한 자격을 끝까지 쓰면 캐시 히트가 민감 행을 돌려준다.
   *
   * <p>위젯 처리 순서는 서비스가 저장 쿼리 id 를 담는 HashSet 의 순회 순서다 — 같은 내용의 HashSet 으로 순서를 구한 뒤, 먼저 처리되는 쪽에 공개
   * SQL(미스)을, 나중 쪽에 민감 SQL(히트)을 둔다.
   */
  @Test
  void dashboard_clearanceLoweredWhileEarlierMissWaits_laterCacheHitAlsoDenied() {
    long first = savedQueryOf(secChart);
    long second = savedQueryOf(pubChart);
    if (new HashSet<>(List.of(first, second)).iterator().next() != first) {
      long t = first;
      first = second;
      second = t;
    }
    long missChart = savedQueryOf(secChart) == first ? secChart : pubChart;
    long hitChart = missChart == secChart ? pubChart : secChart;
    long hitOnly = dashboard(hitChart);
    long board = dashboard(missChart, hitChart);
    AtomicBoolean armed = new AtomicBoolean(false);
    TxRecordingInteractiveGate gate =
        new TxRecordingInteractiveGate(
            () -> {
              if (armed.get()) {
                lowerViewerRoleToPublic();
              }
            });
    AnalyticsDashboardService service = dashboardService(gate);
    // 먼저 처리되는 위젯은 공개 SQL(캐시 미스 → 허가 대기), 나중 위젯은 민감 SQL.
    setSql(missChart, "SELECT v FROM " + pubTable);
    setSql(hitChart, "SELECT v FROM " + secTable);
    // 다른(자격 있는) 조회자가 민감 위젯 결과를 캐시에 데운다 — 이 요청에서 나중 위젯은 허가 없이 캐시 히트다.
    long warmer = fx.createUser("rj_w");
    long warmerRole =
        fx.createRole("rj_wr_" + System.nanoTime(), fx.levelId("민감"), "analytics:read");
    try {
      fx.removeUserRole(warmer);
      fx.assignRole(warmer, warmerRole);
      assertThat(values(byChart(service.getDashboardData(hitOnly, warmer)).get(hitChart)))
          .containsExactly(SEC_VALUE);
      armed.set(true);
      int before = gate.acquireTx.size();

      DashboardDataResponse d = service.getDashboardData(board, viewer);

      // 앞 위젯(미스)만 허가를 얻었고, 그 대기 중에 등급이 낮아졌다.
      assertThat(gate.acquireTx).hasSize(before + 1);
      assertThat(values(byChart(d).get(missChart))).containsExactly(PUB_VALUE);
      assertDeniedNoData(byChart(d).get(hitChart));
      assertThat(secAccessRows()).isZero();
    } finally {
      awaitSecurityAudit();
      inTenantFixture(() -> dsl.execute("delete from audit_log where user_id = ?", warmer));
      fx.deleteUser(warmer);
      fx.deleteRole(warmerRole);
    }
  }
}
