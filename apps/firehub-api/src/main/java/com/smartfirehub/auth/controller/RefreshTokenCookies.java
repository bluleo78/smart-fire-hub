package com.smartfirehub.auth.controller;

import com.smartfirehub.global.security.JwtProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * refresh 토큰 쿠키 작성기.
 *
 * <p>왜 AuthController 밖으로 뺐나(WD-2 리뷰 지적 2): 비밀번호 변경({@code PUT /users/me/password})도 새
 * refresh 쿠키를 내려야 한다. 쿠키 속성(HttpOnly·Secure·SameSite·Path·Max-Age)을 두 컨트롤러에 복사하면
 * 한쪽만 바뀌는 순간 브라우저가 이름은 같고 Path 가 다른 쿠키 두 개를 갖게 되어 refresh 가 엉뚱한 쿠키를
 * 보낼 수 있다. Path 는 요청 경로와 달라도 된다 — 브라우저는 응답의 Set-Cookie Path 를 그대로 따른다.
 */
@Component
public class RefreshTokenCookies {

  /** 쿠키 이름 — {@code /auth/refresh} 의 {@code @CookieValue} 와 같아야 한다. */
  public static final String NAME = "refreshToken";

  /** refresh 쿠키는 인증 경로로만 보낸다(일반 API 요청에 실리지 않게). */
  public static final String PATH = "/api/v1/auth";

  private final JwtProperties jwtProperties;
  private final boolean cookieSecure;

  public RefreshTokenCookies(
      JwtProperties jwtProperties, @Value("${app.cookie.secure:true}") boolean cookieSecure) {
    this.jwtProperties = jwtProperties;
    this.cookieSecure = cookieSecure;
  }

  /** refresh 토큰을 쿠키로 설정한다. */
  public void set(HttpServletResponse response, @NonNull String refreshToken) {
    write(response, refreshToken, jwtProperties.refreshExpiration() / 1000);
  }

  /** refresh 쿠키를 지운다(로그아웃). */
  public void clear(HttpServletResponse response) {
    write(response, "", 0);
  }

  private void write(HttpServletResponse response, String value, long maxAgeSeconds) {
    ResponseCookie cookie =
        ResponseCookie.from(NAME, value)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path(PATH)
            .maxAge(maxAgeSeconds)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }
}
