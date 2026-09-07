package com.smartfirehub.proactive.controller;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.proactive.dto.ProactiveMessageResponse;
import com.smartfirehub.proactive.repository.ProactiveMessageRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** ProactiveMessageController WebMvcTest — JaCoCo LINE 커버리지 보강용. 메시지 조회/읽음 처리 엔드포인트를 커버한다. */
@WebMvcTest(ProactiveMessageController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ProactiveMessageControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProactiveMessageRepository messageRepository;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  /** 인증 mock 설정 — 유효 토큰 + proactive:read 권한 부여 */
  private void mockAuth(String... permissions) {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  private ProactiveMessageResponse sampleMessage() {
    return new ProactiveMessageResponse(
        1L, // id
        1L, // userId
        5L, // jobId
        10L, // executionId
        "Daily Report", // title
        Map.of("summary", "All good"),
        "REPORT",
        false,
        null,
        "daily-report",
        LocalDateTime.now());
  }

  @Test
  void getMessages_returnsList() throws Exception {
    mockAuth("proactive:read");
    when(messageRepository.findByUserId(anyLong(), anyInt(), anyInt(), anyBoolean()))
        .thenReturn(List.of(sampleMessage()));

    mockMvc
        .perform(get("/api/v1/proactive/messages").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1))
        .andExpect(jsonPath("$[0].title").value("Daily Report"));
  }

  @Test
  void getMessages_withCustomLimitAndOffset_returnsList() throws Exception {
    mockAuth("proactive:read");
    when(messageRepository.findByUserId(anyLong(), anyInt(), anyInt(), anyBoolean()))
        .thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/proactive/messages")
                .param("limit", "5")
                .param("offset", "10")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());
  }

  @Test
  void getMessages_withUnreadOnly_passesFlagToRepository() throws Exception {
    // #520: unreadOnly=true 파라미터가 리포지토리 호출에 그대로 전달되는지 검증
    mockAuth("proactive:read");
    when(messageRepository.findByUserId(anyLong(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.eq(true)))
        .thenReturn(List.of(sampleMessage()));

    mockMvc
        .perform(
            get("/api/v1/proactive/messages")
                .param("unreadOnly", "true")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1));
  }

  @Test
  void getUnreadCount_returnsCount() throws Exception {
    mockAuth("proactive:read");
    when(messageRepository.countUnreadByUserId(anyLong())).thenReturn(3);

    mockMvc
        .perform(
            get("/api/v1/proactive/messages/unread-count")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(3));
  }

  @Test
  void markAsRead_returnsNoContent() throws Exception {
    mockAuth("proactive:read");
    doNothing().when(messageRepository).markAsRead(anyLong(), anyLong());

    mockMvc
        .perform(
            put("/api/v1/proactive/messages/1/read").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent());
  }

  @Test
  void markAllAsRead_returnsNoContent() throws Exception {
    mockAuth("proactive:read");
    doNothing().when(messageRepository).markAllAsRead(anyLong());

    mockMvc
        .perform(
            put("/api/v1/proactive/messages/read-all")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent());
  }

  @Test
  void getMessages_withoutAuth_returns401or403() throws Exception {
    mockMvc
        .perform(get("/api/v1/proactive/messages"))
        .andExpect(
            result -> {
              int status = result.getResponse().getStatus();
              assert status == 401 || status == 403;
            });
  }
}
