package com.smartfirehub.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.ai.dto.AiSessionResponse;
import com.smartfirehub.ai.dto.CreateAiSessionRequest;
import com.smartfirehub.ai.service.AiAgentProxyService;
import com.smartfirehub.ai.service.AiSessionService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SuppressWarnings("null")
@WebMvcTest(AiController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AiControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private AiSessionService aiSessionService;

  @MockitoBean private AiAgentProxyService aiAgentProxyService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  private void mockAuthentication(String... permissions) {
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  @Test
  void getSessions_authenticated_returnsOk() throws Exception {
    mockAuthentication("ai:read");
    AiSessionResponse session =
        new AiSessionResponse(
            1L,
            1L,
            "session-001",
            null,
            null,
            "My Session",
            LocalDateTime.now(),
            LocalDateTime.now());
    when(aiSessionService.getSessions(1L)).thenReturn(List.of(session));

    mockMvc
        .perform(get("/api/v1/ai/sessions").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].sessionId").value("session-001"))
        .andExpect(jsonPath("$[0].title").value("My Session"));
  }

  @Test
  void createSession_authenticated_returnsCreated() throws Exception {
    mockAuthentication("ai:write");
    CreateAiSessionRequest request =
        new CreateAiSessionRequest("session-new", "dataset", 10L, "New Session");
    AiSessionResponse response =
        new AiSessionResponse(
            2L,
            1L,
            "session-new",
            "dataset",
            10L,
            "New Session",
            LocalDateTime.now(),
            LocalDateTime.now());
    when(aiSessionService.createSession(eq(1L), any(CreateAiSessionRequest.class)))
        .thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/ai/sessions")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sessionId").value("session-new"))
        .andExpect(jsonPath("$.title").value("New Session"));
  }

  @Test
  void getSessionMessages_authenticated_returnsOk() throws Exception {
    mockAuthentication("ai:read");
    // verifySessionOwnership does nothing for a valid owner (void method, no stub needed)
    when(aiAgentProxyService.getSessionHistory("test-session-id"))
        .thenReturn("[{\"id\":\"1\",\"role\":\"user\",\"content\":\"hello\"}]");

    mockMvc
        .perform(
            get("/api/v1/ai/sessions/test-session-id/messages")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().string("[{\"id\":\"1\",\"role\":\"user\",\"content\":\"hello\"}]"));
  }

  @Test
  void getSessionMessages_otherUserSession_returnsForbidden() throws Exception {
    mockAuthentication("ai:read");
    doThrow(new AccessDeniedException("AI 세션에 대한 권한이 없습니다"))
        .when(aiSessionService)
        .verifySessionOwnership(1L, "other-session-id");

    mockMvc
        .perform(
            get("/api/v1/ai/sessions/other-session-id/messages")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  void deleteSession_authenticated_returnsNoContent() throws Exception {
    mockAuthentication("ai:write");

    mockMvc
        .perform(delete("/api/v1/ai/sessions/5").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent());
  }
}
