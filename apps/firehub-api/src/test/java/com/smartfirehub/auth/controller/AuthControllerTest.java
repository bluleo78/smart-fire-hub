package com.smartfirehub.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.exception.AccountLockedException;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.user.dto.UserResponse;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SuppressWarnings("null")
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RefreshTokenCookies.class})
class AuthControllerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private AuthService authService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  /** 공개 엔드포인트 — 인증 없이 열림 여부를 돌려준다(로그인 화면이 가입 링크를 숨길지 정한다). */
  @Test
  void signupStatus_isPublic() throws Exception {
    when(authService.isSignupOpen()).thenReturn(false);

    mockMvc
        .perform(get("/api/v1/auth/signup-status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.open").value(false));
  }

  /** 가입이 닫혔을 때 403 + 기계 판독용 code 가 응답 본문에 실린다. */
  @Test
  void signup_closed_returnsForbiddenWithCode() throws Exception {
    when(authService.signup(any()))
        .thenThrow(
            new CodedApiException(
                HttpStatus.FORBIDDEN, "SIGNUP_DISABLED", "계정은 워크스페이스 관리자가 추가합니다"));

    mockMvc
        .perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"a@b.io\",\"password\":\"Password123\",\"name\":\"A\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SIGNUP_DISABLED"));
  }

  @Test
  void signup_returnsCreated() throws Exception {
    SignupRequest request =
        new SignupRequest("test@example.com", "test@example.com", "Password123", "Test User");
    UserResponse response =
        new UserResponse(
            1L, "test@example.com", "test@example.com", "Test User", true, LocalDateTime.now());

    when(authService.signup(any(SignupRequest.class))).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value("test@example.com"))
        .andExpect(jsonPath("$.name").value("Test User"));
  }

  @Test
  void signup_invalidEmail_returnsBadRequest() throws Exception {
    SignupRequest request = new SignupRequest("not-email", "not-email", "Password123", "Test User");

    mockMvc
        .perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void login_returnsOkWithCookie() throws Exception {
    LoginRequest request = new LoginRequest("test@example.com", "Password123");
    TokenResponse tokenResponse =
        new TokenResponse("access-token", "refresh-token", "Bearer", 1800);

    when(authService.login(any(LoginRequest.class))).thenReturn(tokenResponse);
    when(jwtProperties.refreshExpiration()).thenReturn(604800000L);

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("access-token"))
        .andExpect(jsonPath("$.refreshToken").doesNotExist())
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(header().exists("Set-Cookie"));
  }

  @Test
  void refresh_withCookie_returnsOk() throws Exception {
    TokenResponse tokenResponse = new TokenResponse("new-access", "new-refresh", "Bearer", 1800);

    when(authService.refresh(eq("some-refresh-token"))).thenReturn(tokenResponse);
    when(jwtProperties.refreshExpiration()).thenReturn(604800000L);

    mockMvc
        .perform(
            post("/api/v1/auth/refresh").cookie(new Cookie("refreshToken", "some-refresh-token")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("new-access"))
        .andExpect(header().exists("Set-Cookie"));
  }

  @Test
  void refresh_withoutCookie_returnsUnauthorized() throws Exception {
    mockMvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isUnauthorized());
  }

  @Test
  void login_accountLocked_returns429() throws Exception {
    LoginRequest request = new LoginRequest("locked@example.com", "Password123");

    when(authService.login(any(LoginRequest.class)))
        .thenThrow(
            new AccountLockedException("Too many failed login attempts. Please try again later."));

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.status").value(429))
        .andExpect(jsonPath("$.error").value("Too Many Requests"));
  }

  @Test
  void logout_returnsNoContent() throws Exception {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));

    mockMvc
        .perform(post("/api/v1/auth/logout").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent())
        .andExpect(header().exists("Set-Cookie"));

    verify(authService).logout(1L);
  }

  /**
   * /me/permissions 는 세션 사용자의 권한 코드 목록을 반환한다. ai-agent 가 MCP 파괴 도구 필터링에 사용하는 경로이므로 JWT/Internal 인증
   * 어느 쪽에서도 동작해야 한다. 여기서는 JWT 경로를 검증한다.
   */
  @Test
  void getMyPermissions_returnsCodes() throws Exception {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(42L, null, false)));
    when(permissionService.getUserPermissions(42L))
        .thenReturn(Set.of("dataset:read", "dataset:delete"));

    mockMvc
        .perform(get("/api/v1/auth/me/permissions").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(2));
  }
}
