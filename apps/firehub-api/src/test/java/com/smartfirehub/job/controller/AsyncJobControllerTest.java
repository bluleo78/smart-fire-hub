package com.smartfirehub.job.controller;

import static org.hamcrest.Matchers.containsString;
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
import com.smartfirehub.job.exception.JobAccessDeniedException;
import com.smartfirehub.job.exception.JobNotFoundException;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.time.LocalDateTime;
import java.util.Map;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** AsyncJobController WebMvcTest — 비동기 잡 상태/스트림 엔드포인트 검증 */
@SuppressWarnings("null")
@WebMvcTest(AsyncJobController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AsyncJobControllerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AsyncJobService asyncJobService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  private static final String JOB_ID = "job-uuid-123";

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("test-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
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

  // ── WD-203: 작업 진행 조회는 기능 권한이 아니라 소유자 확인으로 막는다 ──

  /** data:read 없이 apiconnection 권한만 가진 사용자도 자기가 시작한 작업 상태를 조회한다. */
  @Test
  void getJobStatus_onlyApiConnectionPermissions_returnsOk() throws Exception {
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("apiconnection:read", "apiconnection:write"));
    when(asyncJobService.getJobStatus(anyString(), anyLong()))
        .thenReturn(
            new AsyncJobStatusResponse(
                JOB_ID,
                "API_CONNECTION_REFRESH_ALL",
                "COMPLETED",
                100,
                "Completed",
                Map.of("total", 1, "checked", 1),
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                1L));

    mockMvc
        .perform(
            get("/api/v1/jobs/{jobId}/status", JOB_ID).header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stage").value("COMPLETED"));
  }

  /** 권한이 하나도 없어도(인증만) 자기 작업 진행 SSE 를 구독할 수 있다. */
  @Test
  void streamProgress_withoutAnyPermission_returnsOk() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of());
    when(asyncJobService.subscribe(anyString(), anyLong())).thenReturn(new SseEmitter());

    mockMvc
        .perform(
            get("/api/v1/jobs/{jobId}/progress", JOB_ID)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }

  /** 없는 작업 → 404 + 한국어 문구. */
  @Test
  void getJobStatus_notFound_returns404WithKoreanMessage() throws Exception {
    when(asyncJobService.getJobStatus(anyString(), anyLong()))
        .thenThrow(new JobNotFoundException(JOB_ID));

    mockMvc
        .perform(
            get("/api/v1/jobs/{jobId}/status", JOB_ID).header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value(containsString("작업을 찾을 수 없습니다")));
  }

  /** 다른 사용자의 작업 → 403 + 한국어 문구. */
  @Test
  void getJobStatus_otherUsersJob_returns403() throws Exception {
    when(asyncJobService.getJobStatus(anyString(), anyLong()))
        .thenThrow(new JobAccessDeniedException());

    mockMvc
        .perform(
            get("/api/v1/jobs/{jobId}/status", JOB_ID).header("Authorization", "Bearer test-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(containsString("이 작업을 시작한 사용자만")));
  }

  /**
   * SSE(Accept: text/event-stream) 요청에서 서비스가 던진 404/403 이 내용 협상 실패(406/500)로 바뀌지 않고 그대로 나가야 한다 — 웹
   * 훅이 상태 코드로 재시도 중단을 판단하기 때문이다.
   */
  @Test
  void streamProgress_notFound_returns404ForEventStream() throws Exception {
    when(asyncJobService.subscribe(anyString(), anyLong()))
        .thenThrow(new JobNotFoundException(JOB_ID));

    mockMvc
        .perform(
            get("/api/v1/jobs/{jobId}/progress", JOB_ID)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value(containsString("작업을 찾을 수 없습니다")));
  }

  @Test
  void streamProgress_otherUsersJob_returns403ForEventStream() throws Exception {
    when(asyncJobService.subscribe(anyString(), anyLong()))
        .thenThrow(new JobAccessDeniedException());

    mockMvc
        .perform(
            get("/api/v1/jobs/{jobId}/progress", JOB_ID)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(containsString("이 작업을 시작한 사용자만")));
  }
}
