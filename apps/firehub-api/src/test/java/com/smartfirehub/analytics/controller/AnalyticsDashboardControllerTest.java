package com.smartfirehub.analytics.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.*;
import com.smartfirehub.analytics.exception.WidgetNotFoundException;
import com.smartfirehub.analytics.service.AnalyticsDashboardService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** AnalyticsDashboardController WebMvc 슬라이스 테스트 — 인증/권한 체계 및 HTTP 응답 코드 검증. */
@SuppressWarnings("null")
@WebMvcTest(AnalyticsDashboardController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AnalyticsDashboardControllerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private AnalyticsDashboardService dashboardService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("test-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("analytics:read", "analytics:write"));
  }

  /** 대시보드 단건 응답 샘플 생성 헬퍼. */
  private DashboardResponse sampleDashboard() {
    return new DashboardResponse(
        1L,
        "테스트 대시보드",
        "설명",
        false,
        null,
        List.of(),
        0,
        "홍길동",
        1L,
        LocalDateTime.now(),
        LocalDateTime.now());
  }

  // ── GET /api/v1/analytics/dashboards ───────────────────────────────────────

  @Test
  void listDashboards_withPermission_returnsPage() throws Exception {
    PageResponse<DashboardResponse> page =
        new PageResponse<>(List.of(sampleDashboard()), 0, 20, 1L, 1);
    when(dashboardService.list(any(), any(), any(), anyLong(), anyInt(), anyInt()))
        .thenReturn(page);

    mockMvc
        .perform(get("/api/v1/analytics/dashboards").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(1))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  /** WD-328: 「내 대시보드」 탭의 mineOnly=true 가 서비스까지 전달되고, sharedOnly 는 비어 있다. */
  @Test
  void listDashboards_mineOnlyParam_passedToService() throws Exception {
    when(dashboardService.list(any(), any(), any(), anyLong(), anyInt(), anyInt()))
        .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0));

    mockMvc
        .perform(
            get("/api/v1/analytics/dashboards")
                .param("mineOnly", "true")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());

    verify(dashboardService).list(isNull(), isNull(), eq(true), eq(1L), eq(0), eq(20));
  }

  @Test
  void listDashboards_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/analytics/dashboards")).andExpect(status().isUnauthorized());
  }

  // ── POST /api/v1/analytics/dashboards ──────────────────────────────────────

  @Test
  void createDashboard_withPermission_returnsCreated() throws Exception {
    CreateDashboardRequest request = new CreateDashboardRequest("테스트 대시보드", "설명", false, null);
    when(dashboardService.create(any(CreateDashboardRequest.class), anyLong()))
        .thenReturn(sampleDashboard());

    mockMvc
        .perform(
            post("/api/v1/analytics/dashboards")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.name").value("테스트 대시보드"));
  }

  @Test
  void createDashboard_withoutAuth_returnsUnauthorized() throws Exception {
    CreateDashboardRequest request = new CreateDashboardRequest("대시보드", null, false, null);

    mockMvc
        .perform(
            post("/api/v1/analytics/dashboards")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isUnauthorized());
  }

  /**
   * WD-327: 자동 새로고침 상한 24시간 — 넘으면 400 과 안내 문구, 서비스는 부르지 않는다. 상한 값 그대로는 허용한다. 상한이 없으면 브라우저 타이머가
   * 넘쳐 위젯 데이터를 쉬지 않고 다시 요청했다.
   */
  @Test
  void createDashboard_autoRefreshOverMax_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/analytics/dashboards")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CreateDashboardRequest("대시보드", null, false, 2_500_000))))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors.autoRefreshSeconds")
                .value(CreateDashboardRequest.AUTO_REFRESH_MAX_MESSAGE));
    verify(dashboardService, never()).create(any(), anyLong());
  }

  @Test
  void createDashboard_autoRefreshAtMax_returnsCreated() throws Exception {
    when(dashboardService.create(any(CreateDashboardRequest.class), anyLong()))
        .thenReturn(sampleDashboard());
    mockMvc
        .perform(
            post("/api/v1/analytics/dashboards")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CreateDashboardRequest("대시보드", null, false, 86_400))))
        .andExpect(status().isCreated());
  }

  // ── GET /api/v1/analytics/dashboards/{id} ──────────────────────────────────

  @Test
  void getDashboard_withPermission_returnsDashboard() throws Exception {
    when(dashboardService.getById(1L, 1L)).thenReturn(sampleDashboard());

    mockMvc
        .perform(get("/api/v1/analytics/dashboards/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.name").value("테스트 대시보드"));
  }

  /**
   * WD-195: 볼 수 없는 차트의 위젯은 JSON 에서 accessible=false, chartName·chartType=null 로 나가고 위치·크기는 그대로다. 웹과
   * ai-agent 가 읽는 필드 이름(accessible)이 레코드 접근자에서 바뀌지 않았는지 직렬화 단에서 고정한다.
   */
  @Test
  void getDashboard_inaccessibleWidget_serializedWithoutChartName() throws Exception {
    DashboardResponse.DashboardWidgetResponse visible =
        new DashboardResponse.DashboardWidgetResponse(10L, 100L, "보이는 차트", "BAR", 0, 0, 6, 4, true);
    DashboardResponse.DashboardWidgetResponse hidden =
        new DashboardResponse.DashboardWidgetResponse(11L, 101L, "비공개 차트", "LINE", 6, 0, 6, 4, true)
            .withheld();
    DashboardResponse base = sampleDashboard();
    DashboardResponse withWidgets =
        new DashboardResponse(
            base.id(),
            base.name(),
            base.description(),
            true,
            null,
            List.of(visible, hidden),
            2,
            base.createdByName(),
            base.createdBy(),
            base.createdAt(),
            base.updatedAt());
    when(dashboardService.getById(1L, 1L)).thenReturn(withWidgets);

    mockMvc
        .perform(get("/api/v1/analytics/dashboards/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.widgets[0].accessible").value(true))
        .andExpect(jsonPath("$.widgets[0].chartName").value("보이는 차트"))
        .andExpect(jsonPath("$.widgets[1].accessible").value(false))
        .andExpect(jsonPath("$.widgets[1].chartName").isEmpty())
        .andExpect(jsonPath("$.widgets[1].chartType").isEmpty())
        .andExpect(jsonPath("$.widgets[1].chartId").value(101))
        .andExpect(jsonPath("$.widgets[1].positionX").value(6))
        .andExpect(jsonPath("$.widgets[1].width").value(6))
        .andExpect(jsonPath("$.widgets[1].height").value(4))
        // 이름이 응답 어디에도 실리지 않는다
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("비공개 차트"))));
  }

  // ── PUT /api/v1/analytics/dashboards/{id} ──────────────────────────────────

  @Test
  void updateDashboard_withPermission_returnsUpdated() throws Exception {
    UpdateDashboardRequest request = new UpdateDashboardRequest("수정된 대시보드", null, null, null, null);
    when(dashboardService.update(anyLong(), any(UpdateDashboardRequest.class), anyLong()))
        .thenReturn(sampleDashboard());

    mockMvc
        .perform(
            put("/api/v1/analytics/dashboards/1")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1));
  }

  /** WD-327: 수정도 같은 상한 — 넘으면 400, 서비스는 부르지 않는다. */
  @Test
  void updateDashboard_autoRefreshOverMax_returns400() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/analytics/dashboards/1")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"autoRefreshSeconds\":86401}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors.autoRefreshSeconds")
                .value(CreateDashboardRequest.AUTO_REFRESH_MAX_MESSAGE));
    verify(dashboardService, never()).update(anyLong(), any(), anyLong());
  }

  // ── DELETE /api/v1/analytics/dashboards/{id} ───────────────────────────────

  @Test
  void deleteDashboard_withPermission_returnsNoContent() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/analytics/dashboards/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    verify(dashboardService).delete(1L, 1L);
  }

  // ── GET /api/v1/analytics/dashboards/{id}/data ─────────────────────────────

  @Test
  void getDashboardData_withPermission_returnsData() throws Exception {
    DashboardDataResponse data = new DashboardDataResponse(sampleDashboard(), List.of());
    when(dashboardService.getDashboardData(1L, 1L)).thenReturn(data);

    mockMvc
        .perform(
            get("/api/v1/analytics/dashboards/1/data").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dashboard.id").value(1));
  }

  // ── POST /api/v1/analytics/dashboards/{id}/widgets ────────────────────────

  @Test
  void addWidget_withPermission_returnsCreated() throws Exception {
    AddWidgetRequest request = new AddWidgetRequest(10L, 0, 0, 6, 4);
    when(dashboardService.addWidget(anyLong(), any(AddWidgetRequest.class), anyLong()))
        .thenReturn(sampleDashboard());

    mockMvc
        .perform(
            post("/api/v1/analytics/dashboards/1/widgets")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(1));
  }

  // ── PUT /api/v1/analytics/dashboards/{id}/widgets/{wId} ───────────────────

  @Test
  void updateWidget_withPermission_returnsUpdated() throws Exception {
    UpdateWidgetRequest request = new UpdateWidgetRequest(1, 1, 8, 6);
    when(dashboardService.updateWidget(
            anyLong(), anyLong(), any(UpdateWidgetRequest.class), anyLong()))
        .thenReturn(sampleDashboard());

    mockMvc
        .perform(
            put("/api/v1/analytics/dashboards/1/widgets/2")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1));
  }

  // ── DELETE /api/v1/analytics/dashboards/{id}/widgets/{wId} ────────────────

  @Test
  void removeWidget_withPermission_returnsNoContent() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/analytics/dashboards/1/widgets/2")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    verify(dashboardService).removeWidget(1L, 2L, 1L);
  }

  // WD-367: 다른 탭·사용자가 먼저 지운 위젯 삭제는 400 이 아니라 404 + 한국어 사유로 답한다 — 웹은 404 를 보고 서버 목록을 다시 읽는다
  @Test
  void removeWidget_alreadyDeleted_returns404WithMessage() throws Exception {
    doThrow(new WidgetNotFoundException()).when(dashboardService).removeWidget(1L, 2L, 1L);

    mockMvc
        .perform(
            delete("/api/v1/analytics/dashboards/1/widgets/2")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value(WidgetNotFoundException.MESSAGE));
  }

  // ── PUT /api/v1/analytics/dashboards/{id}/widgets/layout ──────────────────

  @Test
  void updateWidgetLayout_withPermission_returnsNoContent() throws Exception {
    UpdateWidgetLayoutRequest request =
        new UpdateWidgetLayoutRequest(
            List.of(new UpdateWidgetLayoutRequest.WidgetPosition(2L, 0, 0, 6, 4)));

    mockMvc
        .perform(
            put("/api/v1/analytics/dashboards/1/widgets/layout")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    verify(dashboardService)
        .updateWidgetLayout(anyLong(), any(UpdateWidgetLayoutRequest.class), anyLong());
  }

  // ── WD-193: 배치 저장 요청 검증 (null·null 원소·범위 밖 → 400, 서비스 미호출) ──────────────

  /** 배치 저장 PUT 요청을 원문 JSON 본문으로 보낸다. */
  private org.springframework.test.web.servlet.ResultActions putLayout(String body)
      throws Exception {
    return mockMvc.perform(
        put("/api/v1/analytics/dashboards/1/widgets/layout")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  @Test
  void updateWidgetLayout_nullWidgets_returns400() throws Exception {
    putLayout("{\"widgets\":null}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Validation failed"))
        .andExpect(jsonPath("$.errors.widgets").value("위젯 배치 목록(widgets)은 필수입니다."));
    verify(dashboardService, never()).updateWidgetLayout(anyLong(), any(), anyLong());
  }

  @Test
  void updateWidgetLayout_nullElement_returns400() throws Exception {
    putLayout("{\"widgets\":[null]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors['widgets[0]']").value("위젯 배치 항목은 비어 있을 수 없습니다."));
    verify(dashboardService, never()).updateWidgetLayout(anyLong(), any(), anyLong());
  }

  @Test
  void updateWidgetLayout_outOfRangeValues_returns400WithFieldErrors() throws Exception {
    // 탐색 재현값(너비 0·높이 99·음수 좌표)과 열 좌표 상한 초과(12)
    putLayout(
            "{\"widgets\":[{\"widgetId\":2,\"positionX\":-5,\"positionY\":-5,\"width\":0,\"height\":99},"
                + "{\"widgetId\":3,\"positionX\":12,\"positionY\":241,\"width\":13,\"height\":0}]}")
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors['widgets[0].positionX']").value("열 위치(positionX)는 0 이상이어야 합니다."))
        .andExpect(
            jsonPath("$.errors['widgets[0].positionY']").value("행 위치(positionY)는 0 이상이어야 합니다."))
        .andExpect(jsonPath("$.errors['widgets[0].width']").value("너비(width)는 1 이상이어야 합니다."))
        .andExpect(jsonPath("$.errors['widgets[0].height']").value("높이(height)는 12 이하여야 합니다."))
        .andExpect(
            jsonPath("$.errors['widgets[1].positionX']").value("열 위치(positionX)는 11 이하여야 합니다."))
        .andExpect(
            jsonPath("$.errors['widgets[1].positionY']").value("행 위치(positionY)는 240 이하여야 합니다."))
        .andExpect(jsonPath("$.errors['widgets[1].width']").value("너비(width)는 12 이하여야 합니다."))
        .andExpect(jsonPath("$.errors['widgets[1].height']").value("높이(height)는 1 이상이어야 합니다."));
    verify(dashboardService, never()).updateWidgetLayout(anyLong(), any(), anyLong());
  }

  @Test
  void updateWidgetLayout_missingWidgetId_returns400() throws Exception {
    putLayout("{\"widgets\":[{\"positionX\":0,\"positionY\":0,\"width\":6,\"height\":4}]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors['widgets[0].widgetId']").value("위젯 ID(widgetId)는 필수입니다."));
    verify(dashboardService, never()).updateWidgetLayout(anyLong(), any(), anyLong());
  }

  @Test
  void updateWidgetLayout_boundaryValues_returnsNoContent() throws Exception {
    // 경계값(열 11·행 240·크기 1과 12)은 허용된다
    putLayout(
            "{\"widgets\":[{\"widgetId\":2,\"positionX\":11,\"positionY\":240,\"width\":1,\"height\":12},"
                + "{\"widgetId\":3,\"positionX\":0,\"positionY\":0,\"width\":12,\"height\":1}]}")
        .andExpect(status().isNoContent());
    verify(dashboardService)
        .updateWidgetLayout(eq(1L), any(UpdateWidgetLayoutRequest.class), anyLong());
  }

  @Test
  void updateWidgetLayout_emptyList_returnsNoContent() throws Exception {
    putLayout("{\"widgets\":[]}").andExpect(status().isNoContent());
    verify(dashboardService)
        .updateWidgetLayout(eq(1L), any(UpdateWidgetLayoutRequest.class), anyLong());
  }

  /** WD-193: 목록 길이는 대시보드 위젯 상한(20)까지 — 21개면 400 이고 서비스(소속 확인 IN 조회)까지 가지 않는다. */
  @Test
  void updateWidgetLayout_tooManyWidgets_returns400() throws Exception {
    String items =
        java.util.stream.IntStream.rangeClosed(1, 21)
            .mapToObj(
                i ->
                    "{\"widgetId\":"
                        + i
                        + ",\"positionX\":0,\"positionY\":0,\"width\":1,\"height\":1}")
            .collect(java.util.stream.Collectors.joining(","));
    putLayout("{\"widgets\":[" + items + "]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.widgets").value("위젯 배치는 한 번에 20개까지 저장할 수 있습니다."));
    verify(dashboardService, never()).updateWidgetLayout(anyLong(), any(), anyLong());
  }

  /** WD-193: 단건 수정도 같은 범위 규칙을 쓴다 — 음수 좌표는 400. */
  @Test
  void updateWidget_outOfRangePosition_returns400() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/analytics/dashboards/1/widgets/2")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"positionX\":-1,\"positionY\":241}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.positionX").value("열 위치(positionX)는 0 이상이어야 합니다."))
        .andExpect(jsonPath("$.errors.positionY").value("행 위치(positionY)는 240 이하여야 합니다."));
    verify(dashboardService, never()).updateWidget(anyLong(), anyLong(), any(), anyLong());
  }

  // ── WD-425: 대시보드 설명 길이 상한 ──────────────────────────────────────────

  /** WD-425: 설명이 정확히 2,000자(한글)면 생성된다. */
  @Test
  void createDashboard_descriptionAtLimit_returnsCreated() throws Exception {
    when(dashboardService.create(any(CreateDashboardRequest.class), anyLong()))
        .thenReturn(sampleDashboard());
    CreateDashboardRequest request =
        new CreateDashboardRequest(
            "대시보드", "가".repeat(CreateDashboardRequest.DESCRIPTION_MAX_LENGTH), false, null);

    mockMvc
        .perform(
            post("/api/v1/analytics/dashboards")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated());
  }

  /** WD-425: 설명이 2,000자를 넘으면 400 + 한국어 사유, 서비스는 호출되지 않는다. */
  @Test
  void createDashboard_descriptionOverLimit_returns400() throws Exception {
    CreateDashboardRequest request =
        new CreateDashboardRequest(
            "대시보드", "가".repeat(CreateDashboardRequest.DESCRIPTION_MAX_LENGTH + 1), false, null);

    mockMvc
        .perform(
            post("/api/v1/analytics/dashboards")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.description").value("대시보드 설명은 최대 2,000자까지 입력할 수 있습니다."));
    verify(dashboardService, never()).create(any(), anyLong());
  }

  /** WD-425: 설정 수정도 같은 상한 — 넘으면 400, 정확히 2,000자면 200. */
  @Test
  void updateDashboard_descriptionOverLimit_returns400_atLimit_returnsOk() throws Exception {
    when(dashboardService.update(anyLong(), any(UpdateDashboardRequest.class), anyLong()))
        .thenReturn(sampleDashboard());

    UpdateDashboardRequest over =
        new UpdateDashboardRequest(
            null, "가".repeat(CreateDashboardRequest.DESCRIPTION_MAX_LENGTH + 1), null, null, null);
    mockMvc
        .perform(
            put("/api/v1/analytics/dashboards/1")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(over)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.description").value("대시보드 설명은 최대 2,000자까지 입력할 수 있습니다."));
    verify(dashboardService, never()).update(anyLong(), any(), anyLong());

    UpdateDashboardRequest atLimit =
        new UpdateDashboardRequest(
            null, "가".repeat(CreateDashboardRequest.DESCRIPTION_MAX_LENGTH), null, null, null);
    mockMvc
        .perform(
            put("/api/v1/analytics/dashboards/1")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(atLimit)))
        .andExpect(status().isOk());
  }
}
