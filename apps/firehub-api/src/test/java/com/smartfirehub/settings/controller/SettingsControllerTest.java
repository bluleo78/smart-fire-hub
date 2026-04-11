package com.smartfirehub.settings.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.settings.dto.UpdateSettingsRequest;
import com.smartfirehub.settings.service.SettingsService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SettingsController WebMvcTest — JaCoCo LINE 커버리지 보강용. 핵심 경로(getSettings / getDecryptedAiApiKey /
 * updateSettings / getSmtpSettings / updateSmtpSettings / testSmtpSettings) 각각의 성공 분기만 커버한다.
 */
@WebMvcTest(SettingsController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class SettingsControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private SettingsService settingsService;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  /** 인증 mock — 유효 토큰 + 주어진 권한 세트를 PermissionInterceptor가 허용하도록 세팅한다. */
  private void mockAuth(String... permissions) {
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  @Test
  void getSettings_withPrefix_returnsList() throws Exception {
    mockAuth("ai:settings");
    SettingResponse s = new SettingResponse("ai.model", "claude", "desc", LocalDateTime.now());
    when(settingsService.getByPrefix("ai")).thenReturn(List.of(s));

    mockMvc
        .perform(
            get("/api/v1/settings")
                .param("prefix", "ai")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].key").value("ai.model"))
        .andExpect(jsonPath("$[0].value").value("claude"));
  }

  @Test
  void getDecryptedAiApiKey_whenPresent_returnsKey() throws Exception {
    mockAuth("ai:settings");
    when(settingsService.getDecryptedApiKey()).thenReturn(Optional.of("sk-test"));

    mockMvc
        .perform(get("/api/v1/settings/ai-api-key").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.apiKey").value("sk-test"));
  }

  @Test
  void getDecryptedAiApiKey_whenEmpty_returnsBlank() throws Exception {
    mockAuth("ai:settings");
    when(settingsService.getDecryptedApiKey()).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/settings/ai-api-key").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.apiKey").value(""));
  }

  @Test
  void updateSettings_validBody_returnsNoContent() throws Exception {
    mockAuth("ai:settings");
    doNothing().when(settingsService).updateSettings(any(), anyLong());
    UpdateSettingsRequest body = new UpdateSettingsRequest(Map.of("ai.max_turns", "10"));

    mockMvc
        .perform(
            put("/api/v1/settings")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isNoContent());
  }

  @Test
  void getSmtpSettings_returnsList() throws Exception {
    mockAuth("settings:write");
    when(settingsService.getSmtpSettings())
        .thenReturn(
            List.of(new SettingResponse("smtp.host", "localhost", null, LocalDateTime.now())));

    mockMvc
        .perform(get("/api/v1/settings/smtp").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].key").value("smtp.host"));
  }

  @Test
  void updateSmtpSettings_returnsNoContent() throws Exception {
    mockAuth("settings:write");
    doNothing().when(settingsService).updateSmtpSettings(any(), eq(1L));

    mockMvc
        .perform(
            put("/api/v1/settings/smtp")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("smtp.host", "localhost"))))
        .andExpect(status().isNoContent());
  }

  @Test
  void testSmtpSettings_whenHostBlank_returnsFailureMessage() throws Exception {
    mockAuth("settings:write");
    // 호스트가 비어 있으면 컨트롤러가 success=false 응답을 즉시 반환 — JavaMailSender 생성 로직을 타지 않음
    when(settingsService.getSmtpConfig()).thenReturn(Map.of("smtp.host", ""));

    mockMvc
        .perform(post("/api/v1/settings/smtp/test").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false));
  }

  @Test
  void testSmtpSettings_invalidHost_returnsCaughtError() throws Exception {
    mockAuth("settings:write");
    // 실제 연결이 실패하도록 존재하지 않는 호스트를 넣어 JavaMailSenderImpl 경로 전체를 타게 한다
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "invalid.nonexistent.example.invalid",
                "smtp.port", "25",
                "smtp.username", "u",
                "smtp.password", "p",
                "smtp.starttls", "true"));

    mockMvc
        .perform(post("/api/v1/settings/smtp/test").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false));
  }
}
