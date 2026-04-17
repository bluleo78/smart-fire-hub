package com.smartfirehub.analytics.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.*;
import com.smartfirehub.analytics.service.AnalyticsDashboardService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import java.time.LocalDateTime;
import java.util.List;
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

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private AnalyticsDashboardService dashboardService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
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
    when(dashboardService.list(any(), anyLong(), anyInt(), anyInt())).thenReturn(page);

    mockMvc
        .perform(
            get("/api/v1/analytics/dashboards").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(1))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void listDashboards_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/analytics/dashboards")).andExpect(status().isUnauthorized());
  }

  // ── POST /api/v1/analytics/dashboards ──────────────────────────────────────

  @Test
  void createDashboard_withPermission_returnsCreated() throws Exception {
    CreateDashboardRequest request =
        new CreateDashboardRequest("테스트 대시보드", "설명", false, null);
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

  // ── GET /api/v1/analytics/dashboards/{id} ──────────────────────────────────

  @Test
  void getDashboard_withPermission_returnsDashboard() throws Exception {
    when(dashboardService.getById(1L, 1L)).thenReturn(sampleDashboard());

    mockMvc
        .perform(
            get("/api/v1/analytics/dashboards/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.name").value("테스트 대시보드"));
  }

  // ── PUT /api/v1/analytics/dashboards/{id} ──────────────────────────────────

  @Test
  void updateDashboard_withPermission_returnsUpdated() throws Exception {
    UpdateDashboardRequest request =
        new UpdateDashboardRequest("수정된 대시보드", null, null, null);
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
            get("/api/v1/analytics/dashboards/1/data")
                .header("Authorization", "Bearer test-token"))
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
    when(dashboardService.updateWidget(anyLong(), anyLong(), any(UpdateWidgetRequest.class), anyLong()))
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

    verify(dashboardService).updateWidgetLayout(anyLong(), any(UpdateWidgetLayoutRequest.class), anyLong());
  }
}
