package com.smartfirehub.auth.controller;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.SelectTenantRequest;
import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.dto.SignupStatusResponse;
import com.smartfirehub.auth.dto.TokenResponse;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.global.security.AllowedDuringPasswordChange;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private static final String REFRESH_TOKEN_COOKIE = RefreshTokenCookies.NAME;

  private final AuthService authService;
  private final PermissionService permissionService;
  private final RefreshTokenCookies refreshTokenCookies;

  public AuthController(
      AuthService authService,
      PermissionService permissionService,
      RefreshTokenCookies refreshTokenCookies) {
    this.authService = authService;
    this.permissionService = permissionService;
    this.refreshTokenCookies = refreshTokenCookies;
  }

  @PostMapping("/signup")
  @AllowedDuringPasswordChange
  public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request) {
    UserResponse user = authService.signup(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(user);
  }

  /** 공개 가입 열림 여부. 로그인 전 화면이 호출하므로 permitAll 이다(SecurityConfig). */
  @GetMapping("/signup-status")
  @AllowedDuringPasswordChange
  public ResponseEntity<SignupStatusResponse> signupStatus() {
    return ResponseEntity.ok(new SignupStatusResponse(authService.isSignupOpen()));
  }

  @PostMapping("/login")
  @AllowedDuringPasswordChange
  public ResponseEntity<TokenResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    TokenResponse token = authService.login(request);
    refreshTokenCookies.set(response, token.refreshToken());
    // 필드를 다시 조립하지 않는다 — 새 필드(mustChangePassword)가 조용히 빠지는 것을 막는다.
    TokenResponse body = token.withoutRefreshToken();
    return ResponseEntity.ok(body);
  }

  @PostMapping("/refresh")
  @AllowedDuringPasswordChange
  public ResponseEntity<TokenResponse> refresh(
      @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
      HttpServletResponse response) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    TokenResponse token = authService.refresh(refreshToken);
    refreshTokenCookies.set(response, token.refreshToken());
    // 필드를 다시 조립하지 않는다 — 새 필드(mustChangePassword)가 조용히 빠지는 것을 막는다.
    TokenResponse body = token.withoutRefreshToken();
    return ResponseEntity.ok(body);
  }

  @PostMapping("/logout")
  @AllowedDuringPasswordChange
  public ResponseEntity<Void> logout(Authentication authentication, HttpServletResponse response) {
    Long userId = (Long) authentication.getPrincipal();
    authService.logout(userId);
    refreshTokenCookies.clear(response);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  @AllowedDuringPasswordChange
  public ResponseEntity<UserResponse> me(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    UserResponse user = authService.getCurrentUser(userId);
    return ResponseEntity.ok(user);
  }

  /**
   * 현재 세션 사용자에게 부여된 권한 코드 목록을 반환한다.
   *
   * <p>ai-agent 가 MCP 파괴적 도구(delete_dataset 등)를 사용자 권한 기준으로 필터링(fail-closed)하기 위해 사용한다. 내부 서비스 통신
   * 경로에서도 `Authorization: Internal <token>` + `X-On-Behalf-Of: userId` 헤더로 호출되며,
   * JwtAuthenticationFilter 가 해당 헤더를 처리하여 Authentication 에 userId 를 주입한다.
   */
  @GetMapping("/me/permissions")
  public ResponseEntity<List<String>> getMyPermissions(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    Set<String> codes = permissionService.getUserPermissions(userId);
    return ResponseEntity.ok(List.copyOf(codes));
  }

  /** 선택 가능한 테넌트 목록. 테넌트 미선택 토큰으로도 호출할 수 있어야 하므로 권한을 요구하지 않는다. */
  @GetMapping("/memberships")
  @AllowedDuringPasswordChange
  public ResponseEntity<List<MembershipResponse>> memberships(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(authService.getMemberships(userId));
  }

  /**
   * 활성 테넌트 선택/전환. 액세스·리프레시 토큰을 모두 재발급한다.
   *
   * <p>테넌트 미선택 토큰으로도 호출할 수 있어야 하므로 권한을 요구하지 않는다.
   */
  @PostMapping("/select-tenant")
  @AllowedDuringPasswordChange
  public ResponseEntity<TokenResponse> selectTenant(
      Authentication authentication,
      @Valid @RequestBody SelectTenantRequest request,
      HttpServletResponse response) {
    Long userId = (Long) authentication.getPrincipal();
    TokenResponse token = authService.selectTenant(userId, request.tenantId());
    refreshTokenCookies.set(response, token.refreshToken());
    // 필드를 다시 조립하지 않는다 — 새 필드(mustChangePassword)가 조용히 빠지는 것을 막는다.
    TokenResponse body = token.withoutRefreshToken();
    return ResponseEntity.ok(body);
  }
}
