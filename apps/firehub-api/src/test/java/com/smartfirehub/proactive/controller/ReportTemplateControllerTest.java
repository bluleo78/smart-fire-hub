package com.smartfirehub.proactive.controller;

import static org.mockito.ArgumentMatchers.any;
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
import com.smartfirehub.proactive.dto.CreateReportTemplateRequest;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.UpdateReportTemplateRequest;
import com.smartfirehub.proactive.service.ReportTemplateService;
import java.time.LocalDateTime;
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
 * ReportTemplateController WebMvcTest — JaCoCo LINE 커버리지 보강용.
 * 템플릿 CRUD 엔드포인트를 커버한다.
 */
@WebMvcTest(ReportTemplateController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ReportTemplateControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private ReportTemplateService reportTemplateService;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  private void mockAuth(String... permissions) {
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  private ReportTemplateResponse sampleTemplate() {
    return new ReportTemplateResponse(
        5L,
        "Daily Summary",
        "A daily summary template",
        List.of(Map.of("title", "Overview", "type", "text")),
        "default",
        1L,
        false,
        LocalDateTime.now(),
        LocalDateTime.now());
  }

  @Test
  void getTemplates_returnsList() throws Exception {
    mockAuth("proactive:read");
    when(reportTemplateService.getTemplates(anyLong())).thenReturn(List.of(sampleTemplate()));

    mockMvc
        .perform(
            get("/api/v1/proactive/templates").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(5))
        .andExpect(jsonPath("$[0].name").value("Daily Summary"));
  }

  @Test
  void getTemplate_returnsTemplate() throws Exception {
    mockAuth("proactive:read");
    when(reportTemplateService.getTemplate(5L)).thenReturn(sampleTemplate());

    mockMvc
        .perform(
            get("/api/v1/proactive/templates/5").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(5))
        .andExpect(jsonPath("$.name").value("Daily Summary"));
  }

  @Test
  void createTemplate_validBody_returnsCreated() throws Exception {
    mockAuth("proactive:write");
    CreateReportTemplateRequest req =
        new CreateReportTemplateRequest(
            "New Template",
            "Template description",
            List.of(Map.of("title", "Section 1", "type", "text")),
            "default");
    when(reportTemplateService.createTemplate(any(), eq(1L))).thenReturn(sampleTemplate());

    mockMvc
        .perform(
            post("/api/v1/proactive/templates")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(5));
  }

  @Test
  void updateTemplate_returnsNoContent() throws Exception {
    mockAuth("proactive:write");
    UpdateReportTemplateRequest req =
        new UpdateReportTemplateRequest("Updated Name", null, null, null);
    doNothing().when(reportTemplateService).updateTemplate(eq(5L), any(), eq(1L));

    mockMvc
        .perform(
            put("/api/v1/proactive/templates/5")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isNoContent());
  }

  @Test
  void deleteTemplate_returnsNoContent() throws Exception {
    mockAuth("proactive:write");
    doNothing().when(reportTemplateService).deleteTemplate(eq(5L), eq(1L));

    mockMvc
        .perform(
            delete("/api/v1/proactive/templates/5").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent());
  }
}
