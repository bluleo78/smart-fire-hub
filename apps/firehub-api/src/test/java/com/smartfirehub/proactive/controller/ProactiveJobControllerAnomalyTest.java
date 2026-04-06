package com.smartfirehub.proactive.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.proactive.repository.AnomalyEventRepository;
import com.smartfirehub.proactive.service.PdfExportService;
import com.smartfirehub.proactive.service.ProactiveJobService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ProactiveJobController의 이상 탐지 이벤트 조회 엔드포인트를 검증하는 WebMvcTest.
 *
 * <p>GET /api/v1/proactive/jobs/{id}/anomaly-events 가 서비스 레이어의 결과를 올바른 HTTP 응답으로 전환하는지 확인한다.
 */
@WebMvcTest(ProactiveJobController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ProactiveJobControllerAnomalyTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private ProactiveJobService proactiveJobService;
  @MockitoBean private PdfExportService pdfExportService;

  // Spring Security / JWT 관련 의존성 mock
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  /**
   * 각 테스트에서 공통으로 사용할 JWT 인증 및 권한 스텁을 설정한다. "test-token" Bearer 토큰이 userId=1L 로 인증되고
   * proactive:read 권한을 포함한다.
   */
  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("proactive:read", "proactive:write"));
  }

  /**
   * 이상 탐지 이벤트가 존재할 때 GET 요청이 200 OK와 함께 이벤트 목록을 반환하는지 검증한다.
   *
   * <p>서비스가 이벤트 1건을 반환하면 응답 JSON 배열의 길이가 1이고 metricId/sensitivity 필드가 올바르게 직렬화되어야 한다.
   */
  @Test
  void getAnomalyEvents_returns_200_with_event_list() throws Exception {
    // Given: 서비스가 이벤트 1건을 반환하도록 stub
    var record =
        new AnomalyEventRepository.AnomalyEventRecord(
            1L,
            42L,
            "pipeline_failure_rate",
            "파이프라인 실패율",
            45.5,
            12.3,
            5.2,
            6.38,
            "medium",
            LocalDateTime.of(2026, 4, 6, 9, 0));
    when(proactiveJobService.getAnomalyEvents(anyLong(), anyInt())).thenReturn(List.of(record));

    // When & Then: 엔드포인트 호출 시 200 OK와 이벤트 목록이 반환되어야 한다
    mockMvc
        .perform(get("/api/v1/proactive/jobs/42/anomaly-events").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].metricId").value("pipeline_failure_rate"))
        .andExpect(jsonPath("$[0].sensitivity").value("medium"))
        .andExpect(jsonPath("$[0].deviation").value(6.38));
  }

  /**
   * 이상 탐지 이벤트가 없을 때 GET 요청이 200 OK와 빈 배열을 반환하는지 검증한다.
   */
  @Test
  void getAnomalyEvents_returns_empty_list_when_none() throws Exception {
    // Given: 서비스가 빈 목록을 반환하도록 stub
    when(proactiveJobService.getAnomalyEvents(anyLong(), anyInt())).thenReturn(List.of());

    // When & Then: 200 OK + 빈 배열
    mockMvc
        .perform(get("/api/v1/proactive/jobs/99/anomaly-events").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /**
   * limit 쿼리 파라미터를 지정하면 서비스에 해당 값이 전달되는지 검증한다.
   *
   * <p>limit=5 를 전달하면 서비스 메서드가 (jobId=1, limit=5) 인자로 호출되어야 한다.
   */
  @Test
  void getAnomalyEvents_passes_limit_param_to_service() throws Exception {
    // Given
    when(proactiveJobService.getAnomalyEvents(1L, 5)).thenReturn(List.of());

    // When & Then: limit=5 파라미터가 올바르게 전달되면 200 OK
    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/1/anomaly-events")
                .param("limit", "5")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());

    // verify 서비스가 limit=5 로 호출됨
    org.mockito.Mockito.verify(proactiveJobService).getAnomalyEvents(1L, 5);
  }

  /**
   * 인증 토큰 없이 요청하면 접근 거부 응답이 반환되는지 검증한다.
   *
   * <p>이 프로젝트의 Spring Security 설정은 인증되지 않은 요청에 403 Forbidden 을 반환한다.
   */
  @Test
  void getAnomalyEvents_without_auth_returns_403() throws Exception {
    mockMvc
        .perform(get("/api/v1/proactive/jobs/1/anomaly-events"))
        .andExpect(status().isForbidden());
  }
}
