package com.smartfirehub.pipeline.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.service.ApiCallPreviewService;
import com.smartfirehub.pipeline.service.PipelineService;
import com.smartfirehub.pipeline.service.TriggerService;
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

@SuppressWarnings("null")
@WebMvcTest(PipelineController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PipelineControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private PipelineService pipelineService;

  @MockitoBean private TriggerService triggerService;

  @MockitoBean private ApiCallPreviewService apiCallPreviewService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("test-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L))
        .thenReturn(
            Set.of(
                "pipeline:read",
                "pipeline:write",
                "pipeline:delete",
                "pipeline:execute",
                "trigger:read"));
  }

  @Test
  void getPipelines_withPermission_returnsPageResponse() throws Exception {
    PipelineResponse pipeline =
        new PipelineResponse(
            1L, "ETL Daily", "Daily ETL run", true, "testuser", 3, 2, LocalDateTime.now());
    PageResponse<PipelineResponse> page = new PageResponse<>(List.of(pipeline), 0, 20, 1, 1);

    when(pipelineService.getPipelines(anyInt(), anyInt())).thenReturn(page);

    mockMvc
        .perform(get("/api/v1/pipelines").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("ETL Daily"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void createPipeline_withPermission_returnsCreated() throws Exception {
    CreatePipelineRequest request =
        new CreatePipelineRequest("ETL Daily", "Daily ETL run", List.of());
    PipelineDetailResponse detail =
        new PipelineDetailResponse(
            1L,
            "ETL Daily",
            "Daily ETL run",
            true,
            "testuser",
            List.of(),
            LocalDateTime.now(),
            LocalDateTime.now(),
            "testuser");

    when(pipelineService.createPipeline(any(CreatePipelineRequest.class), anyLong()))
        .thenReturn(detail);

    mockMvc
        .perform(
            post("/api/v1/pipelines")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("ETL Daily"))
        .andExpect(jsonPath("$.id").value(1));
  }

  @Test
  void getPipelineById_withPermission_returnsDetail() throws Exception {
    PipelineDetailResponse detail =
        new PipelineDetailResponse(
            1L,
            "ETL Daily",
            "Daily ETL run",
            true,
            "testuser",
            List.of(),
            LocalDateTime.now(),
            LocalDateTime.now(),
            "testuser");

    when(pipelineService.getPipelineById(1L)).thenReturn(detail);

    mockMvc
        .perform(get("/api/v1/pipelines/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.name").value("ETL Daily"));
  }

  @Test
  void deletePipeline_withPermission_returnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/v1/pipelines/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    verify(pipelineService).deletePipeline(1L);
  }

  @Test
  void getPipelines_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/pipelines")).andExpect(status().isUnauthorized());
  }

  // --- 전체 재생성/재읽기 예약 API (Task 7) ---

  @Test
  void reserveFullRebuild_withPermission_returnsNoContent() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/pipelines/1/steps/2/full-rebuild")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    verify(pipelineService).setFullRebuildPending(1L, 2L, true);
  }

  @Test
  void cancelFullRebuild_withPermission_returnsNoContent() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/pipelines/1/steps/2/full-rebuild")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    verify(pipelineService).setFullRebuildPending(1L, 2L, false);
  }

  /** 증분 플레이스홀더가 없는 스텝에 예약을 걸면 서비스가 {@link IllegalArgumentException}(400)을 던진다. */
  @Test
  void reserveFullRebuild_nonIncrementalStep_returnsBadRequest() throws Exception {
    org.mockito.Mockito.doThrow(
            new IllegalArgumentException("{{last_run_at}} 을 쓰는 SQL 스텝만 전체 재생성을 예약할 수 있습니다."))
        .when(pipelineService)
        .setFullRebuildPending(1L, 2L, true);

    mockMvc
        .perform(
            post("/api/v1/pipelines/1/steps/2/full-rebuild")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reserveFullRebuild_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(post("/api/v1/pipelines/1/steps/2/full-rebuild")).andExpect(status().isUnauthorized());
  }

  /**
   * 증분 필드(Task 7)의 JSON 계약 — Task 8 웹 UI가 이 필드명·형태에 그대로 의존한다. {@code lastRunAt} 이
   * ISO-8601 오프셋 문자열로 직렬화되는지(에포크 숫자가 아닌지)까지 함께 확인한다.
   */
  @Test
  void getPipelineById_includesIncrementalFields() throws Exception {
    PipelineStepResponse step =
        new PipelineStepResponse(
            10L,
            "stepA",
            "설명",
            "SQL",
            "SELECT code FROM data.src WHERE _updated_at >= {{last_run_at}}",
            5L,
            "out",
            List.of(),
            List.of(),
            0,
            "MERGE",
            null,
            null,
            null,
            null,
            java.time.OffsetDateTime.parse("2026-09-19T01:02:03Z"),
            true,
            List.of("경고 문구"),
            PipelineStepResponse.FULL_REBUILD_MODE_REBUILD_OUTPUT);
    PipelineDetailResponse detail =
        new PipelineDetailResponse(
            1L,
            "ETL Daily",
            "Daily ETL run",
            true,
            "testuser",
            List.of(step),
            LocalDateTime.now(),
            LocalDateTime.now(),
            "testuser");

    when(pipelineService.getPipelineById(1L)).thenReturn(detail);

    mockMvc
        .perform(get("/api/v1/pipelines/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.steps[0].lastRunAt").value("2026-09-19T01:02:03Z"))
        .andExpect(jsonPath("$.steps[0].fullRebuildPending").value(true))
        .andExpect(jsonPath("$.steps[0].warnings[0]").value("경고 문구"))
        .andExpect(jsonPath("$.steps[0].fullRebuildMode").value("REBUILD_OUTPUT"));
  }
}
