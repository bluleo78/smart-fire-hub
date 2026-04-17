package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.analytics.dto.AddWidgetRequest;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.DashboardDataResponse;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
import com.smartfirehub.analytics.dto.UpdateWidgetLayoutRequest;
import com.smartfirehub.analytics.dto.UpdateWidgetRequest;
import com.smartfirehub.analytics.exception.DashboardNotFoundException;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * AnalyticsDashboardService 추가 통합 테스트.
 * list, getById, delete, getDashboardData, updateWidget, removeWidget, updateWidgetLayout 커버.
 */
@Transactional
class AnalyticsDashboardServiceExtTest extends IntegrationTestBase {

  @Autowired private AnalyticsDashboardService dashboardService;
  @Autowired private ChartService chartService;
  @Autowired private SavedQueryService savedQueryService;
  @Autowired private DSLContext dsl;

  private Long ownerUserId;
  private Long otherUserId;
  private Long savedQueryId;

  @BeforeEach
  void setUp() {
    ownerUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "dash_ext_owner")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Dash Ext Owner")
            .set(DSL.field(DSL.name("user", "email"), String.class), "dash_ext_owner@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    otherUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "dash_ext_other")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Dash Ext Other")
            .set(DSL.field(DSL.name("user", "email"), String.class), "dash_ext_other@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    // 공유 SavedQuery 생성
    var sq =
        savedQueryService.create(
            new CreateSavedQueryRequest("Ext Query", null, "SELECT 1 AS val", null, null, true),
            ownerUserId);
    savedQueryId = sq.id();
  }

  // =========================================================================
  // list
  // =========================================================================

  @Test
  void list_returnsDashboardsForUser() {
    // Given: 오너 대시보드 2개
    dashboardService.create(new CreateDashboardRequest("Alpha", null, false, null), ownerUserId);
    dashboardService.create(new CreateDashboardRequest("Beta", null, false, null), ownerUserId);

    // When
    PageResponse<DashboardResponse> result = dashboardService.list(null, ownerUserId, 0, 10);

    // Then
    assertThat(result.content()).hasSizeGreaterThanOrEqualTo(2);
    List<String> names = result.content().stream().map(DashboardResponse::name).toList();
    assertThat(names).contains("Alpha", "Beta");
  }

