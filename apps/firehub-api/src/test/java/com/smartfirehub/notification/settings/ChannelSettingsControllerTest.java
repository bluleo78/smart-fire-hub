package com.smartfirehub.notification.settings;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.settings.dto.ChannelSettingResponse;
import com.smartfirehub.notification.settings.dto.ChannelTestResult;
import com.smartfirehub.permission.service.PermissionService;
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

/**
 * ChannelSettingsController WebMvcTest.
 *
 * <p>JwtTokenProvider·PermissionService를 Mockito로 모킹하여 JWT 인증을 시뮬레이션한다. ChannelSettingsService는
 * Mockito mock으로 비즈니스 로직과 분리하여 컨트롤러 레이어만 검증한다.
 */
@WebMvcTest(ChannelSettingsController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ChannelSettingsControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ChannelSettingsService channelSettingsService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  private static final long TEST_USER_ID = 1L;
  private static final String AUTH_HEADER = "Bearer test-token";

  /** 테스트 공통 JWT 인증 mock 설정. */
  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(TEST_USER_ID);
    // 인증은 되지만 권한 체크 없는 엔드포인트 — 빈 Set으로 충분
    when(permissionService.getUserPermissions(TEST_USER_ID)).thenReturn(Set.of());
  }

  // =========================================================================
  // GET /api/v1/channels/settings
  // =========================================================================

  /** 인증된 사용자가 GET 요청 시 4개 채널 row를 반환해야 한다. */
  @Test
  void getSettings_authenticated_returnsFourChannels() throws Exception {
    // given: 4채널 mock 응답 준비
    List<ChannelSettingResponse> mockResponse =
        List.of(
            new ChannelSettingResponse("CHAT", true, true, false, "웹 인박스", null),
            new ChannelSettingResponse("EMAIL", true, true, false, "user@example.com", null),
            new ChannelSettingResponse(
                "KAKAO", false, false, false, "카카오톡", "/api/v1/oauth/kakao/auth-url"),
            new ChannelSettingResponse(
                "SLACK", false, false, false, "Slack", "/api/v1/oauth/slack/auth-url"));
    when(channelSettingsService.getSettings(TEST_USER_ID)).thenReturn(mockResponse);

    // when & then
    mockMvc
        .perform(get("/api/v1/channels/settings").header("Authorization", AUTH_HEADER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4))
        .andExpect(jsonPath("$[0].channel").value("CHAT"))
        .andExpect(jsonPath("$[0].enabled").value(true))
        .andExpect(jsonPath("$[0].connected").value(true))
        .andExpect(jsonPath("$[2].channel").value("KAKAO"))
        .andExpect(jsonPath("$[2].connected").value(false))
        .andExpect(jsonPath("$[2].oauthStartUrl").value("/api/v1/oauth/kakao/auth-url"));
  }

  /** 인증 토큰 없이 접근하면 401 반환. */
  @Test
  void getSettings_unauthenticated_returns401() throws Exception {
    mockMvc.perform(get("/api/v1/channels/settings")).andExpect(status().isUnauthorized());
  }

  // =========================================================================
  // PATCH /api/v1/channels/settings/{channel}/preference
  // =========================================================================

  /** EMAIL 채널 preference를 false로 변경하면 204 반환 및 서비스 호출 확인. */
  @Test
  void updatePreference_email_disabled_returns204() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/channels/settings/EMAIL/preference")
                .header("Authorization", AUTH_HEADER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\": false}"))
        .andExpect(status().isNoContent());

    verify(channelSettingsService).updatePreference(TEST_USER_ID, ChannelType.EMAIL, false);
  }

  /** CHAT 채널 preference 변경 시도 → 서비스가 IllegalArgumentException → 400 반환. */
  @Test
  void updatePreference_chat_returns400() throws Exception {
    doThrow(new IllegalArgumentException("CHAT 채널은 항상 활성 상태이며 변경할 수 없습니다."))
        .when(channelSettingsService)
        .updatePreference(TEST_USER_ID, ChannelType.CHAT, false);

    mockMvc
        .perform(
            patch("/api/v1/channels/settings/CHAT/preference")
                .header("Authorization", AUTH_HEADER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\": false}"))
        .andExpect(status().isBadRequest());
  }

  // =========================================================================
  // DELETE /api/v1/channels/settings/{channel}
  // =========================================================================

  /** KAKAO binding 해제 요청 시 204 반환 및 서비스 호출 확인. */
  @Test
  void disconnectBinding_kakao_returns204() throws Exception {
    mockMvc
        .perform(delete("/api/v1/channels/settings/KAKAO").header("Authorization", AUTH_HEADER))
        .andExpect(status().isNoContent());

    verify(channelSettingsService).disconnectBinding(TEST_USER_ID, ChannelType.KAKAO);
  }

  /** 소문자 채널명도 대소문자 무관하게 처리된다. */
  @Test
  void disconnectBinding_lowercaseChannelName_returns204() throws Exception {
    mockMvc
        .perform(delete("/api/v1/channels/settings/kakao").header("Authorization", AUTH_HEADER))
        .andExpect(status().isNoContent());

    verify(channelSettingsService).disconnectBinding(TEST_USER_ID, ChannelType.KAKAO);
  }

  // =========================================================================
  // POST /api/v1/channels/settings/{channel}/test  (이슈 #85)
  // =========================================================================

  /** EMAIL 테스트 발송 성공 — 200 OK + success/message 반환. */
  @Test
  void testChannel_emailSuccess_returns200WithSuccessTrue() throws Exception {
    when(channelSettingsService.testChannel(TEST_USER_ID, ChannelType.EMAIL))
        .thenReturn(new ChannelTestResult(true, "테스트 메시지가 발송되었습니다."));

    mockMvc
        .perform(post("/api/v1/channels/settings/EMAIL/test").header("Authorization", AUTH_HEADER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.message").value("테스트 메시지가 발송되었습니다."));
  }

  /** SLACK 미연동 시 — 200 OK + success: false + 안내 메시지. */
  @Test
  void testChannel_slackNotConnected_returns200WithSuccessFalse() throws Exception {
    when(channelSettingsService.testChannel(TEST_USER_ID, ChannelType.SLACK))
        .thenReturn(new ChannelTestResult(false, "채널 연동이 필요합니다. 먼저 '연동하기' 버튼으로 계정을 연결하세요."));

    mockMvc
        .perform(post("/api/v1/channels/settings/SLACK/test").header("Authorization", AUTH_HEADER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("연동이 필요")));
  }

  /** CHAT 채널 테스트 시도 — 서비스가 IllegalArgumentException → 400 반환. */
  @Test
  void testChannel_chat_returns400() throws Exception {
    when(channelSettingsService.testChannel(TEST_USER_ID, ChannelType.CHAT))
        .thenThrow(new IllegalArgumentException("CHAT 채널은 항상 활성 상태이며 테스트 발송이 필요하지 않습니다."));

    mockMvc
        .perform(post("/api/v1/channels/settings/CHAT/test").header("Authorization", AUTH_HEADER))
        .andExpect(status().isBadRequest());
  }

  /** 인증 토큰 없이 접근하면 401 반환. */
  @Test
  void testChannel_unauthenticated_returns401() throws Exception {
    mockMvc
        .perform(post("/api/v1/channels/settings/EMAIL/test"))
        .andExpect(status().isUnauthorized());
  }
}
