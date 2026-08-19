package com.smartfirehub.platform.controller;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.platform.dto.PlatformMeResponse;
import com.smartfirehub.platform.dto.PlatformTokenResponse;
import com.smartfirehub.platform.service.PlatformAuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 평면 인증 엔드포인트.
 *
 * <p>{@code /login} 과 {@code /refresh} 만 public 이다({@code SecurityConfig}). 나머지는 플랫폼 토큰이
 * 필요하며, {@code PlatformPlaneFilter} 가 테넌트 토큰의 접근을 평면 단위로 막는다.
 *
 * <p>{@code /me} 와 {@code /logout} 에는 {@code @RequirePermission} 을 걸지 않는다 — 세션 확인과
 * 로그아웃은 어떤 권한 조합의 운영자든 할 수 있어야 한다(테넌트 평면의 {@code /auth/me} 와 같은 이유).
 */
@RestController
@RequestMapping("/api/platform/auth")
public class PlatformAuthController {

  /**
   * 운영자 리프레시 쿠키. 이름과 path 를 테넌트 평면({@code refreshToken} @ {@code /api/v1/auth})과
   * <b>모두</b> 분리한다.
   *
   * <p>왜 분리해야 하는가: 같은 호스트에서 firehub-web 과 firehub-admin 을 동시에 열면, 이름이 같을
   * 경우 나중 로그인이 앞의 쿠키를 덮어써 서로의 세션을 끊는다. path 까지 분리하면 브라우저가 각
   * 평면의 갱신 요청에만 해당 쿠키를 보낸다 — 운영자 쿠키가 테넌트 API 로 새지 않는다.
   */
  private static final String PLATFORM_REFRESH_COOKIE = "platformRefreshToken";

  private static final String PLATFORM_REFRESH_PATH = "/api/platform/auth";

  private final PlatformAuthService platformAuthService;
  private final JwtProperties jwtProperties;
  private final boolean cookieSecure;

  public PlatformAuthController(
      PlatformAuthService platformAuthService,
      JwtProperties jwtProperties,
      @Value("${app.cookie.secure:true}") boolean cookieSecure) {
    this.platformAuthService = platformAuthService;
    this.jwtProperties = jwtProperties;
    this.cookieSecure = cookieSecure;
  }

  /** 운영자 로그인. 리프레시 토큰은 본문에서 비우고 HttpOnly 쿠키로만 내려보낸다. */
  @PostMapping("/login")
  public ResponseEntity<PlatformTokenResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    PlatformTokenResponse token = platformAuthService.login(request);
    addRefreshCookie(response, token.refreshToken());
    return ResponseEntity.ok(withoutRefreshToken(token));
  }

  /** 운영자 토큰 갱신. 쿠키가 없으면 401 — 평면 검사는 서비스가 한다. */
  @PostMapping("/refresh")
  public ResponseEntity<PlatformTokenResponse> refresh(
      @CookieValue(name = PLATFORM_REFRESH_COOKIE, required = false) String refreshToken,
      HttpServletResponse response) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    PlatformTokenResponse token = platformAuthService.refresh(refreshToken);
    addRefreshCookie(response, token.refreshToken());
    return ResponseEntity.ok(withoutRefreshToken(token));
  }

  /** 운영자 로그아웃. 이 사용자의 리프레시 토큰을 전부 폐기하고 쿠키를 지운다. */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(Authentication authentication, HttpServletResponse response) {
    Long userId = (Long) authentication.getPrincipal();
    platformAuthService.logout(userId);
    clearRefreshCookie(response);
    return ResponseEntity.noContent().build();
  }

  /** 현재 운영자 세션. 프런트가 메뉴를 권한 기준으로 구성하는 데 쓴다. */
  @GetMapping("/me")
  public ResponseEntity<PlatformMeResponse> me(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return ResponseEntity.ok(platformAuthService.me(userId));
  }

  /** 리프레시 토큰은 쿠키로만 전달한다 — 본문에 실으면 JS 가 읽을 수 있게 된다. */
  private PlatformTokenResponse withoutRefreshToken(PlatformTokenResponse token) {
    return new PlatformTokenResponse(
        token.accessToken(), null, token.tokenType(), token.expiresIn(), token.permissions());
  }

  private void addRefreshCookie(HttpServletResponse response, @NonNull String refreshToken) {
    ResponseCookie cookie =
        ResponseCookie.from(PLATFORM_REFRESH_COOKIE, refreshToken)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path(PLATFORM_REFRESH_PATH)
            .maxAge(jwtProperties.refreshExpiration() / 1000)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  private void clearRefreshCookie(HttpServletResponse response) {
    ResponseCookie cookie =
        ResponseCookie.from(PLATFORM_REFRESH_COOKIE, "")
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path(PLATFORM_REFRESH_PATH)
            .maxAge(0)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }
}
