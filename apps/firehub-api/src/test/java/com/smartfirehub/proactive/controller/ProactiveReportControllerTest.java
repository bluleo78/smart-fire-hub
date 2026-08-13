package com.smartfirehub.proactive.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.proactive.dto.ReportListItemResponse;
import com.smartfirehub.proactive.service.ProactiveJobService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ProactiveReportController WebMvcTest.
 *
 * <p>인증 주체의 userId 전달, 권한 게이트, 응답 형태를 검증한다. 소유권 스코핑은 리포지토리 쿼리의 책임이므로
 * ProactiveJobExecutionRepositoryTest 가 담당한다.
 */
@WebMvcTest(ProactiveReportController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ProactiveReportControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProactiveJobService proactiveJobService;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  /** 인증 mock — 유효 토큰 + 주어진 권한 세트를 PermissionInterceptor가 허용하도록 세팅한다. */
  private void mockAuth(String... permissions) {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null)));
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  private ReportListItemResponse sampleReport() {
    return new ReportListItemResponse(
        11L, 3L, "월간 화재 통계", "8월 리포트", "요약문", LocalDateTime.of(2026, 8, 10, 9, 0));
  }

  @Test
  void getReports_returnsReportsForAuthenticatedUser() throws Exception {
    mockAuth("proactive:read");
    // 인증 주체 userId(1)와 기본 limit/offset 이 그대로 서비스에 전달되어야 한다
    when(proactiveJobService.getReports(1L, 20, 0)).thenReturn(List.of(sampleReport()));

    mockMvc
        .perform(get("/api/v1/proactive/reports").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].executionId").value(11))
        .andExpect(jsonPath("$[0].jobId").value(3))
        .andExpect(jsonPath("$[0].jobName").value("월간 화재 통계"))
        .andExpect(jsonPath("$[0].title").value("8월 리포트"));
  }

  @Test
  void getReports_passesLimitAndOffsetToService() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.getReports(1L, 5, 10)).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/proactive/reports?limit=5&offset=10")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());
    // Mockito 기본 응답(빈 리스트)과 스텁 응답이 같아 status만으로는 limit/offset 오배선을 못 잡는다 —
    // 정확한 인자로 서비스가 호출됐는지 직접 검증한다
    verify(proactiveJobService).getReports(1L, 5, 10);
  }

  @Test
  void getReports_clampsNegativeAndOversizedParams() throws Exception {
    mockAuth("proactive:read");

    // 음수 limit 은 그대로 흘리면 Postgres 에서 "LIMIT must not be negative" 로 500 이 된다
    mockMvc
        .perform(
            get("/api/v1/proactive/reports?limit=-1&offset=-5")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());
    verify(proactiveJobService).getReports(1L, 1, 0);

    // 과대 limit 은 상한(100)으로 잘라 전량 조회를 막는다
    mockMvc
        .perform(
            get("/api/v1/proactive/reports?limit=100000")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());
    verify(proactiveJobService).getReports(1L, 100, 0);
  }

  @Test
  void getReports_responseDoesNotContainHtmlContent() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.getReports(1L, 20, 0)).thenReturn(List.of(sampleReport()));

    // 목록 응답에 수십 KB 본문이 실리면 안 된다 — DTO에 필드 자체가 없어야 한다
    mockMvc
        .perform(get("/api/v1/proactive/reports").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("htmlContent"))));
  }

  @Test
  void getReports_withoutToken_isUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/proactive/reports")).andExpect(status().isUnauthorized());
  }
}
