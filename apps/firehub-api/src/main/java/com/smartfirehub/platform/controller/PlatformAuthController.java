package com.smartfirehub.platform.controller;

import com.smartfirehub.auth.dto.LoginRequest;
import com.smartfirehub.auth.dto.SignupRequest;
import com.smartfirehub.auth.service.AuthService;
import com.smartfirehub.auth.service.LoginIpGate;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.platform.dto.PlatformBootstrapStatusResponse;
import com.smartfirehub.platform.dto.PlatformMeResponse;
import com.smartfirehub.platform.dto.PlatformTokenResponse;
import com.smartfirehub.platform.service.PlatformAuthService;
import com.smartfirehub.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
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
 * <p>{@code /login}·{@code /refresh}·{@code /signup}·{@code /bootstrap-status} 만 public 이다({@code
 * SecurityConfig}). {@code /signup} 은 사용자가 0명일 때만 통과하는 최초 운영자 가입이다(WD-749). 나머지는 플랫폼 토큰이 필요하며,
 * {@code PlatformPlaneFilter} 가 테넌트 토큰의 접근을 평면 단위로 막는다.
 *
 * <p>{@code /me} 와 {@code /logout} 에는 {@code @RequirePermission} 을 걸지 않는다 — 세션 확인과 로그아웃은 어떤 권한 조합의
 * 운영자든 할 수 있어야 한다(테넌트 평면의 {@code /auth/me} 와 같은 이유).
 */
@RestController
@RequestMapping("/api/platform/auth")
public class PlatformAuthController {

  /**
   * 운영자 리프레시 쿠키. 이름과 path 를 테넌트 평면({@code refreshToken} @ {@code /api/v1/auth})과 <b>모두</b> 분리한다.
   *
   * <p>왜 분리해야 하는가: 같은 호스트에서 firehub-web 과 firehub-admin 을 동시에 열면, 이름이 같을 경우 나중 로그인이 앞의 쿠키를 덮어써 서로의
   * 세션을 끊는다. path 까지 분리하면 브라우저가 각 평면의 갱신 요청에만 해당 쿠키를 보낸다 — 운영자 쿠키가 테넌트 API 로 새지 않는다.
   */
  private static final String PLATFORM_REFRESH_COOKIE = "platformRefreshToken";

  private static final String PLATFORM_REFRESH_PATH = "/api/platform/auth";

  private final PlatformAuthService platformAuthService;
  private final AuthService authService;
  private final JwtProperties jwtProperties;
  private final boolean cookieSecure;

  /** 같은 IP 키 로그인 줄 세우기(WD-630) — 설계는 {@link LoginIpGate} 참고. */
  private final LoginIpGate loginIpGate;

  public PlatformAuthController(
      PlatformAuthService platformAuthService,
      AuthService authService,
      JwtProperties jwtProperties,
      @Value("${app.cookie.secure:true}") boolean cookieSecure,
      LoginIpGate loginIpGate) {
    this.platformAuthService = platformAuthService;
    this.authService = authService;
    this.jwtProperties = jwtProperties;
    this.cookieSecure = cookieSecure;
    this.loginIpGate = loginIpGate;
  }

  /** 운영자 로그인. 리프레시 토큰은 본문에서 비우고 HttpOnly 쿠키로만 내려보낸다. */
  @PostMapping("/login")
  public ResponseEntity<PlatformTokenResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    // 같은 IP 키 줄 세우기는 트랜잭션 밖 LoginIpGate 가 한다(WD-630).
    PlatformTokenResponse token = loginIpGate.run(() -> platformAuthService.login(request));
    addRefreshCookie(response, token.refreshToken());
    return ResponseEntity.ok(withoutRefreshToken(token));
  }

  /**
   * 최초 운영자 가입 가능 여부. 로그인 전 화면이 호출하므로 public 이다 — 사용자 존재 여부 한 비트만 알려 준다(웹 {@code
   * /auth/signup-status} 와 같은 정보).
   */
  @GetMapping("/bootstrap-status")
  public ResponseEntity<PlatformBootstrapStatusResponse> bootstrapStatus() {
    return ResponseEntity.ok(new PlatformBootstrapStatusResponse(authService.isSignupOpen()));
  }

  /**
   * 최초 운영자 가입 후 바로 로그인한다.
   *
   * <p>가입 규칙(사용자 0명만 허용·advisory lock 직렬화·SUPER_ADMIN 부여)은 {@link AuthService#signup} 이 그대로 가진다 —
   * 여기서 새 규칙을 만들지 않아 웹 가입과의 경쟁에서도 한 쪽만 성공한다. 이어지는 로그인은 같은 {@link LoginIpGate} 안에서 하여 IP 줄 세우기 규칙을
   * 로그인과 똑같이 적용한다.
   *
   * <p>가입과 로그인은 한 트랜잭션이 아니다 — 가입 커밋 뒤 로그인이 실패(IP 제한 429 등)하면 계정은 남고 세션만 없다. 일반 로그인으로 이어 갈 수 있어 의도된
   * 동작이다. 가입 직후(로그인 전)에 부트스트랩 감사를 남기고 가입 전 로그인 실패 카운터를 비운다.
   */
  @PostMapping("/signup")
  public ResponseEntity<PlatformTokenResponse> signup(
      @Valid @RequestBody SignupRequest request, HttpServletResponse response) {
    PlatformTokenResponse token =
        loginIpGate.run(
            () -> {
              UserResponse created = authService.signup(request);
              // 로그인이 실패해도 감사는 남도록 로그인 전에 기록한다(감사·카운터 초기화).
              platformAuthService.afterBootstrapSignup(created);
              return platformAuthService.login(
                  new LoginRequest(request.username(), request.password()));
            });
    addRefreshCookie(response, token.refreshToken());
    return ResponseEntity.status(HttpStatus.CREATED).body(withoutRefreshToken(token));
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

  /**
   * 운영자 로그아웃. 이 콘솔 세션의 리프레시 패밀리만 폐기하고 쿠키를 지운다.
   *
   * <p>사용자 단위로 폐기하지 않는 이유는 {@code PlatformAuthService.logout} 주석 참고 — {@code refresh_token} 에 평면
   * 컬럼이 없어 사용자 단위 폐기는 테넌트 세션까지 끊는다.
   */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = PLATFORM_REFRESH_COOKIE, required = false) String refreshToken,
      HttpServletResponse response) {
    platformAuthService.logout(refreshToken);
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
