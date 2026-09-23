package com.smartfirehub.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.smartfirehub.settings.model.AiCredential;
import com.smartfirehub.settings.model.UnknownAgentTypeException;
import com.smartfirehub.settings.service.AiCredentialService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

  @MockitoBean private AiCredentialService aiCredentialService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  private void mockAuthentication(String... permissions) {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  @Test
  void getSessions_authenticated_returnsOk() throws Exception {
    mockAuthentication("ai:read");
    AiSessionResponse session =
        AiSessionResponse.ofWeb(
            1L,
            1L,
            "session-001",
            null,
            null,
            "My Session",
            LocalDateTime.now(),
            LocalDateTime.now());
    when(aiSessionService.getSessions(1L, 0, 20)).thenReturn(List.of(session));

    mockMvc
        .perform(get("/api/v1/ai/sessions").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].sessionId").value("session-001"))
        .andExpect(jsonPath("$[0].title").value("My Session"));
  }

  @Test
  void getSessions_withPageAndSize_passesParamsToService() throws Exception {
    mockAuthentication("ai:read");
    when(aiSessionService.getSessions(1L, 1, 10)).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/ai/sessions")
                .param("page", "1")
                .param("size", "10")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  void getSessions_withoutParams_usesDefaults() throws Exception {
    mockAuthentication("ai:read");
    when(aiSessionService.getSessions(1L, 0, 20)).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/ai/sessions").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  void createSession_authenticated_returnsCreated() throws Exception {
    mockAuthentication("ai:write");
    CreateAiSessionRequest request =
        new CreateAiSessionRequest("session-new", "dataset", 10L, "New Session");
    AiSessionResponse response =
        AiSessionResponse.ofWeb(
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

  @Test
  void chat_withMessageOnly_returnsOk() throws Exception {
    mockAuthentication("ai:write");

    String body = "{\"message\":\"hello\",\"sessionId\":null,\"fileIds\":null}";

    mockMvc
        .perform(
            post("/api/v1/ai/chat")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void chat_withFileIdsOnly_returnsOk() throws Exception {
    mockAuthentication("ai:write");

    String body = "{\"message\":null,\"sessionId\":null,\"fileIds\":[1,2]}";

    mockMvc
        .perform(
            post("/api/v1/ai/chat")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void chat_withMessageAndFileIds_returnsOk() throws Exception {
    mockAuthentication("ai:write");

    String body = "{\"message\":\"analyze this\",\"sessionId\":null,\"fileIds\":[3]}";

    mockMvc
        .perform(
            post("/api/v1/ai/chat")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  /** 이슈 #714 — 남의 세션을 이어 쓰려 하면 ai-agent 로 넘기기 전에 403. */
  @Test
  void chat_resumingOtherUserSession_returnsForbiddenWithoutCallingAgent() throws Exception {
    mockAuthentication("ai:write");
    doThrow(new AccessDeniedException("Access denied for AI session: other-session-id"))
        .when(aiSessionService)
        .verifyNotOthersSession(1L, "other-session-id");

    String body = "{\"message\":\"hello\",\"sessionId\":\"other-session-id\",\"fileIds\":null}";

    mockMvc
        .perform(
            post("/api/v1/ai/chat")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    verify(aiAgentProxyService, never())
        .streamChat(any(), any(), any(), any(), any(), any(), any());
  }

  /** 본인 세션 이어쓰기는 소유자 확인을 거쳐 그대로 진행된다. */
  @Test
  void chat_resumingOwnSession_verifiesOwnershipAndStreams() throws Exception {
    mockAuthentication("ai:write");

    String body = "{\"message\":\"hello\",\"sessionId\":\"my-session-id\",\"fileIds\":null}";

    mockMvc
        .perform(
            post("/api/v1/ai/chat")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    verify(aiSessionService).verifyNotOthersSession(1L, "my-session-id");
    verify(aiAgentProxyService)
        .streamChat(any(), eq("hello"), eq("my-session-id"), any(), eq(1L), any(), any());
  }

  @Test
  void chat_withNoMessageAndNoFileIds_returnsBadRequest() throws Exception {
    mockAuthentication("ai:write");

    String body = "{\"message\":\"\",\"sessionId\":null,\"fileIds\":null}";

    mockMvc
        .perform(
            post("/api/v1/ai/chat")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getAuthStatus_sdkWithOauthToken_usesTokenVerification() throws Exception {
    // given: sdk 타입이고 OAuth 토큰이 설정된 상태 — 타입형 전환(2026-09) 이후로는
    // AiCredentialService.resolve() 가 유일한 출처다.
    mockAuthentication("ai:settings");
    when(aiCredentialService.resolve()).thenReturn(new AiCredential.Sdk("oat-test", ""));
    when(aiAgentProxyService.verifyCliToken("oat-test")).thenReturn("{\"valid\":true}");

    // when: /auth-status 엔드포인트 호출
    mockMvc
        .perform(get("/api/v1/ai/auth-status").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().string("{\"valid\":true}"));

    // then: OAuth 토큰 검증 경로(`verifyCliToken`)가 호출되고 API 키 경로(`verifyApiKey`)는 호출되지 않음
    // 컨트롤러 switch 가 쥔 토큰을 그대로 넘겼는지까지 본다 — 프록시가 내부에서 resolve() 를
    // 다시 부르지 않는다는 것이 이 인자로 드러난다.
    verify(aiAgentProxyService).verifyCliToken("oat-test");
    verify(aiAgentProxyService, never()).verifyApiKey(any());
  }

  /**
   * opencode 는 Anthropic 인증 개념이 없다 — ai-agent 를 부르지 않고 "해당 없음"을 바로
   * 응답해야 한다. {@code Opencode.apiKey}(OpenAI 호환 키)를 verifyApiKey()(Anthropic 키 검증)로
   * 보내는 회귀를 이 테스트가 잡는다.
   */
  @Test
  void getAuthStatus_opencode면_해당없음을_바로_응답한다() throws Exception {
    mockAuthentication("ai:settings");
    when(aiCredentialService.resolve())
        .thenReturn(
            new AiCredential.Opencode("openai", "https://api.openai.com/v1", "", "sk-oai"));

    mockMvc
        .perform(get("/api/v1/ai/auth-status").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().string("{\"valid\":false,\"applicable\":false}"));

    verify(aiAgentProxyService, never()).verifyCliToken(any());
    verify(aiAgentProxyService, never()).verifyApiKey(any());
  }

  /**
   * fail-closed 가드: 알 수 없는 agentType(손으로 고친 행 등)을 만나면 resolve() 가 던지는
   * {@code UnknownAgentTypeException} 이 그대로 전파돼 500 이 되어야 한다. 누군가 이 예외를
   * 잡아 빈 자격증명으로 계속 진행하게 바꾸면(예: sdk 로 폴백) 이 테스트가 RED 가 된다 — 그
   * 폴백이 정확히 6b1c6383 과 같은 모양의 과금 혼입이다.
   */
  @Test
  void getAuthStatus_알수없는_유형이면_500으로_실패한다() throws Exception {
    mockAuthentication("ai:settings");
    when(aiCredentialService.resolve()).thenThrow(new UnknownAgentTypeException("martian"));

    mockMvc
        .perform(get("/api/v1/ai/auth-status").header("Authorization", "Bearer valid-token"))
        .andExpect(status().is5xxServerError());

    verify(aiAgentProxyService, never()).verifyCliToken(any());
    verify(aiAgentProxyService, never()).verifyApiKey(any());
  }
}
