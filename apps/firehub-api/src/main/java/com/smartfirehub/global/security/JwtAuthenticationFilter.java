package com.smartfirehub.global.security;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.tenant.dto.MembershipResponse;
import com.smartfirehub.tenant.repository.MembershipRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtTokenProvider jwtTokenProvider;
  private final PermissionService permissionService;
  private final MembershipRepository membershipRepository;
  private final String internalToken;

  public JwtAuthenticationFilter(
      JwtTokenProvider jwtTokenProvider,
      @Lazy PermissionService permissionService,
      @Lazy MembershipRepository membershipRepository,
      @Value("${agent.internal-token:}") String internalToken) {
    this.jwtTokenProvider = jwtTokenProvider;
    this.permissionService = permissionService;
    this.membershipRepository = membershipRepository;
    this.internalToken = internalToken;
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    String authHeader = request.getHeader("Authorization");

    // JWT는 Authorization 헤더로만 받는다. 과거에는 EventSource API의 헤더 미지원 때문에
    // /api/v1/notifications/stream 엔드포인트에 한해 `?token=` 쿼리 파라미터 fallback을
    // 허용했으나, 토큰이 액세스 로그/프록시 로그/Referer/브라우저 히스토리에 노출되는
    // 위험이 있어 제거했다. 프론트엔드는 fetch + ReadableStream으로 SSE를 수신한다.
    String token = null;
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      token = authHeader.substring(7);
    }

    try {
      if (token != null) {
        authenticateWithJwt(token);
      } else if (StringUtils.hasText(authHeader) && authHeader.startsWith("Internal ")) {
        authenticateWithInternalToken(authHeader.substring(9), request);
      }

      filterChain.doFilter(request, response);
    } finally {
      // 서블릿 컨테이너는 스레드를 풀에서 재사용한다. 정리하지 않으면 다음 요청이 이전 요청의
      // 테넌트를 물려받아 크로스 테넌트 접근이 된다.
      TenantContext.clear();
    }
  }

  private void authenticateWithJwt(String token) {
    // validate/getUserId/getTenantId 를 각각 호출하면 같은 토큰을 3번 파싱(서명 검증 포함)하게
    // 되므로, 요청마다 타는 이 경로에서는 1회 파싱 메서드로 합쳐서 호출한다.
    jwtTokenProvider
        .parseAccessToken(token)
        .ifPresent(
            principal -> {
              // 서명된 tenant 클레임을 신뢰한다(요청마다 멤버십을 재조회하지 않는다). 멤버십/테넌트
              // 정지는 select-tenant 와 refresh 에서 재검증되므로, 최대 액세스 토큰 만료 시간만큼
              // 지연 반영된다. 클레임이 없으면 테넌트 미선택 토큰 — GUC 미설정으로 RLS 가
              // fail-closed 한다.
              TenantContext.set(principal.tenantId());
              setSecurityContext(principal.userId());
            });
  }

  private void authenticateWithInternalToken(String token, HttpServletRequest request) {
    if (!StringUtils.hasText(internalToken)) return;

    // 길이가 다를 때 즉시 반환하면 타이밍 공격으로 길이를 유추할 수 있으므로
    // 길이 정규화 후 상수 시간 비교를 수행한다
    byte[] a = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] b = internalToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (a.length != b.length || !MessageDigest.isEqual(a, b)) return;

    String onBehalfOf = request.getHeader("X-On-Behalf-Of");
    if (!StringUtils.hasText(onBehalfOf)) return;

    try {
      Long userId = Long.parseLong(onBehalfOf);
      // 이 경로에는 JWT 가 없어 tenant 클레임도 없다. 테넌트를 세우지 않으면 바로 아래
      // setSecurityContext 의 권한 조회(permission ⨝ role_permission ⨝ user_role)가 V99 의 RLS
      // 아래에서 0행이 되어 권한 집합이 비고, PermissionInterceptor 가 모든 @RequirePermission
      // 엔드포인트를 403 으로 막는다 — 즉 ai-agent 의 모든 MCP 도구 호출이 죽는다.
      resolveInternalTenant(userId).ifPresent(TenantContext::set);
      setSecurityContext(userId);
    } catch (NumberFormatException ignored) {
      // Invalid userId, skip authentication
    }
  }

  /**
   * 내부 토큰 대행 호출({@code X-On-Behalf-Of})의 실행 테넌트를 대행 대상 사용자의 멤버십에서
   * 해석한다.
   *
   * <p>멤버십이 정확히 하나일 때만 그 테넌트를 쓴다. 0개이거나 2개 이상이면 <b>컨텍스트를 세우지
   * 않는다</b> — 임의로 기본 테넌트를 고르면 그 사용자가 속하지도 않은 워크스페이스의 데이터를
   * 대행자에게 열어 주게 된다. 컨텍스트가 없으면 권한이 비어 403 이 되는데, 그것이 의도된
   * fail-closed 결과다. 특히 {@code audit_log} 정책은 형태 (b)({@code IS NOT DISTINCT FROM})라
   * GUC 가 비면 <b>모든 테넌트의 NULL 테넌트 LOGIN 행</b>(username·IP·User-Agent)이 매칭되는
   * fail-open 이므로, 권한만 복구하고 테넌트를 세우지 않는 절충은 교차 테넌트 유출이 된다.
   *
   * <p>헤더로 테넌트를 지정받는 방식은 의도적으로 범위 밖이다 — 내부 토큰의 테넌트 결정은 스펙상
   * P5 단계로 미뤄져 있다.
   *
   * <p>{@code membership}/{@code tenant} 는 전역(RLS 미적용) 테이블이라 컨텍스트도 트랜잭션도 없이
   * 조회된다({@link MembershipRepository} 클래스 주석 참고). 2단계 로그인 흐름이 쓰는 것과 같은
   * 조회를 재사용한다.
   */
  private Optional<Long> resolveInternalTenant(Long userId) {
    List<MembershipResponse> memberships = membershipRepository.findActiveByUser(userId);
    Optional<Long> tenantId = MembershipResponse.soleActiveTenant(memberships);
    if (tenantId.isEmpty()) {
      log.warn(
          "내부 토큰 대행 호출: 사용자 {} 의 ACTIVE 멤버십이 {}개라 실행 테넌트가 모호하다 —"
              + " 테넌트 컨텍스트 없이 진행한다(권한 0개 → 403). 내부 토큰의 테넌트 결정은 P5 범위다.",
          userId,
          memberships.size());
    }
    return tenantId;
  }

  private void setSecurityContext(Long userId) {
    Set<String> permissions = permissionService.getUserPermissions(userId);
    List<SimpleGrantedAuthority> authorities =
        permissions.stream().map(SimpleGrantedAuthority::new).toList();
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(userId, null, authorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
