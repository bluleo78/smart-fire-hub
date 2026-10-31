package com.smartfirehub.proactive.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.smartfirehub.proactive.dto.ProactiveJobExecutionSummaryResponse;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.RecipientResponse;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
import com.smartfirehub.proactive.exception.ProactiveJobAlreadyRunningException;
import com.smartfirehub.proactive.service.PdfExportService;
import com.smartfirehub.proactive.service.ProactiveJobService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private ProactiveJobService proactiveJobService;
  @MockitoBean private PdfExportService pdfExportService;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  /** 인증 mock — 유효 토큰 + 주어진 권한 세트를 PermissionInterceptor가 허용하도록 세팅한다. */
  private void mockAuth(String... permissions) {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
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
        "SCHEDULE",
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
        new CreateProactiveJobRequest(
            "daily", "요약해줘", null, "0 0 9 * * *", "Asia/Seoul", null, "SCHEDULE", config);
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
  void createJob_unsupportedChannel_returnsBadRequest() throws Exception {
    // 지원되지 않는 전달 채널(SMS)이 config.channels에 포함되면 저장 이전에 400으로 거부한다 (#594) —
    // smart-job-manager가 프롬프트 규칙을 우회해 임의 채널을 전달하더라도 서버가 최종 방어선이 된다.
    mockAuth("proactive:write");
    Map<String, Object> config = new HashMap<>();
    config.put("channels", List.of("SMS"));
    CreateProactiveJobRequest req =
        new CreateProactiveJobRequest(
            "daily", "요약해줘", null, "0 0 9 * * *", "Asia/Seoul", null, "SCHEDULE", config);

    mockMvc
        .perform(
            post("/api/v1/proactive/jobs")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateJob_unsupportedChannel_returnsBadRequest() throws Exception {
    mockAuth("proactive:write");
    Map<String, Object> config = new HashMap<>();
    config.put("channels", List.of("SMS"));
    UpdateProactiveJobRequest req =
        new UpdateProactiveJobRequest(null, null, null, null, null, null, null, config);

    mockMvc
        .perform(
            put("/api/v1/proactive/jobs/10")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  /** 수정은 200 + 새 수정 시각 — 웹이 다음 저장의 낙관적 잠금 기준으로 쓴다(WD-364, 예전에는 204). */
  @Test
  void updateJob_withNullConfig_returnsSavedUpdatedAt() throws Exception {
    mockAuth("proactive:write");
    // config=null 분기 — ProactiveConfigParser를 타지 않는 경로
    UpdateProactiveJobRequest req =
        new UpdateProactiveJobRequest("new-name", null, null, null, null, null, null, null);
    when(proactiveJobService.updateJob(eq(10L), any(), eq(1L)))
        .thenReturn(LocalDateTime.of(2026, 10, 30, 10, 0, 0, 123_456_000));

    mockMvc
        .perform(
            put("/api/v1/proactive/jobs/10")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedAt").value("2026-10-30T10:00:00.123456"));
  }

  /** [WD-136] 화면이 보내는 {@code templateId:null, clearTemplate:true} JSON 이 해제 플래그로 서비스에 전달된다. */
  @Test
  void updateJob_clearTemplateJson_passesFlagToService() throws Exception {
    mockAuth("proactive:write");
    when(proactiveJobService.updateJob(eq(10L), any(), eq(1L))).thenReturn(LocalDateTime.now());

    mockMvc
        .perform(
            put("/api/v1/proactive/jobs/10")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"n\",\"templateId\":null,\"clearTemplate\":true}"))
        .andExpect(status().isOk());

    ArgumentCaptor<UpdateProactiveJobRequest> captor =
        ArgumentCaptor.forClass(UpdateProactiveJobRequest.class);
    verify(proactiveJobService).updateJob(eq(10L), captor.capture(), eq(1L));
    assertThat(captor.getValue().templateId()).isNull();
    assertThat(captor.getValue().clearTemplate()).isTrue();
  }

  @Test
  void updateJob_withConfig_returnsOk() throws Exception {
    mockAuth("proactive:write");
    Map<String, Object> config = new HashMap<>();
    config.put("channels", List.of());
    UpdateProactiveJobRequest req =
        new UpdateProactiveJobRequest(null, null, null, null, null, null, null, config);
    when(proactiveJobService.updateJob(eq(10L), any(), eq(1L))).thenReturn(LocalDateTime.now());

    mockMvc
        .perform(
            put("/api/v1/proactive/jobs/10")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk());
  }

  @Test
  void deleteJob_returnsNoContent() throws Exception {
    // WD-192: 내 작업 삭제는 읽기 권한만으로 된다 — 쓰기 권한을 잃은 작성자도 자기 작업을 정리할 수 있어야 한다
    mockAuth("proactive:read");
    doNothing().when(proactiveJobService).deleteJob(10L, 1L);

    mockMvc
        .perform(delete("/api/v1/proactive/jobs/10").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent());
  }

  @Test
  void executeJob_returnsAccepted() throws Exception {
    mockAuth("proactive:write");
    doNothing().when(proactiveJobService).tryAcquireRunSlot(10L);
    doNothing().when(proactiveJobService).executeJob(10L, 1L);

    mockMvc
        .perform(
            post("/api/v1/proactive/jobs/10/execute").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isAccepted());
  }

  /**
   * Job이 이미 실행 중일 때 tryAcquireRunSlot이 ProactiveJobAlreadyRunningException을 던지면 409 Conflict를 반환해야
   * 한다 (#149).
   */
  @Test
  void executeJob_whenAlreadyRunning_returnsConflict() throws Exception {
    mockAuth("proactive:write");
    doThrow(new ProactiveJobAlreadyRunningException())
        .when(proactiveJobService)
        .tryAcquireRunSlot(10L);

    mockMvc
        .perform(
            post("/api/v1/proactive/jobs/10/execute").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isConflict())
        // 영어 용어·내부 번호 없는 한국어 안내와 화면이 구분할 코드(WD-368)
        .andExpect(jsonPath("$.message").value("이미 실행 중인 작업입니다. 끝나면 실행 이력에서 결과를 확인하세요"))
        .andExpect(jsonPath("$.code").value("PROACTIVE_JOB_ALREADY_RUNNING"));
  }

  @Test
  void getExecutions_returnsList() throws Exception {
    mockAuth("proactive:read");
    ProactiveJobExecutionSummaryResponse exec =
        new ProactiveJobExecutionSummaryResponse(
            99L,
            10L,
            "COMPLETED",
            LocalDateTime.now(),
            LocalDateTime.now(),
            null,
            null,
            LocalDateTime.now(),
            null);
    when(proactiveJobService.getExecutions(eq(10L), eq(1L), anyInt(), anyInt()))
        .thenReturn(List.of(exec));

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/10/executions")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(99))
        // 목록 응답은 경량 뷰이므로 리포트 본문(result) 필드 자체가 없어야 한다 (#604)
        .andExpect(jsonPath("$[0].result").doesNotExist());
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
            LocalDateTime.now(),
            null);
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
            LocalDateTime.now(),
            null);
    when(proactiveJobService.getExecution(99L)).thenReturn(exec);

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/10/executions/99")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNotFound());
  }

  /**
   * WD-249 — 한글 제목 리포트 PDF 다운로드에도 내보내기와 같은 Content-Disposition(ASCII 대체 filename + UTF-8
   * filename*)이 실린다. 예전엔 filename* 만 있어 그것을 모르는 클라이언트는 이름을 못 받았다.
   */
  @Test
  void downloadExecutionPdf_koreanTitle_setsAsciiSafeContentDisposition() throws Exception {
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
            Map.of("title", "일일 보고서"),
            null,
            LocalDateTime.now(),
            null);
    when(proactiveJobService.getExecution(99L)).thenReturn(exec);
    when(pdfExportService.generatePdf(any(), eq("daily-report"), any(), any()))
        .thenReturn(new byte[] {1, 2});

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/10/executions/99/pdf")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    "attachment; filename=\"______.pdf\"; filename*=UTF-8''"
                        + "%EC%9D%BC%EC%9D%BC_%EB%B3%B4%EA%B3%A0%EC%84%9C.pdf"));
  }

  /**
   * WD-344 — PDF 머리 시각은 내려받은 순간이 아니라 실행 완료 시각이다. 컨트롤러가 실행의 completedAt(UTC)과 작업 시간대를 PDF 생성에 넘기는지
   * 고정한다(예전엔 PdfExportService 가 매번 LocalDateTime.now() 를 찍었다).
   */
  @Test
  void downloadExecutionPdf_passesExecutionCompletedAtAndJobTimezone() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.getJob(10L, 1L)).thenReturn(sampleJob());
    LocalDateTime completedAt = LocalDateTime.of(2026, 10, 28, 13, 6, 18);
    ProactiveJobExecutionResponse exec =
        new ProactiveJobExecutionResponse(
            99L,
            10L,
            "COMPLETED",
            completedAt.minusMinutes(1),
            completedAt,
            null,
            Map.of("title", "일일 보고서"),
            null,
            completedAt.minusMinutes(1),
            null);
    when(proactiveJobService.getExecution(99L)).thenReturn(exec);
    when(pdfExportService.generatePdf(any(), eq("daily-report"), any(), any()))
        .thenReturn(new byte[] {1, 2});

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/10/executions/99/pdf")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());

    verify(pdfExportService)
        .generatePdf(any(), eq("daily-report"), eq(completedAt), eq("Asia/Seoul"));
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
            LocalDateTime.now(),
            null);
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
            99L,
            10L,
            "RUNNING",
            LocalDateTime.now(),
            null,
            null,
            null,
            null,
            LocalDateTime.now(),
            null);
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
            LocalDateTime.now(),
            null);
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
    // 소유권 검증 — 정상 소유자 케이스 (#150)
    when(proactiveJobService.getJob(10L, 1L)).thenReturn(sampleJob());
    when(proactiveJobService.getAnomalyEvents(anyLong(), anyInt())).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/10/anomaly-events")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());
  }

  @Test
  void getAnomalyEvents_nonOwner_returnsNotFound() throws Exception {
    // #150: 다른 사용자의 Job ID로 anomaly-events 조회 시 404 — 소유권 검증으로 정보 누출 차단
    mockAuth("proactive:read");
    when(proactiveJobService.getJob(10L, 1L))
        .thenThrow(
            new com.smartfirehub.proactive.exception.ProactiveJobNotFoundException(
                "Proactive Job을 찾을 수 없습니다: 10"));

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/10/anomaly-events")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNotFound());
    // getAnomalyEvents가 호출되지 않았는지 확인 — 소유권 검증 실패 시 데이터 조회 자체가 발생하면 안 됨
    org.mockito.Mockito.verify(proactiveJobService, org.mockito.Mockito.never())
        .getAnomalyEvents(anyLong(), anyInt());
  }

  @Test
  void searchRecipients_returnsList() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.searchRecipients("", null))
        .thenReturn(List.of(new RecipientResponse(1L, "Alice", "alice@example.com")));

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/recipients").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].email").value("alice@example.com"));
  }

  // 회귀 테스트 (#555): userIds로 조회하면 검색어 없이도 해당 ID들의 수신자 정보를 그대로 반환한다 —
  // UserCombobox가 저장된 selectedUserIds를 마운트 시 하이드레이션할 때 쓰는 경로.
  @Test
  void searchRecipients_withUserIds_returnsThoseUsersIgnoringSearch() throws Exception {
    mockAuth("proactive:read");
    when(proactiveJobService.searchRecipients("", List.of(1L, 2L)))
        .thenReturn(
            List.of(
                new RecipientResponse(1L, "Alice", "alice@example.com"),
                new RecipientResponse(2L, "Bob", "bob@example.com")));

    mockMvc
        .perform(
            get("/api/v1/proactive/jobs/recipients?userIds=1&userIds=2")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].email").value("alice@example.com"))
        .andExpect(jsonPath("$[1].email").value("bob@example.com"));
  }
}
