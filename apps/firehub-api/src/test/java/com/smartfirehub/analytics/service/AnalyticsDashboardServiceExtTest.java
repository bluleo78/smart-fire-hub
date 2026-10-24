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
 * AnalyticsDashboardService 추가 통합 테스트. list, getById, delete, getDashboardData, updateWidget,
 * removeWidget, updateWidgetLayout 커버.
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
    PageResponse<DashboardResponse> result = dashboardService.list(null, null, ownerUserId, 0, 10);

    // Then
    assertThat(result.content()).hasSizeGreaterThanOrEqualTo(2);
    List<String> names = result.content().stream().map(DashboardResponse::name).toList();
    assertThat(names).contains("Alpha", "Beta");
  }

  @Test
  void list_searchFilter_returnsMatchingDashboards() {
    dashboardService.create(
        new CreateDashboardRequest("UniqueTitle123", null, false, null), ownerUserId);
    dashboardService.create(
        new CreateDashboardRequest("OtherDash", null, false, null), ownerUserId);

    PageResponse<DashboardResponse> result =
        dashboardService.list("UniqueTitle123", null, ownerUserId, 0, 10);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).name()).isEqualTo("UniqueTitle123");
  }

  @Test
  void list_sharedDashboard_visibleToOtherUser() {
    dashboardService.create(
        new CreateDashboardRequest("PublicDash", null, true, null), ownerUserId);

    PageResponse<DashboardResponse> result = dashboardService.list(null, null, otherUserId, 0, 10);

    List<String> names = result.content().stream().map(DashboardResponse::name).toList();
    assertThat(names).contains("PublicDash");
  }

  // =========================================================================
  // getById
  // =========================================================================

  @Test
  void getById_existingDashboard_returnsDashboard() {
    DashboardResponse created =
        dashboardService.create(
            new CreateDashboardRequest("GetMe", "desc", false, null), ownerUserId);

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
        dashboardService.create(
            new CreateDashboardRequest("ToDelete", null, false, null), ownerUserId);

    dashboardService.delete(created.id(), ownerUserId);

    assertThatThrownBy(() -> dashboardService.getById(created.id(), ownerUserId))
        .isInstanceOf(DashboardNotFoundException.class);
  }

  @Test
  void delete_nonOwner_throwsNotFound() {
    DashboardResponse created =
        dashboardService.create(
            new CreateDashboardRequest("NotMine", null, false, null), ownerUserId);

    // WD-196: 소유자 확인 실패 문구는 한국어(존재를 숨기는 404 정책은 그대로 — 없는 대시보드와 같은 문구)
    assertThatThrownBy(() -> dashboardService.delete(created.id(), otherUserId))
        .isInstanceOf(DashboardNotFoundException.class)
        .hasMessage("대시보드를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
  }

  /**
   * WD-196: 남이 공유한 대시보드를 다른 사람이 고치려 하면(이름 변경·위젯 추가·삭제·배치) 모두 같은 한국어 404 문구로 거절한다. 예전에는 「Dashboard not
   * found: 2」가 토스트로 그대로 보였다. 조회는 그대로 된다 — 읽기 전용 보기는 유지.
   */
  @Test
  void writes_nonOwnerOfSharedDashboard_rejectWithKoreanNotFound() {
    DashboardResponse shared =
        dashboardService.create(
            new CreateDashboardRequest("SharedNotMine", null, true, null), ownerUserId);
    var chart =
        chartService.create(
            new CreateChartRequest("Shared Chart", null, savedQueryId, "BAR", Map.of(), true),
            ownerUserId);
    Long widgetId =
        dashboardService
            .addWidget(shared.id(), new AddWidgetRequest(chart.id(), 0, 0, 6, 4), ownerUserId)
            .widgets()
            .get(0)
            .id();
    String expected = "대시보드를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.";

    // 조회는 된다(공유) — 주인 확인은 쓰기에만 걸린다
    assertThat(dashboardService.getById(shared.id(), otherUserId).createdBy())
        .isEqualTo(ownerUserId);
    assertThatThrownBy(
            () ->
                dashboardService.update(
                    shared.id(),
                    new UpdateDashboardRequest("Hacked", null, null, null, null),
                    otherUserId))
        .isInstanceOf(DashboardNotFoundException.class)
        .hasMessage(expected);
    assertThatThrownBy(
            () ->
                dashboardService.addWidget(
                    shared.id(), new AddWidgetRequest(chart.id(), 0, 4, 6, 4), otherUserId))
        .isInstanceOf(DashboardNotFoundException.class)
        .hasMessage(expected);
    assertThatThrownBy(() -> dashboardService.removeWidget(shared.id(), widgetId, otherUserId))
        .isInstanceOf(DashboardNotFoundException.class)
        .hasMessage(expected);
    assertThatThrownBy(
            () ->
                dashboardService.updateWidget(
                    shared.id(), widgetId, new UpdateWidgetRequest(1, 1, 4, 3), otherUserId))
        .isInstanceOf(DashboardNotFoundException.class)
        .hasMessage(expected);
    // 아무것도 바뀌지 않았다
    assertThat(dashboardService.getById(shared.id(), ownerUserId).widgets()).hasSize(1);
  }

  @Test
  void delete_nonExistent_throwsNotFound() {
    assertThatThrownBy(() -> dashboardService.delete(999999L, ownerUserId))
        .isInstanceOf(DashboardNotFoundException.class)
        .hasMessage("대시보드를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."); // WD-240: 번호 없음
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
        dashboardService.create(
            new CreateDashboardRequest("DataDash", null, true, null), ownerUserId);

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
        dashboardService.create(
            new CreateDashboardRequest("EmptyDash", null, false, null), ownerUserId);

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
        dashboardService.create(
            new CreateDashboardRequest("WidgetDash", null, false, null), ownerUserId);

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
        dashboardService.create(
            new CreateDashboardRequest("NoWidgetDash", null, false, null), ownerUserId);

    assertThatThrownBy(
            () ->
                dashboardService.updateWidget(
                    dashboard.id(), 999999L, new UpdateWidgetRequest(0, 0, 4, 4), ownerUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("위젯을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."); // WD-196·WD-240: 한국어·번호 없음
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
        dashboardService.create(
            new CreateDashboardRequest("RemoveWidgetDash", null, false, null), ownerUserId);

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
        dashboardService.create(
            new CreateDashboardRequest("RemoveNone", null, false, null), ownerUserId);

    assertThatThrownBy(() -> dashboardService.removeWidget(dashboard.id(), 999999L, ownerUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("위젯을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."); // WD-196·WD-240: 한국어·번호 없음
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
        dashboardService.create(
            new CreateDashboardRequest("LayoutDash", null, false, null), ownerUserId);

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

    dashboardService.updateWidgetLayout(
        dashboard.id(), new UpdateWidgetLayoutRequest(layouts), ownerUserId);

    // 레이아웃 변경 후 저장된 좌표·크기가 요청값과 같은지 확인한다(WD-193 — 개수만 보면 저장 여부를 증명하지 못한다).
    assertThat(widgetLayout(w1)).containsExactly(2, 2, 5, 3);
    assertThat(widgetLayout(w2)).containsExactly(7, 2, 5, 3);
  }

  /**
   * WD-193: 자기 대시보드 경로에 남의 대시보드 위젯 id 를 섞으면 전체가 거절되고, 남의 위젯은 물론 함께 보낸 자기 위젯도 바뀌지 않아야 한다(자기 위젯 불변 =
   * 일부 반영이 없었다는 증거).
   */
  @Test
  void updateWidgetLayout_foreignWidgetMixedIn_rejectsAllAndLeavesDbUnchanged() {
    Long ownWidget = addWidgetFor(ownerUserId, "WD193 Own");
    Long ownDashboard = dashboardIdOf(ownWidget);
    Long foreignWidget = addWidgetFor(otherUserId, "WD193 Foreign");

    List<UpdateWidgetLayoutRequest.WidgetPosition> layouts =
        List.of(
            new UpdateWidgetLayoutRequest.WidgetPosition(ownWidget, 3, 3, 4, 4),
            new UpdateWidgetLayoutRequest.WidgetPosition(foreignWidget, 11, 40, 1, 1));

    assertThatThrownBy(
            () ->
                dashboardService.updateWidgetLayout(
                    ownDashboard, new UpdateWidgetLayoutRequest(layouts), ownerUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("이 대시보드에 없는 위젯이 포함되어 있습니다: [" + foreignWidget + "]");

    // 남의 위젯·자기 위젯 모두 추가 당시 값(0,0,6,4) 그대로
    assertThat(widgetLayout(foreignWidget)).containsExactly(0, 0, 6, 4);
    assertThat(widgetLayout(ownWidget)).containsExactly(0, 0, 6, 4);
  }

  /** WD-193: 없는 위젯 id 도 남의 위젯 id 와 같은 예외·문구로 거절된다 — 다른 대시보드에 위젯이 있는지 드러내지 않는다. */
  @Test
  void updateWidgetLayout_nonExistentWidget_rejectedWithSameMessage() {
    Long ownWidget = addWidgetFor(ownerUserId, "WD193 Own2");
    Long ownDashboard = dashboardIdOf(ownWidget);
    long missingId = 999_999_999L;

    assertThatThrownBy(
            () ->
                dashboardService.updateWidgetLayout(
                    ownDashboard,
                    new UpdateWidgetLayoutRequest(
                        List.of(
                            new UpdateWidgetLayoutRequest.WidgetPosition(missingId, 1, 1, 2, 2))),
                    ownerUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("이 대시보드에 없는 위젯이 포함되어 있습니다: [" + missingId + "]");
    assertThat(widgetLayout(ownWidget)).containsExactly(0, 0, 6, 4);
  }

  /** WD-193: 빈 목록은 아무것도 바꾸지 않고 정상 종료한다. */
  @Test
  void updateWidgetLayout_emptyList_noOp() {
    Long ownWidget = addWidgetFor(ownerUserId, "WD193 Own3");
    Long ownDashboard = dashboardIdOf(ownWidget);

    dashboardService.updateWidgetLayout(
        ownDashboard, new UpdateWidgetLayoutRequest(List.of()), ownerUserId);

    assertThat(widgetLayout(ownWidget)).containsExactly(0, 0, 6, 4);
  }

  /** WD-193: 남의 대시보드 경로로는(소유 아님) 여전히 대시보드 404 로 거절되고 위젯은 바뀌지 않는다. */
  @Test
  void updateWidgetLayout_nonOwnerDashboardPath_throwsNotFound() {
    Long foreignWidget = addWidgetFor(otherUserId, "WD193 Foreign2");
    Long foreignDashboard = dashboardIdOf(foreignWidget);

    assertThatThrownBy(
            () ->
                dashboardService.updateWidgetLayout(
                    foreignDashboard,
                    new UpdateWidgetLayoutRequest(
                        List.of(
                            new UpdateWidgetLayoutRequest.WidgetPosition(
                                foreignWidget, 1, 1, 2, 2))),
                    ownerUserId))
        .isInstanceOf(DashboardNotFoundException.class);
    assertThat(widgetLayout(foreignWidget)).containsExactly(0, 0, 6, 4);
  }

  // ── WD-193 헬퍼 ─────────────────────────────────────────────────────────

  /** 사용자 소유 차트·대시보드를 만들고 위젯 하나(0,0,6,4)를 붙여 위젯 id 를 돌려준다. */
  private Long addWidgetFor(Long userId, String name) {
    var chart =
        chartService.create(
            new CreateChartRequest(name + " Chart", null, savedQueryId, "BAR", Map.of(), false),
            userId);
    DashboardResponse dashboard =
        dashboardService.create(
            new CreateDashboardRequest(name + " Dash", null, false, null), userId);
    DashboardResponse after =
        dashboardService.addWidget(
            dashboard.id(), new AddWidgetRequest(chart.id(), 0, 0, 6, 4), userId);
    return after.widgets().get(0).id();
  }

  /** 위젯이 속한 대시보드 id 를 DB 에서 직접 읽는다. */
  private Long dashboardIdOf(Long widgetId) {
    return dsl.select(DSL.field(DSL.name("dashboard_id"), Long.class))
        .from(DSL.table(DSL.name("dashboard_widget")))
        .where(DSL.field(DSL.name("id"), Long.class).eq(widgetId))
        .fetchOne(0, Long.class);
  }

  /** 위젯의 [position_x, position_y, width, height] 를 DB 에서 직접 읽는다(서비스 우회 — 저장 결과 자체를 본다). */
  private List<Integer> widgetLayout(Long widgetId) {
    var r =
        dsl.select(
                DSL.field(DSL.name("position_x"), Integer.class),
                DSL.field(DSL.name("position_y"), Integer.class),
                DSL.field(DSL.name("width"), Integer.class),
                DSL.field(DSL.name("height"), Integer.class))
            .from(DSL.table(DSL.name("dashboard_widget")))
            .where(DSL.field(DSL.name("id"), Long.class).eq(widgetId))
            .fetchOne();
    return List.of(r.value1(), r.value2(), r.value3(), r.value4());
  }
}
