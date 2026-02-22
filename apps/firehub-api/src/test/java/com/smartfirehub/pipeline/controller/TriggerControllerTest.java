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
import com.smartfirehub.pipeline.service.TriggerService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private TriggerService triggerService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
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

    verify(triggerService).updateTrigger(eq(10L), any(UpdateTriggerRequest.class), eq(1L));
  }

  @Test
  void deleteTrigger_withPermission_returnsNoContent() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/pipelines/1/triggers/10").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    verify(triggerService).deleteTrigger(10L);
  }

  @Test
  void getTriggers_withoutAuth_returnsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/pipelines/1/triggers")).andExpect(status().isForbidden());
  }
}
