package com.smartfirehub.pipeline.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.pipeline.dto.*;
import com.smartfirehub.pipeline.exception.PipelineNotFoundException;
import com.smartfirehub.pipeline.exception.TriggerNotFoundException;
import com.smartfirehub.pipeline.service.TriggerService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.time.LocalDateTime;
import java.util.List;
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

@SuppressWarnings("null")
@WebMvcTest(TriggerController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class TriggerControllerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private TriggerService triggerService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("test-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("trigger:read", "trigger:write", "trigger:delete"));
  }

  private TriggerResponse sampleTrigger() {
    return new TriggerResponse(
        10L,
        1L,
        "SCHEDULE",
        "Daily Trigger",
        "Runs daily",
        true,
        Map.of("cron", "0 0 * * *"),
        Map.of(),
        null,
        1L,
        LocalDateTime.now());
  }

  @Test
  void getTriggers_withPermission_returnsList() throws Exception {
    when(triggerService.getTriggers(1L)).thenReturn(List.of(sampleTrigger()));

    mockMvc
        .perform(get("/api/v1/pipelines/1/triggers").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(10))
        .andExpect(jsonPath("$[0].name").value("Daily Trigger"));
  }

  @Test
  void createTrigger_withPermission_returnsCreated() throws Exception {
    CreateTriggerRequest request =
        new CreateTriggerRequest(
            "Daily Trigger", TriggerType.SCHEDULE, "Runs daily", Map.of("cron", "0 0 * * *"));

    when(triggerService.createTrigger(eq(1L), any(CreateTriggerRequest.class), anyLong()))
        .thenReturn(sampleTrigger());

    mockMvc
        .perform(
            post("/api/v1/pipelines/1/triggers")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(10))
        .andExpect(jsonPath("$.triggerType").value("SCHEDULE"));
  }

  @Test
  void updateTrigger_withPermission_returnsNoContent() throws Exception {
    UpdateTriggerRequest request =
        new UpdateTriggerRequest(
            "Daily Trigger Updated", true, "Updated description", Map.of("cron", "0 1 * * *"));

    mockMvc
        .perform(
            put("/api/v1/pipelines/1/triggers/10")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    // 경로의 pipelineId 가 서비스까지 전달돼야 소속 확인이 된다(WD-228).
    verify(triggerService).updateTrigger(eq(1L), eq(10L), any(UpdateTriggerRequest.class), eq(1L));
  }

  @Test
  void deleteTrigger_withPermission_returnsNoContent() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/pipelines/1/triggers/10").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    verify(triggerService).deleteTrigger(1L, 10L, 1L);
  }

  @Test
  void getTriggers_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/pipelines/1/triggers")).andExpect(status().isUnauthorized());
  }

  @Test
  void getTriggerById_passesPathPipelineId() throws Exception {
    when(triggerService.getTriggerById(1L, 10L)).thenReturn(sampleTrigger());

    mockMvc
        .perform(
            get("/api/v1/pipelines/1/triggers/10").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(10));

    verify(triggerService).getTriggerById(1L, 10L);
  }

  @Test
  void getTriggerById_otherPipeline_returnsNotFound() throws Exception {
    // 다른 파이프라인 경로면 서비스가 없는 트리거와 같은 예외를 던진다 — 404 와 한국어 문구로 답해야 한다(WD-228).
    when(triggerService.getTriggerById(99999L, 10L)).thenThrow(new TriggerNotFoundException());

    mockMvc
        .perform(
            get("/api/v1/pipelines/99999/triggers/10").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value(TriggerNotFoundException.MESSAGE));
  }

  @Test
  void toggleTrigger_passesPathPipelineId() throws Exception {
    when(triggerService.getTriggerById(1L, 10L)).thenReturn(sampleTrigger());

    mockMvc
        .perform(
            patch("/api/v1/pipelines/1/triggers/10/toggle")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    // 켜져 있던 트리거이므로 같은 파이프라인 범위에서 끄기로 바뀐다.
    verify(triggerService).toggleTrigger(1L, 10L, false, 1L);
  }

  @Test
  void getTriggers_missingPipeline_returnsNotFound() throws Exception {
    // 없는(또는 삭제된) 파이프라인이면 빈 목록이 아니라 404 와 한국어 문구(WD-244).
    when(triggerService.getTriggers(999999L)).thenThrow(new PipelineNotFoundException());

    mockMvc
        .perform(
            get("/api/v1/pipelines/999999/triggers").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value(PipelineNotFoundException.PIPELINE_MESSAGE));
  }

  @Test
  void getTriggerEvents_missingPipeline_returnsNotFound() throws Exception {
    when(triggerService.getTriggerEvents(999999L, 20)).thenThrow(new PipelineNotFoundException());

    mockMvc
        .perform(
            get("/api/v1/pipelines/999999/triggers/events")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value(PipelineNotFoundException.PIPELINE_MESSAGE));
  }

  @Test
  void getTriggerEvents_limitOutOfRange_returnsKoreanBadRequest() throws Exception {
    // 서비스의 limit 검증(IllegalArgumentException)이 400 과 한국어 문구로 나가야 한다(WD-244).
    when(triggerService.getTriggerEvents(1L, -1))
        .thenThrow(new IllegalArgumentException("limit 은 1~200 사이여야 합니다."));

    mockMvc
        .perform(
            get("/api/v1/pipelines/1/triggers/events")
                .param("limit", "-1")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("limit 은 1~200 사이여야 합니다."));
  }
}
