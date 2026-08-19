package com.smartfirehub.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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

  /**
   * 평면 판정을 <b>{@code SecurityConfig} 와 같은 Ant 패턴 문법·같은 디코딩된 경로</b>로 한다.
   *
   * <p>직접 {@code getRequestURI().startsWith("/api/platform/")} 로 대조하면 안 된다. {@code
   * getRequestURI()} 는 <b>디코딩되지 않은</b> 원본 URI 인데, 이 스택의 다른 모든 판정
   * ({@code SecurityConfig} 의 {@code requestMatchers}, MVC 핸들러 매핑)은 <b>디코딩된</b> 경로를
   * 본다. 그래서 {@code GET /api/%70latform/tenants} 는 이 필터에게는 "플랫폼 경로가 아님"으로,
   * 시큐리티와 MVC 에게는 "플랫폼 경로"로 보인다 — 테넌트 토큰이 평면 검사를 건너뛰고
   * 운영자 컨트롤러에 도달하는 우회로다({@code %70} = {@code p}, StrictHttpFirewall 차단 대상
   * 아님). 남는 방어는 권한 검사뿐인데, 이 클래스의 존재 이유가 바로 "권한 검사로는 부족하다"다.
   *
   * <p>매처를 쓰면 {@code server.servlet.context-path} 설정(원본 URI 에는 컨텍스트 경로가 포함되지만
   * 매처는 그것을 제외한 경로를 본다)과 대소문자·트레일링 슬래시 처리까지 시큐리티 설정과 자동으로
   * 일치한다 — 아래 면제 목록이 {@code SecurityConfig} 의 permitAll 목록과 "1:1 로 일치"해야 하는
   * 불변식을 지키기 쉽게 만든다.
   *
   * <p>단, {@code HttpSecurity.requestMatchers(String)} 이 만드는 매처는 이 클래스가 쓰는
   * {@link AntPathRequestMatcher} 와 <b>같은 구현이 아니다</b>(기본 루트 서블릿 매핑에서는 동작이
   * 같다). 따라서 면제 목록이 permitAll 목록과 1:1 이라는 보장은 여전히 <b>사람이</b> 지킨다 —
   * 그 사실이 {@code PlatformPlaneIsolationTest.platformAuthPathsAreExemptFromPlaneCheck} 로
   * 검증되는 이유다. 한쪽만 고치면 그 테스트가 깨진다.
   */
  private static final RequestMatcher PLATFORM_PLANE =
      new AntPathRequestMatcher("/api/platform/**");

  /**
   * 평면 검사 면제 경로. {@code SecurityConfig} 의 플랫폼 permitAll 목록과 <b>1:1 로 일치</b>해야 한다.
   *
   * <p>왜 면제가 필요한가: 이 두 경로는 인증 없이 호출되는 것이 정상이지만, 같은 호스트에서
   * firehub-web 을 열어 둔 브라우저는 살아 있는 <b>테넌트</b> Bearer 를 함께 보낸다. 그러면
   * {@code auth != null} 인데 평면이 어긋나 403 이 되고, 운영자가 로그인 자체를 못 한다 — 실제로
   * 밟게 되는 경로다.
   */
  private static final List<RequestMatcher> PLANE_CHECK_EXEMPT =
      List.of(
          new AntPathRequestMatcher("/api/platform/auth/login"),
          new AntPathRequestMatcher("/api/platform/auth/refresh"));

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && !isExempt(request)) {
      boolean platformRequest = PLATFORM_PLANE.matches(request);
      boolean platformToken = auth instanceof PlatformAuthentication;
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

  /** 면제 경로 여부. 목록이 두 개뿐이라 순회로 충분하다. */
  private static boolean isExempt(HttpServletRequest request) {
    for (RequestMatcher exempt : PLANE_CHECK_EXEMPT) {
      if (exempt.matches(request)) return true;
    }
    return false;
  }
}
