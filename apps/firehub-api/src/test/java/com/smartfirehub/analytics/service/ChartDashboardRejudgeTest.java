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
  @Autowired private PermitWaitRejudger permitWaitRejudger;

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
        gate,
        permitWaitRejudger);
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
        gate,
        permitWaitRejudger);
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

  /** 위젯 둘(캐시 히트 하나 + 허가를 기다리는 캐시 미스 하나) 대시보드 시나리오의 결과. */
  private record HitMissResult(
      DashboardDataResponse response, long hitChart, long missChart, int acquiresDuringRequest) {}

  /**
   * 두 위젯 대시보드에서 민감 SQL 위젯은 다른(자격 있는) 조회자가 미리 데운 캐시 히트, 공개 SQL 위젯은 캐시 미스(허가 대기)로 만들고, 열람자 요청의 허가 대기
   * 중에 {@code onWait} 를 커밋한다.
   *
   * <p>위젯 처리 순서는 서비스가 저장 쿼리 id 오름차순으로 정한다(WD-689) — {@code hitFirst} 면 id 가 작은 쪽 차트에 민감 SQL(히트)을, 큰
   * 쪽에 공개 SQL(미스)을 둔다. 반대면 그 반대다. HashSet 순회 순서를 흉내 내지 않아 id 값과 무관하게 결정적이다.
   */
  private HitMissResult runHitMissScenario(boolean hitFirst, Runnable onWait) {
    long lowFirst = savedQueryOf(secChart) < savedQueryOf(pubChart) ? secChart : pubChart;
    long highLast = lowFirst == secChart ? pubChart : secChart;
    long hitChart = hitFirst ? lowFirst : highLast;
    long missChart = hitFirst ? highLast : lowFirst;
    setSql(hitChart, "SELECT v FROM " + secTable);
    setSql(missChart, "SELECT v FROM " + pubTable);
    long hitOnly = dashboard(hitChart);
    long board = dashboard(missChart, hitChart);
    AtomicBoolean armed = new AtomicBoolean(false);
    TxRecordingInteractiveGate gate =
        new TxRecordingInteractiveGate(
            () -> {
              if (armed.get()) {
                onWait.run();
              }
            });
    AnalyticsDashboardService service = dashboardService(gate);
    // 다른(자격 있는) 조회자가 민감 위젯 결과를 캐시에 데운다 — 열람자 요청에서 민감 위젯은 허가 없이 캐시 히트다.
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

      return new HitMissResult(d, hitChart, missChart, gate.acquireTx.size() - before);
    } finally {
      awaitSecurityAudit();
      inTenantFixture(() -> dsl.execute("delete from audit_log where user_id = ?", warmer));
      fx.deleteUser(warmer);
      fx.deleteRole(warmerRole);
    }
  }

  /** 미스 위젯은 공개 행을 받고, 민감 캐시 히트 위젯은 denied(행·내보내기 없음)이며 민감 데이터셋 감사 접근이 남지 않았다. */
  private void assertMissServedHitDenied(HitMissResult r) {
    // 미스 위젯만 허가를 얻었고, 그 대기 중에 변경이 커밋됐다.
    assertThat(r.acquiresDuringRequest()).isEqualTo(1);
    assertThat(values(byChart(r.response()).get(r.missChart()))).containsExactly(PUB_VALUE);
    ChartDataResponse hit = byChart(r.response()).get(r.hitChart());
    assertDeniedNoData(hit);
    assertThat(hit.exportAllowed()).isFalse();
    assertThat(secAccessRows()).isZero();
  }

  /**
   * 양성 대조(WD-689) — 뒤 위젯이 허가를 기다렸지만(자격을 다시 읽음) 그동안 바뀐 것이 없으면 앞 캐시 히트 위젯은 마지막 재판정을 통과해 민감 행을 받고, 전달
   * 감사는 정확히 한 번 남는다(재판정 뒤로 미룬 recordDelivered 가 빠지거나 두 번 남지 않는다). 마지막 재판정이 지나치게 거부하지 않는다는 대조이기도 하다.
   */
  @Test
  void dashboard_laterMissWaitsWithoutChange_earlierCacheHitServedAndAuditedOnce() {
    HitMissResult r = runHitMissScenario(true, () -> {});

    assertThat(r.acquiresDuringRequest()).isEqualTo(1);
    assertThat(values(byChart(r.response()).get(r.missChart()))).containsExactly(PUB_VALUE);
    ChartDataResponse hit = byChart(r.response()).get(r.hitChart());
    assertThat(hit.denied()).isFalse();
    assertThat(values(hit)).containsExactly(SEC_VALUE);
    assertThat(secAccessRows()).isEqualTo(1);
  }

  /**
   * 한 대시보드 안에서 앞 위젯(캐시 미스)이 허가를 기다리는 동안 열람자 등급이 낮아지면, 그 뒤에 판정하는 위젯(다른 조회자가 데운 캐시 히트 — 허가 없이 바로
   * 나간다)도 낮아진 자격으로 판정해야 한다. 요청 시작 때 한 번 계산한 자격을 끝까지 쓰면 캐시 히트가 민감 행을 돌려준다.
   */
  @Test
  void dashboard_clearanceLoweredWhileEarlierMissWaits_laterCacheHitAlsoDenied() {
    assertMissServedHitDenied(runHitMissScenario(false, this::lowerViewerRoleToPublic));
  }

  /**
   * WD-689 — 앞 위젯(캐시 히트)은 허가 없이 그 시점 자격으로 통과하지만 응답은 뒤 위젯(캐시 미스)의 허가 대기가 끝난 뒤에 나간다. 그 대기 중에 열람자 등급이
   * 낮아지면 앞 위젯도 낮아진 자격으로 다시 판정해 denied 로 바꿔야 한다 — 그러지 않으면 이제 볼 수 없는 민감 행이 응답에 실려 나가고 전달 감사도 남는다.
   */
  @Test
  void dashboard_clearanceLoweredWhileLaterMissWaits_earlierCacheHitAlsoDenied() {
    assertMissServedHitDenied(runHitMissScenario(true, this::lowerViewerRoleToPublic));
  }

  /**
   * WD-689 — 뒤 위젯의 허가 대기 중 데이터셋 등급이 열람자 자격 위로 올라가면(열람자 자격 값은 그대로) 앞 캐시 히트 위젯도 denied. 자격 값이 바뀌었는지가
   * 아니라 기다림 뒤 다시 읽었는지로 재판정을 정해야 이 경우를 놓치지 않는다.
   */
  @Test
  void dashboard_datasetLevelRaisedWhileLaterMissWaits_earlierCacheHitAlsoDenied() {
    assertMissServedHitDenied(runHitMissScenario(true, this::raiseSecDatasetToSecret));
  }

  /**
   * WD-689 리뷰 — 뒤 위젯(B, 캐시 미스)의 허가 대기가 재판정 없이 429 로 끝나도 그 대기 중 바뀐 자격을 반영해야 한다. 429 는 wait-ms 를 실제로
   * 기다린 끝이라 그동안 등급이 낮아졌을 수 있다. 처리 순서(저장 쿼리 id 오름차순): A 민감 캐시 히트 → B 공개 미스(게이트가 차 있어 기다리다 429, 대기 중
   * 등급 하향) → C 민감 캐시 히트(빨리 끝내기 분기) → D 공개 캐시 히트. 429 뒤 자격을 다시 읽지 않던 구현은 A·C 의 민감 행을 응답에 싣고 전달 감사도
   * 남긴다. D(공개)가 살아남아 응답은 200 이다 — 거부가 429 로 뭉개지지 않고 위젯별로 보인다.
   */
  @Test
  void dashboard_laterMissTimesOutWhileClearanceLowered_cacheHitsDenied() {
    String m = "rj" + System.nanoTime();
    long a = secChart;
    long b = pubChart;
    long c = chart(m + "_secq2", "SELECT v FROM " + secTable + " WHERE v IS NOT NULL");
    long dPub = chart(m + "_pubq2", "SELECT v FROM " + pubTable + " WHERE v IS NOT NULL");
    // 처리 순서 전제 — 차트를 만든 순서대로 저장 쿼리 id 가 오른다.
    assertThat(List.of(savedQueryOf(a), savedQueryOf(b), savedQueryOf(c), savedQueryOf(dPub)))
        .isSorted();
    long warmBoard = dashboard(a, c, dPub);
    long board = dashboard(a, b, c, dPub);
    AtomicBoolean armed = new AtomicBoolean(false);
    // 전체 상한 1·대기 100ms — 미리 허가 하나를 쥐고 있으면 B 는 100ms 기다린 뒤 429 다. 훅은 기다리기 직전에 등급 하향을 커밋한다.
    TxRecordingInteractiveGate gate =
        new TxRecordingInteractiveGate(
            1,
            1,
            100,
            () -> {
              if (armed.get()) {
                lowerViewerRoleToPublic();
              }
            });
    AnalyticsDashboardService service = dashboardService(gate);
    long warmer = fx.createUser("rj_w");
    long warmerRole =
        fx.createRole("rj_wr_" + System.nanoTime(), fx.levelId("민감"), "analytics:read");
    try {
      fx.removeUserRole(warmer);
      fx.assignRole(warmer, warmerRole);
      // 다른(자격 있는) 조회자가 A·C·D 를 캐시에 데운다 — 열람자 요청에서는 B 만 미스다.
      assertThat(values(byChart(service.getDashboardData(warmBoard, warmer)).get(a)))
          .containsExactly(SEC_VALUE);
      DashboardDataResponse d;
      try (var held = gate.acquire(owner)) {
        armed.set(true);
        d = service.getDashboardData(board, viewer);
      }

      Map<Long, ChartDataResponse> w = byChart(d);
      assertThat(w.get(b).queryResult().error()).isNotNull();
      assertThat(values(w.get(b))).isEmpty();
      assertDeniedNoData(w.get(a));
      assertDeniedNoData(w.get(c));
      assertThat(w.get(dPub).denied()).isFalse();
      assertThat(values(w.get(dPub))).containsExactly(PUB_VALUE);
      assertThat(secAccessRows()).isZero();
    } finally {
      awaitSecurityAudit();
      inTenantFixture(() -> dsl.execute("delete from audit_log where user_id = ?", warmer));
      fx.deleteUser(warmer);
      fx.deleteRole(warmerRole);
    }
  }
}
