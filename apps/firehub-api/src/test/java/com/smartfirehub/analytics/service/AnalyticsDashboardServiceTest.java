package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.analytics.dto.AddWidgetRequest;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.DashboardShareImpactResponse;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.analytics.exception.DashboardNotFoundException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AnalyticsDashboardServiceTest extends IntegrationTestBase {

  @Autowired private AnalyticsDashboardService dashboardService;
  @Autowired private ChartService chartService;
  @Autowired private SavedQueryService savedQueryService;
  @Autowired private DSLContext dsl;

  private Long ownerUserId;
  private Long otherUserId;

  @BeforeEach
  void setUp() {
    ownerUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "dashboard_owner")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Dashboard Owner")
            .set(DSL.field(DSL.name("user", "email"), String.class), "dashboard_owner@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    otherUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "dashboard_viewer")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Dashboard Viewer")
            .set(DSL.field(DSL.name("user", "email"), String.class), "dashboard_viewer@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));
  }

  private Long createSavedQuery() {
    var savedQuery =
        savedQueryService.create(
            new CreateSavedQueryRequest("Test Query", null, "SELECT 1", null, "test", false),
            ownerUserId);
    return savedQuery.id();
  }

  @Test
  void shareDashboard_shouldAutoShareContainedCharts() {
    // Given: a saved query, private charts, and a private dashboard with widgets
    Long savedQueryId = createSavedQuery();

    var chart1 =
        chartService.create(
            new CreateChartRequest("Chart 1", null, savedQueryId, "BAR", Map.of(), false),
            ownerUserId);
    var chart2 =
        chartService.create(
            new CreateChartRequest("Chart 2", null, savedQueryId, "LINE", Map.of(), false),
            ownerUserId);

    assertThat(chart1.isShared()).isFalse();
    assertThat(chart2.isShared()).isFalse();

    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("Test Dashboard", "desc", false, null), ownerUserId);

    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(chart1.id(), 0, 0, 6, 4), ownerUserId);
    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(chart2.id(), 6, 0, 6, 4), ownerUserId);

    // When: share the dashboard
    dashboardService.update(
        dashboard.id(),
        new UpdateDashboardRequest("Test Dashboard", "desc", true, null, null),
        ownerUserId);

    // Then: contained charts should be auto-shared
    var updatedChart1 = chartService.getById(chart1.id(), ownerUserId);
    var updatedChart2 = chartService.getById(chart2.id(), ownerUserId);
    assertThat(updatedChart1.isShared()).isTrue();
    assertThat(updatedChart2.isShared()).isTrue();

    // And: saved query should also be auto-shared
    var sharedQuery = savedQueryService.getById(savedQueryId, otherUserId);
    assertThat(sharedQuery).isNotNull();
    assertThat(sharedQuery.isShared()).isTrue();

    // And: other user can now see the dashboard and charts
    DashboardResponse otherView = dashboardService.getById(dashboard.id(), otherUserId);
    assertThat(otherView).isNotNull();
    assertThat(otherView.name()).isEqualTo("Test Dashboard");

    var otherChart1 = chartService.getById(chart1.id(), otherUserId);
    assertThat(otherChart1).isNotNull();
  }

  @Test
  void unshareDashboard_shouldNotUnshareCharts() {
    // Given: a shared dashboard with shared charts
    Long savedQueryId = createSavedQuery();

    var chart =
        chartService.create(
            new CreateChartRequest("Shared Chart", null, savedQueryId, "BAR", Map.of(), false),
            ownerUserId);

    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("Shared Dashboard", null, true, null), ownerUserId);

    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(chart.id(), 0, 0, 6, 4), ownerUserId);

    // Share dashboard (auto-shares chart)
    dashboardService.update(
        dashboard.id(), new UpdateDashboardRequest(null, null, true, null, null), ownerUserId);
    assertThat(chartService.getById(chart.id(), ownerUserId).isShared()).isTrue();

    // When: unshare the dashboard
    dashboardService.update(
        dashboard.id(), new UpdateDashboardRequest(null, null, false, null, null), ownerUserId);

    // Then: chart should still be shared (not auto-unshared)
    var stillSharedChart = chartService.getById(chart.id(), ownerUserId);
    assertThat(stillSharedChart.isShared()).isTrue();
  }

  /** 설명을 빈 문자열로 보내면 지워지고(NULL), 생략(null)하면 기존 설명이 남는다(WD-197). */
  @Test
  void updateDashboard_blankDescriptionClears_nullKeeps() {
    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("Desc Dashboard", "옛 설명", false, null), ownerUserId);

    // 생략(null) → 변경 없음
    dashboardService.update(
        dashboard.id(), new UpdateDashboardRequest("이름만 바꿈", null, null, null, null), ownerUserId);
    assertThat(dashboardService.getById(dashboard.id(), ownerUserId).description())
        .isEqualTo("옛 설명");

    // 빈 문자열(공백 포함) → NULL 로 지움
    DashboardResponse cleared =
        dashboardService.update(
            dashboard.id(), new UpdateDashboardRequest(null, "  ", null, null, null), ownerUserId);
    assertThat(cleared.description()).isNull();
    assertThat(dashboardService.getById(dashboard.id(), ownerUserId).description()).isNull();
  }

  @Test
  void updateDashboard_withClearAutoRefreshFlag_setsColumnToNull() {
    // Given: 자동 새로고침이 30초로 설정된 대시보드
    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("Refresh Dashboard", null, false, 30), ownerUserId);
    assertThat(dashboard.autoRefreshSeconds()).isEqualTo(30);

    // When: autoRefreshSeconds=null + clearAutoRefresh=true로 "수동"으로 되돌리기 요청 (#568)
    DashboardResponse updated =
        dashboardService.update(
            dashboard.id(),
            new UpdateDashboardRequest("Refresh Dashboard", null, false, null, true),
            ownerUserId);

    // Then: 이전 값이 남지 않고 실제로 null(수동)로 초기화되어야 한다
    assertThat(updated.autoRefreshSeconds()).isNull();
    DashboardResponse reloaded = dashboardService.getById(dashboard.id(), ownerUserId);
    assertThat(reloaded.autoRefreshSeconds()).isNull();
  }

  @Test
  void updateDashboard_withNullAutoRefreshAndNoClearFlag_keepsExistingValue() {
    // Given: 자동 새로고침이 30초로 설정된 대시보드
    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("Refresh Dashboard 2", null, false, 30), ownerUserId);

    // When: autoRefreshSeconds만 null이고 clearAutoRefresh 플래그가 없는 부분 업데이트
    // (다른 필드만 바꾸는 기존 partial-update 경로와 동일 — 값이 "미제공"으로 해석돼야 한다)
    dashboardService.update(
        dashboard.id(), new UpdateDashboardRequest(null, null, true, null, null), ownerUserId);

    // Then: autoRefreshSeconds는 그대로 유지되어야 한다
    DashboardResponse reloaded = dashboardService.getById(dashboard.id(), ownerUserId);
    assertThat(reloaded.autoRefreshSeconds()).isEqualTo(30);
  }

  // ── WD-194: 대시보드 공유는 요청자 본인 차트·쿼리만 함께 공유한다 ──────────────────────────────
  // 플래그는 서비스 조회(가시성 필터가 걸림)가 아니라 DB 원본으로 확인한다 — 필터가 결과를 가리지 않게.

  private boolean chartSharedInDb(Long chartId) {
    return dsl.select(DSL.field(DSL.name("chart", "is_shared"), Boolean.class))
        .from(DSL.table(DSL.name("chart")))
        .where(DSL.field(DSL.name("chart", "id"), Long.class).eq(chartId))
        .fetchOne(DSL.field(DSL.name("chart", "is_shared"), Boolean.class));
  }

  private boolean querySharedInDb(Long queryId) {
    return dsl.select(DSL.field(DSL.name("saved_query", "is_shared"), Boolean.class))
        .from(DSL.table(DSL.name("saved_query")))
        .where(DSL.field(DSL.name("saved_query", "id"), Long.class).eq(queryId))
        .fetchOne(DSL.field(DSL.name("saved_query", "is_shared"), Boolean.class));
  }

  private Long createQueryAs(Long userId, String name, boolean shared) {
    return savedQueryService
        .create(new CreateSavedQueryRequest(name, null, "SELECT 1", null, "test", shared), userId)
        .id();
  }

  private Long createChartAs(Long userId, String name, Long queryId, boolean shared) {
    return chartService
        .create(new CreateChartRequest(name, null, queryId, "BAR", Map.of(), shared), userId)
        .id();
  }

  @Test
  void shareDashboard_sharesOnlyOwnResources_notOthersChartOrPrivateQuery() {
    // Given: 대시보드 주인(owner)의 비공개 차트·쿼리 + 다른 사용자(other)의 공유 차트(그 쿼리는 비공개)
    Long myQuery = createQueryAs(ownerUserId, "내 쿼리", false);
    Long myChart = createChartAs(ownerUserId, "내 차트", myQuery, false);
    Long othersQuery = createQueryAs(otherUserId, "남의 비공개 쿼리", false);
    Long othersChart = createChartAs(otherUserId, "남의 공유 차트", othersQuery, true);

    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("섞인 대시보드", null, false, null), ownerUserId);
    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(myChart, 0, 0, 6, 4), ownerUserId);
    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(othersChart, 6, 0, 6, 4), ownerUserId);

    // When: owner 가 대시보드를 공유
    dashboardService.update(
        dashboard.id(), new UpdateDashboardRequest(null, null, true, null, null), ownerUserId);

    // Then: 내 차트·쿼리는 공유되고(의도된 동작 유지), 남의 비공개 쿼리는 그대로 비공개다
    assertThat(chartSharedInDb(myChart)).isTrue();
    assertThat(querySharedInDb(myQuery)).isTrue();
    assertThat(querySharedInDb(othersQuery)).isFalse();
    assertThat(chartSharedInDb(othersChart)).isTrue(); // 주인이 공유한 값 그대로(바뀌지 않음)
  }

  @Test
  void reSaveSharedDashboard_doesNotReShareResourcesOwnerMadePrivateAgain() {
    // Given: other 의 공유 차트 + 공유 쿼리를 owner 가 자기 대시보드에 넣고 공유해 둔 상태
    Long othersQuery = createQueryAs(otherUserId, "남의 쿼리", true);
    Long othersChart = createChartAs(otherUserId, "남의 차트", othersQuery, true);
    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("공유 대시보드", null, false, null), ownerUserId);
    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(othersChart, 0, 0, 6, 4), ownerUserId);
    dashboardService.update(
        dashboard.id(), new UpdateDashboardRequest(null, null, true, null, null), ownerUserId);

    // other 가 자기 차트·쿼리를 다시 비공개로 되돌린다
    chartService.update(
        othersChart, new UpdateChartRequest(null, null, null, null, false), otherUserId);
    savedQueryService.update(
        othersQuery, new UpdateSavedQueryRequest(null, null, null, null, null, false), otherUserId);
    assertThat(chartSharedInDb(othersChart)).isFalse();
    assertThat(querySharedInDb(othersQuery)).isFalse();

    // When: owner 가 이름만 바꿔 다시 저장(웹 설정 창은 isShared 를 늘 함께 보낸다)
    dashboardService.update(
        dashboard.id(), new UpdateDashboardRequest("이름만 바꿈", null, true, null, null), ownerUserId);

    // Then: other 가 되돌린 비공개가 유지된다
    assertThat(chartSharedInDb(othersChart)).isFalse();
    assertThat(querySharedInDb(othersQuery)).isFalse();
  }

  @Test
  void shareDashboard_ownChartOnOthersQuery_sharesChartButNotOthersQuery() {
    // Given: other 가 공유했던 쿼리로 owner 가 차트를 만들고, other 가 그 쿼리를 비공개로 되돌림
    Long othersQuery = createQueryAs(otherUserId, "남의 쿼리(나중에 비공개)", true);
    Long myChart = createChartAs(ownerUserId, "남의 쿼리를 쓰는 내 차트", othersQuery, false);
    savedQueryService.update(
        othersQuery, new UpdateSavedQueryRequest(null, null, null, null, null, false), otherUserId);

    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("내 차트만 있는 대시보드", null, false, null), ownerUserId);
    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(myChart, 0, 0, 6, 4), ownerUserId);

    // When
    dashboardService.update(
        dashboard.id(), new UpdateDashboardRequest(null, null, true, null, null), ownerUserId);

    // Then: 내 차트는 공유되지만, 쿼리 주인이 아닌 owner 가 남의 쿼리를 공유로 바꾸지는 못한다
    assertThat(chartSharedInDb(myChart)).isTrue();
    assertThat(querySharedInDb(othersQuery)).isFalse();
  }

  // ── WD-325: 공유 스위치 안내용 영향 개수 ─────────────────────

  @Test
  void shareImpact_countsOnlyOwnResources_beforeAndAfterSharing() {
    // Given: 내 비공개 차트 2개(같은 내 비공개 쿼리) + 내가 따로 공개한 차트 1개(공개 쿼리) + 남의 공유 차트(남의 비공개 쿼리)
    Long myQuery = createQueryAs(ownerUserId, "내 쿼리", false);
    Long myChartA = createChartAs(ownerUserId, "내 차트 A", myQuery, false);
    Long myChartB = createChartAs(ownerUserId, "내 차트 B", myQuery, false);
    Long mySharedQuery = createQueryAs(ownerUserId, "내 공개 쿼리", true);
    Long mySharedChart = createChartAs(ownerUserId, "내 공개 차트", mySharedQuery, true);
    Long othersQuery = createQueryAs(otherUserId, "남의 쿼리", false);
    Long othersChart = createChartAs(otherUserId, "남의 차트", othersQuery, true);

    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("영향 대시보드", null, false, null), ownerUserId);
    List<Long> widgetCharts = List.of(myChartA, myChartB, mySharedChart, othersChart);
    for (int i = 0; i < widgetCharts.size(); i++) {
      dashboardService.addWidget(
          dashboard.id(), new AddWidgetRequest(widgetCharts.get(i), 0, i * 2, 2, 2), ownerUserId);
    }

    // When/Then: 켜기 전 — 새로 공개될 내 차트 2·쿼리 1(두 차트가 같은 쿼리), 이미 공개인 내 차트 1·쿼리 1(남의 것은 세지 않음)
    assertThat(dashboardService.getShareImpact(dashboard.id(), ownerUserId))
        .isEqualTo(new DashboardShareImpactResponse(2, 1, 1, 1));

    // 공유를 켠 뒤에는 모두 공개 — 끄면 계속 공개로 남는 내 차트 3·쿼리 2
    dashboardService.update(
        dashboard.id(), new UpdateDashboardRequest(null, null, true, null, null), ownerUserId);
    assertThat(dashboardService.getShareImpact(dashboard.id(), ownerUserId))
        .isEqualTo(new DashboardShareImpactResponse(0, 0, 3, 2));
  }

  @Test
  void shareImpact_notOwner_throwsNotFound() {
    // 공유 스위치는 소유자 설정 창에만 있다 — 남에게 주인의 비공개 차트 수를 드러내지 않는다
    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("남의 대시보드", null, true, null), ownerUserId);
    assertThatThrownBy(() -> dashboardService.getShareImpact(dashboard.id(), otherUserId))
        .isInstanceOf(DashboardNotFoundException.class);
  }

  // ── WD-195: 공유 대시보드 위젯 추가 시 공유 + 볼 수 없는 차트 위젯은 이름을 가린다 ─────────────────────

  private Long createUser(String username) {
    return dsl.insertInto(DSL.table(DSL.name("user")))
        .set(DSL.field(DSL.name("user", "username"), String.class), username)
        .set(DSL.field(DSL.name("user", "password"), String.class), "password")
        .set(DSL.field(DSL.name("user", "name"), String.class), username)
        .set(DSL.field(DSL.name("user", "email"), String.class), username + "@example.com")
        .returning(DSL.field(DSL.name("user", "id"), Long.class))
        .fetchOne()
        .get(DSL.field(DSL.name("user", "id"), Long.class));
  }

  private DashboardResponse.DashboardWidgetResponse widgetOf(DashboardResponse d, Long chartId) {
    return d.widgets().stream().filter(w -> w.chartId().equals(chartId)).findFirst().orElseThrow();
  }

  @Test
  void addWidget_toSharedDashboard_sharesOwnPrivateChartAndQuery_visibleToOthers() {
    // Given: 이미 공유된 대시보드 + owner 의 비공개 쿼리·차트(F3 재현)
    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("이미 공유된 대시보드", null, true, null), ownerUserId);
    Long myQuery = createQueryAs(ownerUserId, "공유 후 추가 쿼리", false);
    Long myChart = createChartAs(ownerUserId, "공유 후 추가 차트", myQuery, false);

    // When: 설정 창 재저장 없이 위젯만 추가
    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(myChart, 0, 0, 6, 4), ownerUserId);

    // Then: 차트·쿼리가 공유로 바뀌고, 다른 사람 화면에서 이름·데이터가 정상으로 보인다
    assertThat(chartSharedInDb(myChart)).isTrue();
    assertThat(querySharedInDb(myQuery)).isTrue();
    DashboardResponse viewerView = dashboardService.getById(dashboard.id(), otherUserId);
    DashboardResponse.DashboardWidgetResponse w = widgetOf(viewerView, myChart);
    assertThat(w.accessible()).isTrue();
    assertThat(w.chartName()).isEqualTo("공유 후 추가 차트");
    assertThat(dashboardService.getDashboardData(dashboard.id(), otherUserId).widgetData())
        .extracting(d -> d.widgetId())
        .containsExactly(w.id());
  }

  @Test
  void addWidget_toPrivateDashboard_keepsChartPrivate() {
    // 공유하지 않은 대시보드에 넣을 때는 아무것도 공유하지 않는다 — 공유 처리는 공유 대시보드에만 붙는다.
    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("비공개 대시보드", null, false, null), ownerUserId);
    Long myQuery = createQueryAs(ownerUserId, "비공개 쿼리", false);
    Long myChart = createChartAs(ownerUserId, "비공개 차트", myQuery, false);

    DashboardResponse afterAdd =
        dashboardService.addWidget(
            dashboard.id(), new AddWidgetRequest(myChart, 0, 0, 6, 4), ownerUserId);

    assertThat(chartSharedInDb(myChart)).isFalse();
    assertThat(querySharedInDb(myQuery)).isFalse();
    // 주인 화면은 그대로 — 자기 비공개 차트는 이름까지 보인다
    DashboardResponse.DashboardWidgetResponse w = widgetOf(afterAdd, myChart);
    assertThat(w.accessible()).isTrue();
    assertThat(w.chartName()).isEqualTo("비공개 차트");
    assertThat(w.chartType()).isEqualTo("BAR");
  }

  @Test
  void othersChartMadePrivate_widgetWithheldForViewerAndDashboardOwner_butOwnerCanRemove() {
    // Given: other 의 공유 차트를 owner 가 공유 대시보드에 넣은 뒤, other 가 그 차트를 비공개로 돌린다
    Long thirdUserId = createUser("dashboard_third_viewer");
    Long othersQuery = createQueryAs(otherUserId, "남의 쿼리", true);
    Long othersChart = createChartAs(otherUserId, "남의 기밀 차트", othersQuery, true);
    Long myQuery = createQueryAs(ownerUserId, "내 쿼리", true);
    Long myChart = createChartAs(ownerUserId, "내 공유 차트", myQuery, true);
    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("섞인 공유 대시보드", null, true, null), ownerUserId);
    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(othersChart, 3, 2, 5, 3), ownerUserId);
    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(myChart, 0, 0, 3, 2), ownerUserId);
    chartService.update(
        othersChart, new UpdateChartRequest(null, null, null, null, false), otherUserId);

    // Then: 제3 조회자 — 이름·종류 없이 accessible=false, 위치·크기는 유지, 이웃 위젯은 정상
    DashboardResponse thirdView = dashboardService.getById(dashboard.id(), thirdUserId);
    DashboardResponse.DashboardWidgetResponse hidden = widgetOf(thirdView, othersChart);
    assertThat(hidden.accessible()).isFalse();
    assertThat(hidden.chartName()).isNull();
    assertThat(hidden.chartType()).isNull();
    assertThat(hidden.positionX()).isEqualTo(3);
    assertThat(hidden.positionY()).isEqualTo(2);
    assertThat(hidden.width()).isEqualTo(5);
    assertThat(hidden.height()).isEqualTo(3);
    assertThat(widgetOf(thirdView, myChart).accessible()).isTrue();
    assertThat(widgetOf(thirdView, myChart).chartName()).isEqualTo("내 공유 차트");

    // 일괄 데이터 경로도 같은 규칙 — 볼 수 없는 위젯은 데이터에서 빠지고, 함께 실린 대시보드 위젯 목록에서도 이름이 가려진다
    var thirdData = dashboardService.getDashboardData(dashboard.id(), thirdUserId);
    assertThat(thirdData.widgetData()).extracting(d -> d.widgetId()).doesNotContain(hidden.id());
    assertThat(widgetOf(thirdData.dashboard(), othersChart).chartName()).isNull();

    // 대시보드 주인에게도 남의 비공개 차트는 가려진다(차트 단건 경로와 같은 규칙)
    DashboardResponse ownerView = dashboardService.getById(dashboard.id(), ownerUserId);
    assertThat(widgetOf(ownerView, othersChart).accessible()).isFalse();
    assertThat(widgetOf(ownerView, othersChart).chartName()).isNull();
    assertThat(widgetOf(ownerView, myChart).chartName()).isEqualTo("내 공유 차트");

    // 차트 주인(other)에게는 자기 차트라 그대로 보인다
    DashboardResponse chartOwnerView = dashboardService.getById(dashboard.id(), otherUserId);
    assertThat(widgetOf(chartOwnerView, othersChart).accessible()).isTrue();
    assertThat(widgetOf(chartOwnerView, othersChart).chartName()).isEqualTo("남의 기밀 차트");

    // When: 대시보드 주인이 볼 수 없게 된 위젯을 삭제
    dashboardService.removeWidget(dashboard.id(), hidden.id(), ownerUserId);

    // Then: 삭제된다(남의 차트 자체는 그대로)
    DashboardResponse afterRemove = dashboardService.getById(dashboard.id(), ownerUserId);
    assertThat(afterRemove.widgets()).extracting(w -> w.chartId()).containsExactly(myChart);
    assertThat(chartSharedInDb(othersChart)).isFalse();
  }

  // ── WD-202: 위젯 추가는 대시보드 행 잠금으로 직렬화하고, 같은 차트 중복을 서버에서 거부한다 ─────────────────────

  private static final String DUPLICATE_CHART_MESSAGE = "이미 대시보드에 추가된 차트입니다";
  private static final String LIMIT_MESSAGE = "대시보드에는 위젯을 최대 20개까지 넣을 수 있습니다";

  @Test
  void addWidget_sameChartTwice_rejectedAsConflict_andKeepsSingleWidget() {
    // 웹 편집 화면만 막던 중복을 서버가 막는다 — AI 도구·API·차트 편집기 「대시보드에 추가」가 모두 이 경로를 탄다.
    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest("중복 차트 대시보드", null, false, null), ownerUserId);
    Long chart = createChartAs(ownerUserId, "한 번만 들어갈 차트", createSavedQuery(), false);
    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(chart, 0, 0, 6, 4), ownerUserId);

    // IllegalStateException 은 GlobalExceptionHandler 가 409 + 이 문구로 내보낸다(한도 초과와 같은 형태).
    assertThatThrownBy(
            () ->
                dashboardService.addWidget(
                    dashboard.id(), new AddWidgetRequest(chart, 6, 0, 6, 4), ownerUserId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(DUPLICATE_CHART_MESSAGE);
    assertThat(dashboardService.getById(dashboard.id(), ownerUserId).widgets())
        .extracting(w -> w.chartId())
        .containsExactly(chart);
  }

  /**
   * 동시 위젯 추가 경합(WD-202). 테스트 트랜잭션을 끈다 — 작업 스레드들이 서로의 커밋과 픽스처를 봐야 한다. 각 요청은 제품 경로({@code addWidget}
   * 의 @Transactional)가 스스로 연 트랜잭션에서 돈다. 커밋된 픽스처는 finally 에서 지운다.
   *
   * <p>테스트 커넥션 풀이 2개라 경합 폭이 운영보다 좁다 — 잠금 유무를 결정적으로 가르는 것은 아래 {@link
   * #addWidget_waitsForDashboardRowLock_thenSeesCommittedDuplicate} 이고, 이 테스트는 결과 불변식(한도·중복)을 확인한다.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void addWidget_concurrentRequests_neverExceedLimitOrDuplicate() throws Exception {
    try {
      Long queryId = createSavedQuery();
      DashboardResponse dashboard =
          dashboardService.create(
              new CreateDashboardRequest("경합 대시보드", null, false, null), ownerUserId);
      for (int i = 0; i < 18; i++) {
        Long c = createChartAs(ownerUserId, "기존 차트 " + i, queryId, false);
        dashboardService.addWidget(
            dashboard.id(), new AddWidgetRequest(c, 0, i * 4, 6, 4), ownerUserId);
      }
      List<Long> newCharts = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        newCharts.add(createChartAs(ownerUserId, "새 차트 " + i, queryId, false));
      }

      // 서로 다른 차트 12건 동시 추가 → 남은 2칸만 성공, 나머지는 한도 409
      Map<String, Integer> distinct = raceAddWidget(dashboard.id(), newCharts);
      assertThat(distinct).containsExactlyInAnyOrderEntriesOf(Map.of("OK", 2, LIMIT_MESSAGE, 10));
      assertThat(widgetChartIds(dashboard.id())).hasSize(20).doesNotHaveDuplicates();

      // 같은 차트 8건 동시 추가(빈 대시보드) → 1건만 성공, 나머지는 중복 409
      DashboardResponse empty =
          dashboardService.create(
              new CreateDashboardRequest("같은 차트 경합", null, false, null), ownerUserId);
      Long sameChart = newCharts.get(0);
      Map<String, Integer> same =
          raceAddWidget(empty.id(), java.util.Collections.nCopies(8, sameChart));
      assertThat(same)
          .containsExactlyInAnyOrderEntriesOf(Map.of("OK", 1, DUPLICATE_CHART_MESSAGE, 7));
      assertThat(widgetChartIds(empty.id())).containsExactly(sameChart);
    } finally {
      cleanupCommittedFixtures();
    }
  }

  /**
   * 잠금이 실제로 위젯 추가를 막는지 결정적으로 본다(WD-202). 다른 트랜잭션이 대시보드 행을 쥔 동안 addWidget 은 끝나지 않아야 하고, 그 트랜잭션이 같은
   * 차트를 넣고 커밋하면 풀려난 addWidget 은 그 커밋을 보고 중복으로 거부해야 한다. 잠금이 없으면 addWidget 이 바로 끝나 같은 차트가 두 개가 된다.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void addWidget_waitsForDashboardRowLock_thenSeesCommittedDuplicate() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      DashboardResponse dashboard =
          dashboardService.create(
              new CreateDashboardRequest("잠금 대기 대시보드", null, false, null), ownerUserId);
      Long chart = createChartAs(ownerUserId, "잠금 대기 차트", createSavedQuery(), false);

      CountDownLatch locked = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      // 첫 트랜잭션: 대시보드 행을 FOR UPDATE 로 잡고, 신호가 오면 같은 차트 위젯을 넣고 커밋한다(다른 요청의 추가를 흉내).
      Future<?> holder =
          pool.submit(
              () ->
                  inTenantFixture(
                      () -> {
                        dsl.execute(
                            "SELECT id FROM dashboard WHERE id = ? FOR UPDATE", dashboard.id());
                        locked.countDown();
                        try {
                          if (!release.await(30, TimeUnit.SECONDS)) {
                            throw new AssertionError("release 신호가 오지 않았다");
                          }
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError(e);
                        }
                        dsl.execute(
                            "INSERT INTO dashboard_widget (dashboard_id, chart_id, position_x,"
                                + " position_y, width, height) VALUES (?, ?, 0, 0, 6, 4)",
                            dashboard.id(),
                            chart);
                      }));
      assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

      CompletableFuture<Void> adder =
          CompletableFuture.runAsync(
              () -> {
                TenantContext.set(DEFAULT_TEST_TENANT_ID);
                try {
                  dashboardService.addWidget(
                      dashboard.id(), new AddWidgetRequest(chart, 6, 0, 6, 4), ownerUserId);
                } finally {
                  TenantContext.clear();
                }
              },
              pool);

      // 잠금을 쥔 동안에는 끝나면 안 된다(관측 조회는 풀 2개를 둘 다 쓰고 있어 하지 않는다).
      Thread.sleep(1_000);
      if (adder.isDone()) {
        adder.get(); // 예외로 끝났으면 그대로 드러낸다
        throw new AssertionError("addWidget 이 대시보드 행 잠금을 기다리지 않고 끝났다");
      }

      release.countDown();
      holder.get(30, TimeUnit.SECONDS);
      assertThatThrownBy(() -> adder.get(30, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage(DUPLICATE_CHART_MESSAGE);
      assertThat(widgetChartIds(dashboard.id())).containsExactly(chart);
    } finally {
      pool.shutdownNow();
      cleanupCommittedFixtures();
    }
  }

  /** 같은 대시보드에 addWidget 을 동시에 쏘고 결과를 "OK" 또는 409 문구별 건수로 모은다. 그 밖의 예외는 그대로 테스트 실패로 드러낸다. */
  private Map<String, Integer> raceAddWidget(Long dashboardId, List<Long> chartIds)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(chartIds.size());
    CyclicBarrier start = new CyclicBarrier(chartIds.size());
    try {
      List<Future<String>> futures = new ArrayList<>();
      for (Long chartId : chartIds) {
        futures.add(
            pool.submit(
                () -> {
                  // 운영의 요청 필터처럼 스레드마다 테넌트 컨텍스트를 세운다 — 없으면 트랜잭션에 GUC 가 안 붙는다.
                  TenantContext.set(DEFAULT_TEST_TENANT_ID);
                  try {
                    start.await(30, TimeUnit.SECONDS);
                    dashboardService.addWidget(
                        dashboardId, new AddWidgetRequest(chartId, 0, 0, 6, 4), ownerUserId);
                    return "OK";
                  } catch (IllegalStateException e) {
                    return e.getMessage();
                  } finally {
                    TenantContext.clear();
                  }
                }));
      }
      Map<String, Integer> outcomes = new TreeMap<>();
      for (Future<String> f : futures) {
        outcomes.merge(f.get(60, TimeUnit.SECONDS), 1, Integer::sum);
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }

  /** 대시보드의 위젯 차트 id 들(DB 원본, 커밋된 값). */
  private List<Long> widgetChartIds(Long dashboardId) {
    return inTenantFixture(
        () ->
            dsl.select(DSL.field(DSL.name("chart_id"), Long.class))
                .from(DSL.table(DSL.name("dashboard_widget")))
                .where(DSL.field(DSL.name("dashboard_id"), Long.class).eq(dashboardId))
                .fetch(DSL.field(DSL.name("chart_id"), Long.class)));
  }

  /** 트랜잭션을 끈 테스트가 커밋한 픽스처(이 클래스의 두 사용자와 그들이 만든 분석 자원)를 지운다. */
  private void cleanupCommittedFixtures() {
    awaitSecurityAudit();
    List<Long> users = List.of(ownerUserId, otherUserId);
    inTenantFixture(
        () -> {
          dsl.execute(
              "DELETE FROM dashboard_widget WHERE dashboard_id IN"
                  + " (SELECT id FROM dashboard WHERE created_by = ANY(?))",
              (Object) users.toArray(new Long[0]));
          dsl.execute(
              "DELETE FROM dashboard WHERE created_by = ANY(?)",
              (Object) users.toArray(new Long[0]));
          dsl.execute(
              "DELETE FROM chart WHERE created_by = ANY(?)", (Object) users.toArray(new Long[0]));
          dsl.execute(
              "DELETE FROM saved_query WHERE created_by = ANY(?)",
              (Object) users.toArray(new Long[0]));
          dsl.execute(
              "DELETE FROM audit_log WHERE user_id = ANY(?)", (Object) users.toArray(new Long[0]));
          dsl.execute(
              "DELETE FROM \"user\" WHERE id = ANY(?)", (Object) users.toArray(new Long[0]));
        });
  }
}
