package com.smartfirehub.proactive.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.RecipientResponse;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
import com.smartfirehub.proactive.service.PdfExportService;
import com.smartfirehub.proactive.service.ProactiveJobService;
import java.time.LocalDateTime;
import java.util.HashMap;
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

/**
 * ProactiveJobController WebMvcTest — JaCoCo LINE 커버리지 보강용. 각 엔드포인트의 성공 경로 + 일부 에러 분기를 커버한다.
 * PermissionInterceptor가 유효 토큰 + 권한 Set을 검증하도록 JwtTokenProvider/PermissionService를 mock 처리한다.
 */
@WebMvcTest(ProactiveJobController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ProactiveJobControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private ProactiveJobService proactiveJobService;
  @MockitoBean private PdfExportService pdfExportService;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  /** 인증 mock — 유효 토큰 + 주어진 권한 세트를 PermissionInterceptor가 허용하도록 세팅한다. */
  private void mockAuth(String... permissions) {
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  private ProactiveJobResponse sampleJob() {
    return new ProactiveJobResponse(
        10L,
        1L,
        null,
        null,
        "daily-report",
        "요약해줘",
        "0 0 9 * * *",
        "Asia/Seoul",
        true,
        new HashMap<>(),
        null,
        null,
        LocalDateTime.now(),
        LocalDateTime.now(),
        null);
  }

  @Test
  void getJobs_returnsList() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.getJobs(1L)).thenReturn(List.of(sampleJob()));

    mockMvc
        .perform(get("/api/v1/proactive/jobs").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(10))
        .andExpect(jsonPath("$[0].name").value("daily-report"));
  }

  @Test
  void getJob_returnsJob() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.getJob(10L, 1L)).thenReturn(sampleJob());

    mockMvc
        .perform(get("/api/v1/proactive/jobs/10").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(10));
  }

  @Test
  void createJob_validBody_returnsCreated() throws Exception {
    mockAuth("proactive:write");
    Map<String, Object> config = new HashMap<>();
    config.put("channels", List.of());
    CreateProactiveJobRequest req =
        new CreateProactiveJobRequest("daily", "요약해줘", null, "0 0 9 * * *", "Asia/Seoul", config);
    when(proactiveJobService.createJob(any(), eq(1L))).thenReturn(sampleJob());

    mockMvc
        .perform(
            post("/api/v1/proactive/jobs")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(10));
  }

  @Test
  void updateJob_withNullConfig_returnsNoContent() throws Exception {
    mockAuth("proactive:write");
    // config=null 분기 — ProactiveConfigParser를 타지 않는 경로
    UpdateProactiveJobRequest req =
        new UpdateProactiveJobRequest("new-name", null, null, null, null, null, null);
    doNothing().when(proactiveJobService).updateJob(eq(10L), any(), eq(1L));

    mockMvc
        .perform(
            put("/api/v1/proactive/jobs/10")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isNoContent());
  }

  @Test
  void updateJob_withConfig_returnsNoContent() throws Exception {
    mockAuth("proactive:write");
    Map<String, Object> config = new HashMap<>();
    config.put("channels", List.of());
    UpdateProactiveJobRequest req =
        new UpdateProactiveJobRequest(null, null, null, null, null, null, config);
    doNothing().when(proactiveJobService).updateJob(eq(10L), any(), eq(1L));

    mockMvc
        .perform(
            put("/api/v1/proactive/jobs/10")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isNoContent());
  }

  @Test
  void deleteJob_returnsNoContent() throws Exception {
    mockAuth("proactive:write");
    doNothing().when(proactiveJobService).deleteJob(10L, 1L);

    mockMvc
        .perform(delete("/api/v1/proactive/jobs/10").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent());
  }

  @Test
  void executeJob_returnsAccepted() throws Exception {
    mockAuth("proactive:write");
    doNothing().when(proactiveJobService).executeJob(10L, 1L);

    mockMvc
        .perform(
            post("/api/v1/proactive/jobs/10/execute").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isAccepted());
  }

  @Test
  void getExecutions_returnsList() throws Exception {
    mockAuth("proactive:read");
    ProactiveJobExecutionResponse exec =
        new ProactiveJobExecutionResponse(
            99L,
            10L,
            "COMPLETED",
            LocalDateTime.now(),
            LocalDateTime.now(),
            null,
            null,
            null,
            LocalDateTime.now());
    when(proactiveJobService.getExecutions(eq(10L), eq(1L), anyInt(), anyInt()))
        .thenReturn(List.of(exec));

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/10/executions")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(99));
  }

  @Test
  void getExecution_whenBelongsToJob_returnsExecution() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.getJob(10L, 1L)).thenReturn(sampleJob());
    ProactiveJobExecutionResponse exec =
        new ProactiveJobExecutionResponse(
            99L,
            10L,
            "COMPLETED",
            LocalDateTime.now(),
            LocalDateTime.now(),
            null,
            null,
            null,
            LocalDateTime.now());
    when(proactiveJobService.getExecution(99L)).thenReturn(exec);

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/10/executions/99")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(99));
  }

  @Test
  void getExecution_whenMismatchedJob_returnsNotFound() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.getJob(10L, 1L)).thenReturn(sampleJob());
    // jobId(10) != execution.jobId(77) 분기 — 404
    ProactiveJobExecutionResponse exec =
        new ProactiveJobExecutionResponse(
            99L,
            77L,
            "COMPLETED",
            LocalDateTime.now(),
            LocalDateTime.now(),
            null,
            null,
            null,
            LocalDateTime.now());
    when(proactiveJobService.getExecution(99L)).thenReturn(exec);

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/10/executions/99")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNotFound());
  }

  @Test
  void downloadExecutionPdf_whenMismatchedJob_returnsBadRequest() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.getJob(10L, 1L)).thenReturn(sampleJob());
    // getValidatedResult 내부에서 jobId 불일치 → null → 400
    ProactiveJobExecutionResponse exec =
        new ProactiveJobExecutionResponse(
            99L,
            77L,
            "COMPLETED",
            LocalDateTime.now(),
            LocalDateTime.now(),
            null,
            null,
            null,
            LocalDateTime.now());
    when(proactiveJobService.getExecution(99L)).thenReturn(exec);

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/10/executions/99/pdf")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void downloadExecutionPdf_whenNotCompleted_returnsBadRequest() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.getJob(10L, 1L)).thenReturn(sampleJob());
    // status != COMPLETED 분기
    ProactiveJobExecutionResponse exec =
        new ProactiveJobExecutionResponse(
            99L, 10L, "RUNNING", LocalDateTime.now(), null, null, null, null, LocalDateTime.now());
    when(proactiveJobService.getExecution(99L)).thenReturn(exec);

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/10/executions/99/pdf")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getExecutionHtml_whenBadValidation_returnsBadRequest() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.getJob(10L, 1L)).thenReturn(sampleJob());
    ProactiveJobExecutionResponse exec =
        new ProactiveJobExecutionResponse(
            99L,
            77L,
            "COMPLETED",
            LocalDateTime.now(),
            LocalDateTime.now(),
            null,
            null,
            null,
            LocalDateTime.now());
    when(proactiveJobService.getExecution(99L)).thenReturn(exec);

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/10/executions/99/html")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getAnomalyEvents_returnsList() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.getAnomalyEvents(anyLong(), anyInt())).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/10/anomaly-events")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());
  }

  @Test
  void searchRecipients_returnsList() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.searchRecipients(""))
        .thenReturn(List.of(new RecipientResponse(1L, "Alice", "alice@example.com")));

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/recipients").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].email").value("alice@example.com"));
  }
}
