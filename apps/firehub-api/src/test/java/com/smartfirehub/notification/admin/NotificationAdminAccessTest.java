package com.smartfirehub.notification.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.notification.auth.OAuthStateService;
import com.smartfirehub.notification.auth.SlackOAuthService;
import com.smartfirehub.notification.auth.controller.SlackOAuthController;
import com.smartfirehub.notification.repository.NotificationOutboxRepository;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WD-433: 알림 관리 API 와 Slack 설치 URL 발급이 notification:admin 권한으로만 열리는지 검증한다.
 *
 * <p>예전에는 {@code @PreAuthorize("hasRole('ADMIN')")} 가 붙어 있었지만 메서드 보안이 켜져 있지 않아 평가되지 않았고, 로그인한 누구나
 * 남의 알림 내용을 보고 다시 보낼 수 있었다. 권한이 없으면 403 이면서 리포지토리·서비스에 손대지 않아야 한다(판정이 실행보다 먼저).
 */
@WebMvcTest({NotificationAdminController.class, SlackOAuthController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class NotificationAdminAccessTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private NotificationOutboxRepository outboxRepo;
  @MockitoBean private SlackOAuthService slackOAuthService;
  @MockitoBean private OAuthStateService oAuthStateService;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  private static final String AUTH = "Bearer test-token";

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("test-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, 1L, false)));
  }

  /** 일반 사용자(권한 코드 하나뿐)로 만든다. */
  private void asUser() {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
  }

  /** 알림 관리 권한을 가진 관리자로 만든다. */
  private void asAdmin() {
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("dataset:read", "notification:admin"));
  }

  @Test
  void stuck_withoutNotificationAdmin_returns403AndDoesNotRead() throws Exception {
    asUser();
    mockMvc
        .perform(get("/api/v1/admin/notifications/stuck").header("Authorization", AUTH))
        .andExpect(status().isForbidden());
    verify(outboxRepo, never()).findStuckPending(any());
  }

  @Test
  void retry_withoutNotificationAdmin_returns403AndDoesNotRequeue() throws Exception {
    asUser();
    mockMvc
        .perform(post("/api/v1/admin/notifications/1/retry").header("Authorization", AUTH))
        .andExpect(status().isForbidden());
    verify(outboxRepo, never()).requeueForRetry(anyLong());
  }

  @Test
  void slackAuthUrl_withoutNotificationAdmin_returns403AndIssuesNoState() throws Exception {
    asUser();
    mockMvc
        .perform(get("/api/v1/oauth/slack/auth-url").header("Authorization", AUTH))
        .andExpect(status().isForbidden());
    verify(slackOAuthService, never()).isConfigured();
    verify(oAuthStateService, never()).issue(anyLong(), any());
  }

  @Test
  void stuck_withNotificationAdmin_returns200() throws Exception {
    asAdmin();
    when(outboxRepo.findStuckPending(any())).thenReturn(List.of());
    mockMvc
        .perform(
            get("/api/v1/admin/notifications/stuck")
                .param("olderThan", "PT0S")
                .header("Authorization", AUTH))
        .andExpect(status().isOk());
  }

  /** 형식이 틀린 기간은 500(예기치 않은 오류)이 아니라 400 이고, 음수 기간(미래 기준 목록)·시각 범위를 넘는 기간도 400 이다. */
  @ParameterizedTest
  @ValueSource(strings = {"abc", "-PT5M", "PT9999999999999H"})
  void stuck_invalidOlderThan_returns400(String olderThan) throws Exception {
    asAdmin();
    mockMvc
        .perform(
            get("/api/v1/admin/notifications/stuck")
                .param("olderThan", olderThan)
                .header("Authorization", AUTH))
        .andExpect(status().isBadRequest());
    verify(outboxRepo, never()).findStuckPending(any());
  }

  @Test
  void retry_withNotificationAdmin_returns204WhenRequeued() throws Exception {
    asAdmin();
    when(outboxRepo.requeueForRetry(7L)).thenReturn(1);
    mockMvc
        .perform(post("/api/v1/admin/notifications/7/retry").header("Authorization", AUTH))
        .andExpect(status().isNoContent());
  }

  /** 되돌린 행이 없으면(없는 id·이미 보냄·취소·다른 워크스페이스) 204 가 아니라 404 + 코드로 알린다. */
  @Test
  void retry_nothingRequeued_returns404() throws Exception {
    asAdmin();
    when(outboxRepo.requeueForRetry(999999L)).thenReturn(0);
    mockMvc
        .perform(post("/api/v1/admin/notifications/999999/retry").header("Authorization", AUTH))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_RETRYABLE"));
  }

  /** 관리자는 권한 판정을 지나 설정 검사까지 간다 — 이 슬라이스는 Slack 미설정이라 400. */
  @Test
  void slackAuthUrl_withNotificationAdmin_passesPermissionCheck() throws Exception {
    asAdmin();
    when(slackOAuthService.isConfigured()).thenReturn(false);
    mockMvc
        .perform(get("/api/v1/oauth/slack/auth-url").header("Authorization", AUTH))
        .andExpect(status().isBadRequest());
    verify(slackOAuthService).isConfigured();
  }
}
