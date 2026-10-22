package com.smartfirehub.dbexecutor.security;

import com.smartfirehub.dbexecutor.config.DbExecutorProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /actuator/health} 를 뺀 모든 요청(특히 {@code /execute/**})을 {@code Authorization: Internal
 * <token>} 으로 보호한다.
 *
 * <p>api 의 {@code InternalTokens.matches} 와 같은 규칙(접두어 확인·빈 토큰 거부·상수 시간 비교)이다.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

  private static final String PREFIX = "Internal ";
  private static final String UNAUTHORIZED_BODY =
      "{\"success\":false,\"error\":\"인증되지 않은 요청입니다.\"}";

  /** 토큰 없이 허용하는 유일한 경로. */
  private static final String HEALTH_PATH = "/actuator/health";

  private final String expectedToken;

  public InternalTokenFilter(DbExecutorProperties properties) {
    this.expectedToken = properties.internalToken();
  }

  /**
   * 기본 거부(deny by default): health 엔드포인트의 정확한 원문 경로만 토큰 없이 통과시키고 나머지는 모두 인증한다. 경로 접두어({@code
   * /execute})로 보호 대상을 고르면 {@code /%65xecute}·{@code /execute;x} 처럼 Spring MVC 는 같은 핸들러로 풀지만 원문은 다른
   * 경로가 우회로가 된다 — 그래서 허용 목록 방식이다. health 의 변형 표기는 401 로 안전하게 실패한다.
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return HEALTH_PATH.equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (matches(request.getHeader("Authorization"), expectedToken)) {
      chain.doFilter(request, response);
      return;
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write(UNAUTHORIZED_BODY);
  }

  /** 헤더가 {@code "Internal " + expected} 인지 상수 시간으로 비교한다(응답 시간으로 토큰을 추측하지 못하게). */
  static boolean matches(String header, String expected) {
    if (header == null || !header.startsWith(PREFIX)) {
      return false;
    }
    if (expected == null || expected.isBlank()) {
      return false;
    }
    return MessageDigest.isEqual(
        header.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8),
        expected.getBytes(StandardCharsets.UTF_8));
  }
}