  @Test
  void list_searchFilter_returnsMatchingDashboards() {
    dashboardService.create(new CreateDashboardRequest("UniqueTitle123", null, false, null), ownerUserId);
    dashboardService.create(new CreateDashboardRequest("OtherDash", null, false, null), ownerUserId);

    PageResponse<DashboardResponse> result = dashboardService.list("UniqueTitle123", ownerUserId, 0, 10);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).name()).isEqualTo("UniqueTitle123");
  }

  @Test
  void list_sharedDashboard_visibleToOtherUser() {
    dashboardService.create(new CreateDashboardRequest("PublicDash", null, true, null), ownerUserId);

    PageResponse<DashboardResponse> result = dashboardService.list(null, otherUserId, 0, 10);

    List<String> names = result.content().stream().map(DashboardResponse::name).toList();
    assertThat(names).contains("PublicDash");
  }

  // =========================================================================
  // getById
  // =========================================================================

  @Test
  void getById_existingDashboard_returnsDashboard() {
    DashboardResponse created =
        dashboardService.create(new CreateDashboardRequest("GetMe", "desc", false, null), ownerUserId);

    DashboardResponse found = dashboardService.getById(created.id(), ownerUserId);

    assertThat(found.id()).isEqualTo(created.id());
    assertThat(found.name()).isEqualTo("GetMe");
  }

  @Test
  void getById_nonExistent_throwsNotFound() {
    assertThatThrownBy(() -> dashboardService.getById(999999L, ownerUserId))
        .isInstanceOf(DashboardNotFoundException.class);
  }

  // =========================================================================
  // delete
  // =========================================================================

  @Test
  void delete_ownerCanDelete_success() {
    DashboardResponse created =
        dashboardService.create(new CreateDashboardRequest("ToDelete", null, false, null), ownerUserId);

    dashboardService.delete(created.id(), ownerUserId);

    assertThatThrownBy(() -> dashboardService.getById(created.id(), ownerUserId))
        .isInstanceOf(DashboardNotFoundException.class);
  }

  @Test
  void delete_nonOwner_throwsNotFound() {
    DashboardResponse created =
        dashboardService.create(new CreateDashboardRequest("NotMine", null, false, null), ownerUserId);

    assertThatThrownBy(() -> dashboardService.delete(created.id(), otherUserId))
        .isInstanceOf(DashboardNotFoundException.class);
  }

  @Test
  void delete_nonExistent_throwsNotFound() {
    assertThatThrownBy(() -> dashboardService.delete(999999L, ownerUserId))
        .isInstanceOf(DashboardNotFoundException.class);
  }

  // =========================================================================
  // getDashboardData
  // =========================================================================

  @Test
  void getDashboardData_withWidgets_returnsData() {
    var chart =
        chartService.create(
            new CreateChartRequest("Ext Chart", null, savedQueryId, "BAR", Map.of(), true),
            ownerUserId);

    DashboardResponse dashboard =
        dashboardService.create(new CreateDashboardRequest("DataDash", null, true, null), ownerUserId);

    dashboardService.addWidget(
        dashboard.id(), new AddWidgetRequest(chart.id(), 0, 0, 6, 4), ownerUserId);

    DashboardDataResponse data = dashboardService.getDashboardData(dashboard.id(), ownerUserId);

    assertThat(data).isNotNull();
    assertThat(data.dashboard().id()).isEqualTo(dashboard.id());
    assertThat(data.widgetData()).hasSize(1);
  }

  @Test
  void getDashboardData_emptyDashboard_returnsEmptyWidgets() {
    DashboardResponse dashboard =
        dashboardService.create(new CreateDashboardRequest("EmptyDash", null, false, null), ownerUserId);

    DashboardDataResponse data = dashboardService.getDashboardData(dashboard.id(), ownerUserId);

    assertThat(data.widgetData()).isEmpty();
  }

  @Test
  void getDashboardData_nonExistent_throwsNotFound() {
    assertThatThrownBy(() -> dashboardService.getDashboardData(999999L, ownerUserId))
        .isInstanceOf(DashboardNotFoundException.class);
  }

  // =========================================================================
  // updateWidget
  // =========================================================================

  @Test
  void updateWidget_ownerCanUpdate_success() {
    var chart =
        chartService.create(
            new CreateChartRequest("Widget Chart", null, savedQueryId, "LINE", Map.of(), true),
            ownerUserId);

    DashboardResponse dashboard =
        dashboardService.create(new CreateDashboardRequest("WidgetDash", null, false, null), ownerUserId);

    DashboardResponse afterAdd =
        dashboardService.addWidget(
            dashboard.id(), new AddWidgetRequest(chart.id(), 0, 0, 6, 4), ownerUserId);

    Long widgetId = afterAdd.widgets().get(0).id();

    DashboardResponse updated =
        dashboardService.updateWidget(
            dashboard.id(), widgetId, new UpdateWidgetRequest(1, 1, 4, 3), ownerUserId);

    assertThat(updated.widgets().get(0).positionX()).isEqualTo(1);
    assertThat(updated.widgets().get(0).positionY()).isEqualTo(1);
  }

  @Test
  void updateWidget_nonExistentWidget_throwsException() {
    DashboardResponse dashboard =
        dashboardService.create(new CreateDashboardRequest("NoWidgetDash", null, false, null), ownerUserId);

    assertThatThrownBy(
            () ->
                dashboardService.updateWidget(
                    dashboard.id(), 999999L, new UpdateWidgetRequest(0, 0, 4, 4), ownerUserId))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // =========================================================================
  // removeWidget
  // =========================================================================

  @Test
  void removeWidget_existingWidget_success() {
    var chart =
        chartService.create(
            new CreateChartRequest("Remove Chart", null, savedQueryId, "BAR", Map.of(), true),
            ownerUserId);

    DashboardResponse dashboard =
        dashboardService.create(new CreateDashboardRequest("RemoveWidgetDash", null, false, null), ownerUserId);

    DashboardResponse afterAdd =
        dashboardService.addWidget(
            dashboard.id(), new AddWidgetRequest(chart.id(), 0, 0, 6, 4), ownerUserId);

    Long widgetId = afterAdd.widgets().get(0).id();

    dashboardService.removeWidget(dashboard.id(), widgetId, ownerUserId);

    DashboardResponse after = dashboardService.getById(dashboard.id(), ownerUserId);
    assertThat(after.widgets()).isEmpty();
  }

  @Test
  void removeWidget_nonExistentWidget_throwsException() {
    DashboardResponse dashboard =
        dashboardService.create(new CreateDashboardRequest("RemoveNone", null, false, null), ownerUserId);

    assertThatThrownBy(() -> dashboardService.removeWidget(dashboard.id(), 999999L, ownerUserId))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // =========================================================================
  // updateWidgetLayout
  // =========================================================================

  @Test
  void updateWidgetLayout_multipleWidgets_success() {
    var chart1 =
        chartService.create(
            new CreateChartRequest("Layout Chart 1", null, savedQueryId, "BAR", Map.of(), true),
            ownerUserId);
    var chart2 =
        chartService.create(
            new CreateChartRequest("Layout Chart 2", null, savedQueryId, "LINE", Map.of(), true),
            ownerUserId);

    DashboardResponse dashboard =
        dashboardService.create(new CreateDashboardRequest("LayoutDash", null, false, null), ownerUserId);

    DashboardResponse after1 =
        dashboardService.addWidget(
            dashboard.id(), new AddWidgetRequest(chart1.id(), 0, 0, 6, 4), ownerUserId);
    DashboardResponse after2 =
        dashboardService.addWidget(
            dashboard.id(), new AddWidgetRequest(chart2.id(), 6, 0, 6, 4), ownerUserId);

    Long w1 = after2.widgets().get(0).id();
    Long w2 = after2.widgets().get(1).id();

    // updateWidgetLayout 정상 실행 확인 (예외 없이 완료)
    List<UpdateWidgetLayoutRequest.WidgetPosition> layouts =
        List.of(
            new UpdateWidgetLayoutRequest.WidgetPosition(w1, 2, 2, 5, 3),
            new UpdateWidgetLayoutRequest.WidgetPosition(w2, 7, 2, 5, 3));

    dashboardService.updateWidgetLayout(dashboard.id(), new UpdateWidgetLayoutRequest(layouts), ownerUserId);

    // 레이아웃 변경 후 대시보드 조회 가능 확인
    DashboardResponse result = dashboardService.getById(dashboard.id(), ownerUserId);
    assertThat(result.widgets()).hasSize(2);
  }
}
