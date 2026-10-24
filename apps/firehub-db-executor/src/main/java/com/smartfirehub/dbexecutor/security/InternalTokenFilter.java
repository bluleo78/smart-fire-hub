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
 * <token>} 으로 보호한다. 자격은 범위가 다르다(스펙 §3.4).
 *
 * <ul>
 *   <li>내부 토큰: 모든 창구.
 *   <li>적재 토큰: {@code POST /execute/load} 한 곳만(경로는 정확히 일치, 메서드는 POST) — Python 실행기가 SQL·조회 창구를 쓰지
 *       못하게 한다.
 *   <li>{@code Script <token>}: 스크립트 1회용 토큰 자리. 검증은 이후 작업에서 구현하므로 지금은 모두 401.
 * </ul>
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

  /** 적재 토큰이 허용되는 유일한 경로 — 정확한 URI 일치만 인정한다(변형 표기는 거부). */
  private static final String LOAD_PATH = "/execute/load";

  private final String expectedToken;
  private final String loadToken;

  public InternalTokenFilter(DbExecutorProperties properties) {
    this.expectedToken = properties.internalToken();
    this.loadToken = properties.loadToken();
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
    String header = request.getHeader("Authorization");
    // Script 스킴은 matches 의 접두어 검사("Internal ")에서 걸러져 어느 쪽 자격에도 닿지 않는다.
    boolean allowed =
        matches(header, expectedToken)
            || (LOAD_PATH.equals(request.getRequestURI())
                && "POST".equals(request.getMethod())
                && matches(header, loadToken));
    if (allowed) {
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
