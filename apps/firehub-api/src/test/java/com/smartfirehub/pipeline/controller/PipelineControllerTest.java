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
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
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

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

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

  /**
   * WD-154 — 스텝 출력 컬럼 저장 검증 실패(서비스의 {@link IllegalArgumentException})는 생성·수정 모두 400 과 스텝 이름이 담긴 한국어
   * 문구로 응답한다. 웹 편집기·AI 에이전트 도구가 이 message 를 그대로 보여 준다.
   */
  @Test
  void createPipeline_stepColumnValidationFailure_returnsBadRequestWithKoreanMessage()
      throws Exception {
    String message = "스텝 'API 호출': 출력 데이터셋에 없는 컬럼입니다: no_such_col";
    when(pipelineService.createPipeline(any(CreatePipelineRequest.class), anyLong()))
        .thenThrow(new IllegalArgumentException(message));

    mockMvc
        .perform(
            post("/api/v1/pipelines")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CreatePipelineRequest("p", null, List.of()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(message));
  }

  @Test
  void updatePipeline_stepColumnValidationFailure_returnsBadRequestWithKoreanMessage()
      throws Exception {
    String message = "스텝 'API 호출': 비어 있는 매핑 행이 있습니다 — 채우거나 삭제하세요";
    org.mockito.Mockito.doThrow(new IllegalArgumentException(message))
        .when(pipelineService)
        .updatePipeline(eq(1L), any(UpdatePipelineRequest.class), anyLong());

    mockMvc
        .perform(
            put("/api/v1/pipelines/1")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new UpdatePipelineRequest("p", null, true, List.of()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(message));
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

    // 호출자를 함께 넘겨 볼 수 없는 임시 데이터셋 판정을 받게 한다(WD-310)
    verify(pipelineService).deletePipeline(eq(1L), anyLong());
  }

  /** 진행 중 실행이 있으면 삭제를 409 와 한국어 안내 문구로 거부한다(WD-437). */
  @Test
  void deletePipeline_withActiveExecution_returnsConflictWithMessage() throws Exception {
    org.mockito.Mockito.doThrow(
            new com.smartfirehub.pipeline.exception.PipelineExecutionActiveException(false))
        .when(pipelineService)
        .deletePipeline(eq(1L), anyLong());

    mockMvc
        .perform(delete("/api/v1/pipelines/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("실행 중에는 삭제할 수 없습니다. 실행을 취소한 뒤 삭제하세요"))
        .andExpect(jsonPath("$.code").value("PIPELINE_EXECUTION_ACTIVE"));
  }

  /** 삭제 영향 조회(WD-170) — 함께 지워질 자동 생성 임시 데이터셋 개수를 그대로 내보낸다. */
  @Test
  void getDeleteImpact_withPermission_returnsTempDatasetCount() throws Exception {
    when(pipelineService.getDeleteImpact(eq(1L), anyLong()))
        .thenReturn(new PipelineDeleteImpactResponse(
                2,
                true,
                com.smartfirehub.pipeline.repository.PipelineExecutionRepository.ActiveExecutionState
                    .ACTIVE));

    mockMvc
        .perform(
            get("/api/v1/pipelines/1/delete-impact").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tempDatasetCount").value(2))
        .andExpect(jsonPath("$.hasHiddenTempDatasets").value(true))
        // 진행 중 실행 안내(WD-437) — 확인 창이 삭제를 막는 데 쓴다.
        .andExpect(jsonPath("$.activeExecution").value("ACTIVE"));
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
    mockMvc
        .perform(post("/api/v1/pipelines/1/steps/2/full-rebuild"))
        .andExpect(status().isUnauthorized());
  }

  /**
   * 증분 필드(Task 7)의 JSON 계약 — Task 8 웹 UI가 이 필드명·형태에 그대로 의존한다. {@code lastRunAt} 이 ISO-8601 오프셋 문자열로
   * 직렬화되는지(에포크 숫자가 아닌지)까지 함께 확인한다.
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

  // ------------------------------------------------------------------ //
  // 실행 취소(WD-153)
  // ------------------------------------------------------------------ //

  /** 실행 중 실행 취소 요청은 202 Accepted 와 접수 상태(RUNNING — 다음 체크포인트에서 멈춤)를 돌려준다. */
  @Test
  void cancelExecution_running_returnsAccepted() throws Exception {
    LocalDateTime at = LocalDateTime.of(2026, 10, 22, 1, 2, 3);
    when(pipelineService.cancelExecution(1L, 42L, 1L))
        .thenReturn(new CancelExecutionResponse(42L, "RUNNING", at));

    mockMvc
        .perform(
            post("/api/v1/pipelines/1/executions/42/cancel")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.executionId").value(42))
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.cancelRequestedAt").exists());
  }

  /** 이미 끝난 실행 취소는 409 와 사용자 문구. */
  @Test
  void cancelExecution_finished_returnsConflict() throws Exception {
    when(pipelineService.cancelExecution(1L, 43L, 1L))
        .thenThrow(new com.smartfirehub.pipeline.exception.ExecutionAlreadyFinishedException());

    mockMvc
        .perform(
            post("/api/v1/pipelines/1/executions/43/cancel")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("이미 끝난 실행은 취소할 수 없습니다"));
  }

  /** 실행 권한(pipeline:execute)이 없으면 403 — 취소는 실행과 같은 권한이다. 서비스는 호출되지 않는다. */
  @Test
  void cancelExecution_withoutExecutePermission_returnsForbidden() throws Exception {
    when(jwtTokenProvider.parseAccessToken("reader-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(2L, null, false)));
    when(permissionService.getUserPermissions(2L)).thenReturn(Set.of("pipeline:read"));

    mockMvc
        .perform(
            post("/api/v1/pipelines/1/executions/42/cancel")
                .header("Authorization", "Bearer reader-token"))
        .andExpect(status().isForbidden());
    org.mockito.Mockito.verify(pipelineService, org.mockito.Mockito.never())
        .cancelExecution(anyLong(), anyLong(), anyLong());
  }

  /** 없는(다른 테넌트·다른 파이프라인 포함) 실행은 404. */
  @Test
  void cancelExecution_notFound_returns404() throws Exception {
    when(pipelineService.cancelExecution(1L, 44L, 1L))
        .thenThrow(com.smartfirehub.pipeline.exception.PipelineNotFoundException.execution());

    mockMvc
        .perform(
            post("/api/v1/pipelines/1/executions/44/cancel")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound())
        // WD-226: 응답 message 는 토스트에 그대로 실리는 한국어 문구
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                .value("실행 기록을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."));
  }

  @Test
  void getTriggerEvents_missingPipeline_returnsNotFound() throws Exception {
    // /trigger-events 도 /triggers/events 와 같이 없는 파이프라인이면 404 와 한국어 문구(WD-244).
    when(triggerService.getTriggerEvents(999999L, 20))
        .thenThrow(new com.smartfirehub.pipeline.exception.PipelineNotFoundException());

    mockMvc
        .perform(
            get("/api/v1/pipelines/999999/trigger-events")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("파이프라인을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."));
  }

  @Test
  void getTriggerEvents_limitOutOfRange_returnsKoreanBadRequest() throws Exception {
    when(triggerService.getTriggerEvents(1L, 100000))
        .thenThrow(new IllegalArgumentException("limit 은 1~200 사이여야 합니다."));

    mockMvc
        .perform(
            get("/api/v1/pipelines/1/trigger-events")
                .param("limit", "100000")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("limit 은 1~200 사이여야 합니다."));
  }
}
