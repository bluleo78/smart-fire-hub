package com.smartfirehub.notification.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.permission.service.PermissionService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** NotificationController WebMvcTest — SSE 스트림 엔드포인트 인증/권한 검증 */
@SuppressWarnings("null")
@WebMvcTest(NotificationController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class NotificationControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SseEmitterRegistry registry;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
    when(registry.register(1L)).thenReturn(new SseEmitter());
  }

  /** 인증 토큰과 dataset:read 권한이 있으면 SSE 스트림에 200 OK */
  @Test
  void subscribe_withPermission_returnsOk() throws Exception {
    mockMvc
        .perform(get("/api/v1/notifications/stream").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }

  /** 인증 토큰 없이 접근하면 401 Unauthorized */
  @Test
  void subscribe_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/notifications/stream")).andExpect(status().isUnauthorized());
  }
}
