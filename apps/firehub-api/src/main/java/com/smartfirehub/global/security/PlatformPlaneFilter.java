package com.smartfirehub.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 평면 교차 차단. 인증된 요청의 평면과 경로의 평면이 다르면 403 으로 끊는다.
 *
 * <p>왜 권한 검사로 충분하지 않은가: {@code permission} 카탈로그는 전역 공유라 누군가 테넌트 롤에 {@code platform:*} 권한을 부여하는 것이
 * 문법적으로 가능하다. 그 순간 권한 코드 기반 방어는 뚫린다. 반대 방향도 새는데, 플랫폼 토큰은 테넌트 권한이 비어 있어 대개 403 이 되지만 <b>권한을 요구하지
 * 않는</b> 테넌트 엔드포인트(예: {@code /api/v1/auth/memberships}, {@code /auth/me})는 그냥 통과한다. 그래서 권한이 아니라
 * <b>평면</b>으로 막는다.
 *
 * <p>핸들러 조회 전에 도는 필터로 구현한 이유: 존재하지 않는 경로에 대해서도 평면 판정이 나야 fail-closed 다. 인터셉터(핸들러 조회 후)로 만들면 없는 경로가
 * 404 를 돌려주어 어떤 운영자 엔드포인트가 존재하는지 알려주는 오라클이 된다.
 *
 * <p>{@code @Component} 를 붙이지 않는다 — Spring Boot 가 {@code @Component} 필터를 서블릿 체인에도 자동 등록해 시큐리티 체인과
 * 합쳐 두 번 돌기 때문이다. {@code SecurityConfig} 가 {@code addFilterAfter(new PlatformPlaneFilter(),
 * JwtAuthenticationFilter.class)} 로 한 번만 넣는다. 반드시 {@link JwtAuthenticationFilter} <b>뒤</b>여야 한다 —
 * 앞이면 인증이 아직 없어 무조건 통과한다.
 */
public class PlatformPlaneFilter extends OncePerRequestFilter {

  /**
   * 평면 판정 매처 — {@code SecurityConfig} 와 같은 패턴 문자열({@link PlatformAuthPaths})을 쓰고, 디코딩된 애플리케이션 내 경로로
   * 대조한다(DispatcherServlet 이 루트에 매핑돼 있어 시큐리티·MVC 판정과 어긋나지 않는다). 그래서 {@code /api/%70latform/tenants}
   * 도 걸리며, 원본 URI 를 직접 {@code startsWith} 로 대조하면 이 우회가 생기므로 쓰지 않는다. 매처 동작의 어긋남은 {@code
   * PlatformPlaneIsolationTest} 가 잡는다.
   */
  private static final RequestMatcher PLATFORM_PLANE = matcher(PlatformAuthPaths.PLATFORM_PATTERN);

  /**
   * 평면 검사 면제 경로. {@link PlatformAuthPaths} 를 통해 {@code SecurityConfig} 의 permitAll 목록과 <b>같은
   * 출처</b>를 공유한다 — 한쪽만 고치는 실수가 아예 불가능해진다.
   *
   * <p>왜 면제가 필요한가: 이 공개 경로(로그인·갱신·부트스트랩 가입·가입 가능 여부)는 인증 없이 호출되는 것이 정상이지만, 같은 호스트에서 firehub-web 을
   * 열어 둔 브라우저는 살아 있는 <b>테넌트</b> Bearer 를 함께 보낸다. 그러면 {@code auth != null} 인데 평면이 어긋나 403 이 되고, 운영자가
   * 로그인 자체를 못 한다 — 실제로 밟게 되는 경로다.
   */
  private static final List<RequestMatcher> PLANE_CHECK_EXEMPT =
      PlatformAuthPaths.PUBLIC_PATTERNS.stream().map(PlatformPlaneFilter::matcher).toList();

  /** 패턴 문자열로 {@link PathPatternRequestMatcher} 를 만든다 — 두 매처가 같은 생성 방식을 쓰게 한다. */
  private static RequestMatcher matcher(String pattern) {
    return PathPatternRequestMatcher.withDefaults().matcher(pattern);
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && !isExempt(request)) {
      boolean platformRequest = PLATFORM_PLANE.matches(request);
      boolean platformToken = PlatformAuthentication.isCurrent();
      if (platformRequest != platformToken) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        // charset 을 명시해야 한다. 다른 에러 본문은 Jackson 컨버터를 지나며 UTF-8 이 붙지만
        // 여기는 직접 쓰므로 생략하면 컨테이너 기본 인코딩(ISO-8859-1)으로 나가 한글이 깨진다.
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"평면이 일치하지 않습니다\"}");
        return;
      }
    }
    filterChain.doFilter(request, response);
  }

  /** 면제 경로 여부. 목록이 네 개뿐이라 순회로 충분하다. */
  private static boolean isExempt(HttpServletRequest request) {
    for (RequestMatcher exempt : PLANE_CHECK_EXEMPT) {
      if (exempt.matches(request)) return true;
    }
    return false;
  }
}
