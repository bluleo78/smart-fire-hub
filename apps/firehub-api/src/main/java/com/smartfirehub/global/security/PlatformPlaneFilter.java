package com.smartfirehub.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 평면 교차 차단. 인증된 요청의 평면과 경로의 평면이 다르면 403 으로 끊는다.
 *
 * <p>왜 권한 검사로 충분하지 않은가: {@code permission} 카탈로그는 전역 공유라 누군가 테넌트 롤에
 * {@code platform:*} 권한을 부여하는 것이 문법적으로 가능하다. 그 순간 권한 코드 기반 방어는 뚫린다.
 * 반대 방향도 새는데, 플랫폼 토큰은 테넌트 권한이 비어 있어 대개 403 이 되지만 <b>권한을 요구하지
 * 않는</b> 테넌트 엔드포인트(예: {@code /api/v1/auth/memberships}, {@code /auth/me})는 그냥 통과한다.
 * 그래서 권한이 아니라 <b>평면</b>으로 막는다.
 *
 * <p>핸들러 조회 전에 도는 필터로 구현한 이유: 존재하지 않는 경로에 대해서도 평면 판정이 나야
 * fail-closed 다. 인터셉터(핸들러 조회 후)로 만들면 없는 경로가 404 를 돌려주어 어떤 운영자
 * 엔드포인트가 존재하는지 알려주는 오라클이 된다.
 *
 * <p>{@code @Component} 를 붙이지 않는다 — Spring Boot 가 {@code @Component} 필터를 서블릿 체인에도
 * 자동 등록해 시큐리티 체인과 합쳐 두 번 돌기 때문이다. {@code SecurityConfig} 가
 * {@code addFilterAfter(new PlatformPlaneFilter(), JwtAuthenticationFilter.class)} 로 한 번만 넣는다.
 * 반드시 {@link JwtAuthenticationFilter} <b>뒤</b>여야 한다 — 앞이면 인증이 아직 없어 무조건 통과한다.
 */
public class PlatformPlaneFilter extends OncePerRequestFilter {

  private static final String PLATFORM_PREFIX = "/api/platform/";

  /**
   * 평면 검사 면제 경로. {@code SecurityConfig} 의 플랫폼 permitAll 목록과 <b>1:1 로 일치</b>해야 한다.
   *
   * <p>왜 면제가 필요한가: 이 두 경로는 인증 없이 호출되는 것이 정상이지만, 같은 호스트에서
   * firehub-web 을 열어 둔 브라우저는 살아 있는 <b>테넌트</b> Bearer 를 함께 보낸다. 그러면
   * {@code auth != null} 인데 평면이 어긋나 403 이 되고, 운영자가 로그인 자체를 못 한다 — 실제로
   * 밟게 되는 경로다.
   */
  private static final Set<String> PLANE_CHECK_EXEMPT =
      Set.of("/api/platform/auth/login", "/api/platform/auth/refresh");

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && !PLANE_CHECK_EXEMPT.contains(request.getRequestURI())) {
      boolean platformRequest = request.getRequestURI().startsWith(PLATFORM_PREFIX);
      boolean platformToken = auth instanceof PlatformAuthentication;
      if (platformRequest != platformToken) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"평면이 일치하지 않습니다\"}");
        return;
      }
    }
    filterChain.doFilter(request, response);
  }
}
