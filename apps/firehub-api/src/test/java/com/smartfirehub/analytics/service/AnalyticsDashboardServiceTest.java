package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.AddWidgetRequest;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
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
            new CreateSavedQueryRequest(
                "Test Query", null, "SELECT 1", null, "test", false),
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
        dashboard.id(), new UpdateDashboardRequest("Test Dashboard", "desc", true, null), ownerUserId);

    // Then: contained charts should be auto-shared
    var updatedChart1 = chartService.getById(chart1.id(), ownerUserId);
    var updatedChart2 = chartService.getById(chart2.id(), ownerUserId);
    assertThat(updatedChart1.isShared()).isTrue();
    assertThat(updatedChart2.isShared()).isTrue();

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
        dashboard.id(), new UpdateDashboardRequest(null, null, true, null), ownerUserId);
    assertThat(chartService.getById(chart.id(), ownerUserId).isShared()).isTrue();

    // When: unshare the dashboard
    dashboardService.update(
        dashboard.id(), new UpdateDashboardRequest(null, null, false, null), ownerUserId);

    // Then: chart should still be shared (not auto-unshared)
    var stillSharedChart = chartService.getById(chart.id(), ownerUserId);
    assertThat(stillSharedChart.isShared()).isTrue();
  }
}
