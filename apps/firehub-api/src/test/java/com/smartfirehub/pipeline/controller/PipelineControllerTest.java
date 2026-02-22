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
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
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
            1L, "ETL Daily", "Daily ETL run", true, "testuser", 3, LocalDateTime.now());
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
  void getPipelines_withoutAuth_returnsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/pipelines")).andExpect(status().isForbidden());
  }
}
