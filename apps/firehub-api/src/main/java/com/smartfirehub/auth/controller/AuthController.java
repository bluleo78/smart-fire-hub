package com.smartfirehub.auth.controller;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private static final String REFRESH_TOKEN_COOKIE = "refreshToken";
  private static final String REFRESH_TOKEN_PATH = "/api/v1/auth";

  private final AuthService authService;
  private final JwtProperties jwtProperties;
  private final PermissionService permissionService;
  private final boolean cookieSecure;

  public AuthController(
      AuthService authService,
      JwtProperties jwtProperties,
      PermissionService permissionService,
      @org.springframework.beans.factory.annotation.Value("${app.cookie.secure:true}")
          boolean cookieSecure) {
    this.authService = authService;
    this.cookieSecure = cookieSecure;
    this.jwtProperties = jwtProperties;
    this.permissionService = permissionService;
  }

  @PostMapping("/signup")
  public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request) {
    UserResponse user = authService.signup(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(user);
  }

  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    TokenResponse token = authService.login(request);
    addRefreshTokenCookie(response, token.refreshToken());
    TokenResponse body =
        new TokenResponse(token.accessToken(), null, token.tokenType(), token.expiresIn());
    return ResponseEntity.ok(body);
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokenResponse> refresh(
      @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
      HttpServletResponse response) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    TokenResponse token = authService.refresh(refreshToken);
    addRefreshTokenCookie(response, token.refreshToken());
    TokenResponse body =
        new TokenResponse(token.accessToken(), null, token.tokenType(), token.expiresIn());
    return ResponseEntity.ok(body);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(Authentication authentication, HttpServletResponse response) {
    Long userId = (Long) authentication.getPrincipal();
    authService.logout(userId);
    clearRefreshTokenCookie(response);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  public ResponseEntity<UserResponse> me(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    UserResponse user = authService.getCurrentUser(userId);
    return ResponseEntity.ok(user);
  }

  /**
   * 현재 세션 사용자에게 부여된 권한 코드 목록을 반환한다.
   *
   * <p>ai-agent 가 MCP 파괴적 도구(delete_dataset 등)를 사용자 권한 기준으로
   * 필터링(fail-closed)하기 위해 사용한다. 내부 서비스 통신 경로에서도
   * `Authorization: Internal <token>` + `X-On-Behalf-Of: userId` 헤더로 호출되며,
   * JwtAuthenticationFilter 가 해당 헤더를 처리하여 Authentication 에 userId 를 주입한다.
   */
  @GetMapping("/me/permissions")
  public ResponseEntity<List<String>> getMyPermissions(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    Set<String> codes = permissionService.getUserPermissions(userId);
    return ResponseEntity.ok(List.copyOf(codes));
  }

  private void addRefreshTokenCookie(HttpServletResponse response, @NonNull String refreshToken) {
    ResponseCookie cookie =
        ResponseCookie.from(REFRESH_TOKEN_COOKIE, refreshToken)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path(REFRESH_TOKEN_PATH)
            .maxAge(jwtProperties.refreshExpiration() / 1000)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  private void clearRefreshTokenCookie(HttpServletResponse response) {
    ResponseCookie cookie =
        ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path(REFRESH_TOKEN_PATH)
            .maxAge(0)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }
}
