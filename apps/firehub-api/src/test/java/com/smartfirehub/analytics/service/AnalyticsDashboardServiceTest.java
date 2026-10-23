package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.AddWidgetRequest;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
}
