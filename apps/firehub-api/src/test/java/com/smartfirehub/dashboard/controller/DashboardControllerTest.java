package com.smartfirehub.dashboard.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartfirehub.dashboard.dto.ActivityFeedResponse;
import com.smartfirehub.dashboard.dto.AttentionItemResponse;
import com.smartfirehub.dashboard.dto.DashboardStatsResponse;
import com.smartfirehub.dashboard.dto.SystemHealthResponse;
import com.smartfirehub.dashboard.service.DashboardService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** DashboardController WebMvcTest — 대시보드 통계/상태/활동 피드 엔드포인트 검증 */
@SuppressWarnings("null")
@WebMvcTest(DashboardController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DashboardControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private DashboardService dashboardService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
  }

  /** GET /stats — 인증 성공 시 대시보드 통계 반환 */
  @Test
  void getStats_withPermission_returnsOk() throws Exception {
    DashboardStatsResponse stats =
        new DashboardStatsResponse(10L, 5L, 5L, 3L, 2L, List.of(), List.of());
    when(dashboardService.getStats()).thenReturn(stats);

    mockMvc
        .perform(get("/api/v1/dashboard/stats").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalDatasets").value(10));
  }

  /** GET /stats — 인증 없으면 401 */
  @Test
  void getStats_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/dashboard/stats")).andExpect(status().isUnauthorized());
  }

  /** GET /health — 시스템 상태 반환 */
  @Test
  void getSystemHealth_withPermission_returnsOk() throws Exception {
    SystemHealthResponse health = new SystemHealthResponse(
        new SystemHealthResponse.PipelineHealth(5, 4, 1, 0, 0),
        new SystemHealthResponse.DatasetHealth(10, 8, 1, 1));
    when(dashboardService.getSystemHealth()).thenReturn(health);

    mockMvc
        .perform(get("/api/v1/dashboard/health").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pipelineHealth.total").value(5));
  }

  /** GET /attention — 주목 필요 항목 목록 반환 */
  @Test
  void getAttentionItems_withPermission_returnsOk() throws Exception {
    when(dashboardService.getAttentionItems()).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/dashboard/attention").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }

  /** GET /activity — 활동 피드 반환 */
  @Test
  void getActivityFeed_withPermission_returnsOk() throws Exception {
    ActivityFeedResponse feed = new ActivityFeedResponse(List.of(), 0, false);
    when(dashboardService.getActivityFeed(null, null, 0, 20)).thenReturn(feed);

    mockMvc
        .perform(get("/api/v1/dashboard/activity").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }
}
