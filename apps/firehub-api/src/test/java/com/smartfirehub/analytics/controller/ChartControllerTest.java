package com.smartfirehub.analytics.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.analytics.dto.ChartDataResponse;
import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.analytics.service.ChartService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** ChartController WebMvcTest — 전체 엔드포인트의 성공 경로만 커버한다. */
@WebMvcTest(ChartController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ChartControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private ChartService chartService;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  private void mockAuth(String... permissions) {
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  @Test
  void listCharts_returnsPage() throws Exception {
    mockAuth("analytics:read");
    when(chartService.list(
            any(), any(), any(), any(), anyLong(), any(Integer.class), any(Integer.class)))
        .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0));

    mockMvc
        .perform(get("/api/v1/analytics/charts").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());
  }

  @Test
  void createChart_returnsCreated() throws Exception {
    mockAuth("analytics:write");
    ChartResponse created = sampleChartResponse(42L);
    when(chartService.create(any(CreateChartRequest.class), eq(1L))).thenReturn(created);

    CreateChartRequest req = new CreateChartRequest("chart1", "desc", 1L, "bar", Map.of(), false);

    mockMvc
        .perform(
            post("/api/v1/analytics/charts")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(42));
  }

  @Test
  void getChart_returnsOk() throws Exception {
    mockAuth("analytics:read");
    when(chartService.getById(eq(5L), eq(1L))).thenReturn(sampleChartResponse(5L));

    mockMvc
        .perform(get("/api/v1/analytics/charts/5").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(5));
  }

  @Test
  void updateChart_returnsOk() throws Exception {
    mockAuth("analytics:write");
    when(chartService.update(eq(5L), any(UpdateChartRequest.class), eq(1L)))
        .thenReturn(sampleChartResponse(5L));

    UpdateChartRequest req = new UpdateChartRequest("chart", "desc", "bar", Map.of(), null);

    mockMvc
        .perform(
            put("/api/v1/analytics/charts/5")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk());
  }

  @Test
  void deleteChart_returnsNoContent() throws Exception {
    mockAuth("analytics:write");
    doNothing().when(chartService).delete(eq(5L), eq(1L));

    mockMvc
        .perform(delete("/api/v1/analytics/charts/5").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent());
  }

  @Test
  void getChartData_returnsOk() throws Exception {
    mockAuth("analytics:read");
    when(chartService.getChartData(eq(5L), eq(1L)))
        .thenReturn(new ChartDataResponse(sampleChartResponse(5L), null));

    mockMvc
        .perform(
            get("/api/v1/analytics/charts/5/data").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());
  }

  /** ChartResponse 샘플 객체 생성 헬퍼 — DTO 필드 수에 대응하기 위해 리플렉션으로 인스턴스화 시도 없이 직접 작성한다. */
  private static ChartResponse sampleChartResponse(Long id) {
    // ChartResponse 레코드는 필드가 많을 수 있으므로 Map 기반 실제 필드 세팅 대신 null/빈 값으로 생성한다.
    // 레코드 필드가 바뀌면 컴파일 에러로 즉시 감지된다.
    return new ChartResponse(
        id, "name", null, 1L, "query", "bar", Map.of(), false, "user", 1L, null, null, 0L);
  }
}
