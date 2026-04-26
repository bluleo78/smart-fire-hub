package com.smartfirehub.job.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.job.dto.AsyncJobStatusResponse;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.permission.service.PermissionService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** AsyncJobController WebMvcTest — 비동기 잡 상태/스트림 엔드포인트 검증 */
@SuppressWarnings("null")
@WebMvcTest(AsyncJobController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AsyncJobControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AsyncJobService asyncJobService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  private static final String JOB_ID = "job-uuid-123";

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("data:read"));
  }

  /** GET /{jobId}/status — 인증 성공 시 잡 상태 반환 */
  @Test
  void getJobStatus_withPermission_returnsOk() throws Exception {
    AsyncJobStatusResponse response =
        new AsyncJobStatusResponse(
            JOB_ID,
            "IMPORT",
            "DONE",
            100,
            "완료",
            Map.of(),
            null,
            LocalDateTime.now(),
            LocalDateTime.now(),
            1L);
    when(asyncJobService.getJobStatus(anyString(), anyLong())).thenReturn(response);

    mockMvc
        .perform(
            get("/api/v1/jobs/{jobId}/status", JOB_ID).header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").value(JOB_ID))
        .andExpect(jsonPath("$.progress").value(100));
  }

  /** GET /{jobId}/status — 인증 없으면 401 */
  @Test
  void getJobStatus_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc
        .perform(get("/api/v1/jobs/{jobId}/status", JOB_ID))
        .andExpect(status().isUnauthorized());
  }

  /** GET /{jobId}/progress SSE — 인증 성공 시 SSE 스트림 반환 */
  @Test
  void streamProgress_withPermission_returnsOk() throws Exception {
    when(asyncJobService.subscribe(anyString(), anyLong())).thenReturn(new SseEmitter());

    mockMvc
        .perform(
            get("/api/v1/jobs/{jobId}/progress", JOB_ID)
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }
}
