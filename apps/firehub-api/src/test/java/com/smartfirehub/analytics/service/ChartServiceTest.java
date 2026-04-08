package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.analytics.dto.ChartDataResponse;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.analytics.exception.ChartNotFoundException;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
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
 * ChartService 통합 테스트.
 *
 * <p>list, create, getById, update, delete, getChartData 핵심 메서드 전체 커버.
 * MAP 차트 spatialColumn 검증 엣지 케이스 포함.
 * 정상/예외 케이스 모두 검증한다.
 */
@Transactional
class ChartServiceTest extends IntegrationTestBase {

  @Autowired private ChartService chartService;
  @Autowired private DSLContext dsl;

  /** 테스트 소유자 userId */
  private Long ownerUserId;
  /** 다른 사용자 userId */
  private Long otherUserId;
  /** 테스트용 saved_query id */
  private Long savedQueryId;
  /** 다른 사용자 소유 saved_query id */
  private Long otherSavedQueryId;

  // =========================================================================
  // Setup
  // =========================================================================

  @BeforeEach
  void setUp() {
    // owner 사용자 생성
    ownerUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "chart_owner")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Chart Owner")
            .set(DSL.field(DSL.name("user", "email"), String.class), "chart_owner@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    // 다른 사용자 생성
    otherUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "chart_other")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Chart Other")
            .set(DSL.field(DSL.name("user", "email"), String.class), "chart_other@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    // owner 소유 saved_query 생성 (공유)
    savedQueryId =
        dsl.insertInto(DSL.table(DSL.name("saved_query")))
            .set(DSL.field(DSL.name("saved_query", "name"), String.class), "Test Query")
            .set(DSL.field(DSL.name("saved_query", "sql_text"), String.class), "SELECT 1 AS value")
            .set(DSL.field(DSL.name("saved_query", "is_shared"), Boolean.class), true)
            .set(DSL.field(DSL.name("saved_query", "created_by"), Long.class), ownerUserId)
            .returning(DSL.field(DSL.name("saved_query", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("saved_query", "id"), Long.class));

    // 다른 사용자 소유 saved_query 생성 (비공개)
    otherSavedQueryId =
        dsl.insertInto(DSL.table(DSL.name("saved_query")))
            .set(DSL.field(DSL.name("saved_query", "name"), String.class), "Other Query")
            .set(DSL.field(DSL.name("saved_query", "sql_text"), String.class), "SELECT 2 AS value")
            .set(DSL.field(DSL.name("saved_query", "is_shared"), Boolean.class), false)
            .set(DSL.field(DSL.name("saved_query", "created_by"), Long.class), otherUserId)
            .returning(DSL.field(DSL.name("saved_query", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("saved_query", "id"), Long.class));
  }

  // =========================================================================
  // Helper
  // =========================================================================

  /**
   * 기본 BAR 차트를 생성하는 헬퍼 메서드.
   *
   * @param name 차트 이름
   * @param isShared 공유 여부
   * @param userId 생성자 userId
   */
  private ChartResponse createChart(String name, boolean isShared, Long userId) {
    return chartService.create(
        new CreateChartRequest(
            name, null, savedQueryId, "BAR", Map.of("xAxis", "col1", "yAxis", "col2"), isShared),
        userId);
  }

  /**
   * MAP 차트를 생성하는 헬퍼 메서드.
   *
   * @param name 차트 이름
   * @param config spatialColumn 등 MAP 설정
   */
  private ChartResponse createMapChart(String name, Map<String, Object> config, Long userId) {
    return chartService.create(
        new CreateChartRequest(name, null, savedQueryId, "MAP", config, false), userId);
  }

  // =========================================================================
  // Create
  // =========================================================================

  /** 정상: 유효한 savedQueryId로 차트 생성 성공 */
  @Test
  void create_withValidSavedQuery_success() {
    ChartResponse response = createChart("My Bar Chart", false, ownerUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.name()).isEqualTo("My Bar Chart");
    assertThat(response.chartType()).isEqualTo("BAR");
    assertThat(response.savedQueryId()).isEqualTo(savedQueryId);
    assertThat(response.isShared()).isFalse();
    assertThat(response.createdBy()).isEqualTo(ownerUserId);
  }

  /** 정상: 공유 차트 생성 */
  @Test
  void create_sharedChart_success() {
    ChartResponse response = createChart("Shared Chart", true, ownerUserId);

    assertThat(response.isShared()).isTrue();
  }

  /** 예외: 존재하지 않는 savedQueryId로 생성 시 SavedQueryNotFoundException */
  @Test
  void create_withNonExistentSavedQueryId_throwsNotFound() {
    assertThatThrownBy(
            () ->
                chartService.create(
                    new CreateChartRequest(
                        "Invalid Chart",
                        null,
                        999999L,
                        "BAR",
                        Map.of("xAxis", "col1"),
                        false),
                    ownerUserId))
        .isInstanceOf(SavedQueryNotFoundException.class);
  }

  /** 예외: 접근 불가 (비공개) savedQuery로 차트 생성 시 SavedQueryNotFoundException */
  @Test
  void create_withInaccessiblePrivateSavedQuery_throwsNotFound() {
    // otherSavedQueryId는 비공개이므로 ownerUserId는 접근 불가
    assertThatThrownBy(
            () ->
                chartService.create(
                    new CreateChartRequest(
                        "Access Denied Chart",
                        null,
                        otherSavedQueryId,
                        "LINE",
                        Map.of("xAxis", "col1"),
                        false),
                    ownerUserId))
        .isInstanceOf(SavedQueryNotFoundException.class);
  }

  /** 정상: MAP 차트 — spatialColumn이 있으면 생성 성공 */
  @Test
  void create_mapChart_withSpatialColumn_success() {
    ChartResponse response =
        createMapChart("My Map", Map.of("spatialColumn", "geom", "color", "#FF0000"), ownerUserId);

    assertThat(response.chartType()).isEqualTo("MAP");
    assertThat(response.config()).containsKey("spatialColumn");
  }

  /** 예외: MAP 차트 — spatialColumn 누락 시 IllegalArgumentException */
  @Test
  void create_mapChart_withoutSpatialColumn_throwsIllegalArgument() {
    assertThatThrownBy(
            () -> createMapChart("Bad Map", Map.of("color", "#FF0000"), ownerUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("spatialColumn");
  }

  /** 예외: MAP 차트 — spatialColumn이 빈 문자열이면 IllegalArgumentException */
  @Test
  void create_mapChart_withBlankSpatialColumn_throwsIllegalArgument() {
    assertThatThrownBy(
            () -> createMapChart("Blank Map", Map.of("spatialColumn", "   "), ownerUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("spatialColumn");
  }

  // =========================================================================
  // GetById
  // =========================================================================

  /** 정상: 소유자는 자신의 차트를 조회 가능 */
  @Test
  void getById_ownerCanAccessOwnChart() {
    ChartResponse created = createChart("Owner Chart", false, ownerUserId);

    ChartResponse found = chartService.getById(created.id(), ownerUserId);

    assertThat(found.id()).isEqualTo(created.id());
    assertThat(found.name()).isEqualTo("Owner Chart");
  }

  /** 정상: 공유 차트는 다른 사용자도 조회 가능 */
  @Test
  void getById_sharedChartAccessibleByOtherUser() {
    ChartResponse shared = createChart("Shared Chart", true, ownerUserId);

    ChartResponse found = chartService.getById(shared.id(), otherUserId);

    assertThat(found.id()).isEqualTo(shared.id());
  }

  /** 예외: 비공개 차트를 다른 사용자가 조회 시 ChartNotFoundException */
  @Test
  void getById_privateChartNotAccessibleByOtherUser_throwsNotFound() {
    ChartResponse privateChart = createChart("Private Chart", false, ownerUserId);

    assertThatThrownBy(() -> chartService.getById(privateChart.id(), otherUserId))
        .isInstanceOf(ChartNotFoundException.class);
  }

  /** 예외: 존재하지 않는 chartId 조회 시 ChartNotFoundException */
  @Test
  void getById_nonExistentChart_throwsNotFound() {
    assertThatThrownBy(() -> chartService.getById(999999L, ownerUserId))
        .isInstanceOf(ChartNotFoundException.class);
  }

  // =========================================================================
  // Update
  // =========================================================================

  /** 정상: 소유자는 자신의 차트를 수정 가능 */
  @Test
  void update_ownerCanUpdateChart_success() {
    ChartResponse created = createChart("Original Chart", false, ownerUserId);

    ChartResponse updated =
        chartService.update(
            created.id(),
            new UpdateChartRequest("Renamed Chart", "new description", null, null, null),
            ownerUserId);

    assertThat(updated.name()).isEqualTo("Renamed Chart");
    assertThat(updated.description()).isEqualTo("new description");
    assertThat(updated.chartType()).isEqualTo("BAR"); // 변경하지 않았으므로 유지
  }

  /** 예외: 다른 사용자는 차트를 수정할 수 없음 */
  @Test
  void update_nonOwnerCannotUpdate_throwsNotFound() {
    ChartResponse created = createChart("Not Mine Chart", true, ownerUserId);

    assertThatThrownBy(
            () ->
                chartService.update(
                    created.id(),
                    new UpdateChartRequest("Hacked", null, null, null, null),
                    otherUserId))
        .isInstanceOf(ChartNotFoundException.class);
  }

  /** 예외: MAP 타입으로 변경 시 spatialColumn 누락이면 IllegalArgumentException */
  @Test
  void update_changeToMapChart_withoutSpatialColumn_throwsIllegalArgument() {
    ChartResponse created = createChart("Bar To Map", false, ownerUserId);

    assertThatThrownBy(
            () ->
                chartService.update(
                    created.id(),
                    new UpdateChartRequest(null, null, "MAP", Map.of("color", "#123456"), null),
                    ownerUserId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("spatialColumn");
  }

  /** 정상: MAP 타입으로 변경 시 spatialColumn이 있으면 수정 성공 */
  @Test
  void update_changeToMapChart_withSpatialColumn_success() {
    ChartResponse created = createChart("Bar To Map OK", false, ownerUserId);

    ChartResponse updated =
        chartService.update(
            created.id(),
            new UpdateChartRequest(
                null, null, "MAP", Map.of("spatialColumn", "geom", "color", "#123456"), null),
            ownerUserId);

    assertThat(updated.chartType()).isEqualTo("MAP");
    assertThat(updated.config()).containsKey("spatialColumn");
  }

  // =========================================================================
  // Delete
  // =========================================================================

  /** 정상: 소유자는 자신의 차트를 삭제 가능 */
  @Test
  void delete_ownerCanDeleteChart_success() {
    ChartResponse created = createChart("To Delete Chart", false, ownerUserId);
    Long id = created.id();

    chartService.delete(id, ownerUserId);

    assertThatThrownBy(() -> chartService.getById(id, ownerUserId))
        .isInstanceOf(ChartNotFoundException.class);
  }

  /** 예외: 다른 사용자는 차트를 삭제할 수 없음 */
  @Test
  void delete_nonOwnerCannotDelete_throwsNotFound() {
    ChartResponse created = createChart("Not Mine To Delete", true, ownerUserId);

    assertThatThrownBy(() -> chartService.delete(created.id(), otherUserId))
        .isInstanceOf(ChartNotFoundException.class);
  }

  // =========================================================================
  // List
  // =========================================================================

  /** 정상: 자신의 차트와 공유 차트 목록을 반환한다 */
  @Test
  void list_showsOwnAndSharedCharts() {
    createChart("My Private", false, ownerUserId);
    createChart("Other Shared", true, otherUserId);

    // 두 번째 차트는 otherUserId 소유이므로 otherUserId의 savedQuery 접근이 필요함
    // 위 createChart는 ownerUserId 기준 savedQueryId를 사용하므로 직접 삽입
    Long sharedChartId =
        dsl.insertInto(DSL.table(DSL.name("chart")))
            .set(DSL.field(DSL.name("chart", "name"), String.class), "Other Shared Chart")
            .set(DSL.field(DSL.name("chart", "saved_query_id"), Long.class), savedQueryId)
            .set(DSL.field(DSL.name("chart", "chart_type"), String.class), "LINE")
            .set(DSL.field(DSL.name("chart", "is_shared"), Boolean.class), true)
            .set(DSL.field(DSL.name("chart", "created_by"), Long.class), otherUserId)
            .returning(DSL.field(DSL.name("chart", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("chart", "id"), Long.class));

    PageResponse<ChartResponse> result =
        chartService.list(null, null, null, ownerUserId, 0, 10);

    List<String> names = result.content().stream().map(ChartResponse::name).toList();
    assertThat(names).contains("My Private", "Other Shared Chart");
  }

  /** 정상: chartType 필터로 특정 타입 차트만 반환 */
  @Test
  void list_filterByChartType_returnsOnlyMatchingType() {
    createChart("Bar Chart 1", false, ownerUserId);

    // LINE 차트 직접 삽입
    dsl.insertInto(DSL.table(DSL.name("chart")))
        .set(DSL.field(DSL.name("chart", "name"), String.class), "Line Chart 1")
        .set(DSL.field(DSL.name("chart", "saved_query_id"), Long.class), savedQueryId)
        .set(DSL.field(DSL.name("chart", "chart_type"), String.class), "LINE")
        .set(DSL.field(DSL.name("chart", "is_shared"), Boolean.class), false)
        .set(DSL.field(DSL.name("chart", "created_by"), Long.class), ownerUserId)
        .execute();

    PageResponse<ChartResponse> result =
        chartService.list(null, "BAR", null, ownerUserId, 0, 10);

    assertThat(result.content()).allMatch(c -> "BAR".equals(c.chartType()));
    List<String> names = result.content().stream().map(ChartResponse::name).toList();
    assertThat(names).contains("Bar Chart 1");
    assertThat(names).doesNotContain("Line Chart 1");
  }

  /** 정상: 검색어 필터로 이름 일치 차트만 반환 */
  @Test
  void list_filterBySearch_returnsMatchingCharts() {
    createChart("Alpha Chart", false, ownerUserId);
    createChart("Beta Chart", false, ownerUserId);

    PageResponse<ChartResponse> result =
        chartService.list("Alpha", null, null, ownerUserId, 0, 10);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).name()).isEqualTo("Alpha Chart");
  }

  // =========================================================================
  // GetChartData
  // =========================================================================

  /** 정상: getChartData는 차트 정보와 쿼리 실행 결과를 함께 반환 */
  @Test
  void getChartData_returnsChartAndQueryResult() {
    ChartResponse chart = createChart("Data Chart", false, ownerUserId);

    ChartDataResponse data = chartService.getChartData(chart.id(), ownerUserId);

    assertThat(data.chart().id()).isEqualTo(chart.id());
    assertThat(data.chart().name()).isEqualTo("Data Chart");
    // SELECT 1 AS value 쿼리 실행 결과 검증
    assertThat(data.queryResult()).isNotNull();
    assertThat(data.queryResult().columns()).isNotNull();
  }

  /** 예외: 접근 불가 차트의 데이터 조회 시 ChartNotFoundException */
  @Test
  void getChartData_inaccessibleChart_throwsNotFound() {
    ChartResponse privateChart = createChart("Private Data Chart", false, ownerUserId);

    assertThatThrownBy(() -> chartService.getChartData(privateChart.id(), otherUserId))
        .isInstanceOf(ChartNotFoundException.class);
  }
}
