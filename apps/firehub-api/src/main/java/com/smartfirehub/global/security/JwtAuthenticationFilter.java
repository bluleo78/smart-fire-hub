package com.smartfirehub.global.security;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.platform.repository.PlatformRoleRepository;
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
  private final PlatformRoleRepository platformRoleRepository;
  private final String internalToken;

  public JwtAuthenticationFilter(
      JwtTokenProvider jwtTokenProvider,
      @Lazy PermissionService permissionService,
      @Lazy MembershipRepository membershipRepository,
      @Lazy PlatformRoleRepository platformRoleRepository,
      @Value("${agent.internal-token:}") String internalToken) {
    this.jwtTokenProvider = jwtTokenProvider;
    this.permissionService = permissionService;
    this.membershipRepository = membershipRepository;
    this.platformRoleRepository = platformRoleRepository;
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
              if (principal.platform()) {
                // 운영자 평면: TenantContext 를 세우지 않는다. 세우면 운영자 요청이 그 테넌트의
                // RLS 안에서 돌아 도메인 데이터가 열린다(설계서 §4 는 크로스테넌트 도메인 조회를
                // 제공하지 않기로 결정했다). 컨텍스트가 비어 있음 = GUC 미설정 = fail-closed 다.
                setPlatformSecurityContext(principal.userId());
                return;
              }
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

    String onBehalfOf = request.getHeader(InternalCallHeaders.ON_BEHALF_OF);
    if (!StringUtils.hasText(onBehalfOf)) return;

    try {
      Long userId = Long.parseLong(onBehalfOf);
      // 이 경로에는 JWT 가 없어 tenant 클레임도 없다. 테넌트를 세우지 않으면 바로 아래
      // setSecurityContext 의 권한 조회(permission ⨝ role_permission ⨝ user_role)가 V99 의 RLS
      // 아래에서 0행이 되어 권한 집합이 비고, PermissionInterceptor 가 모든 @RequirePermission
      // 엔드포인트를 403 으로 막는다 — 즉 ai-agent 의 모든 MCP 도구 호출이 죽는다.
      resolveInternalTenant(userId, request.getHeader(InternalCallHeaders.ON_BEHALF_OF_TENANT))
          .ifPresent(TenantContext::set);
      setSecurityContext(userId);
    } catch (NumberFormatException ignored) {
      // Invalid userId, skip authentication
    }
  }

  /**
   * 내부 토큰 대행 호출({@code X-On-Behalf-Of})의 실행 테넌트를 해석한다.
   *
   * <p><b>원칙: 추측하지 않고 전달받은 값을 검증한다.</b> 호출측이
   * {@link InternalCallHeaders#ON_BEHALF_OF_TENANT} 로 원요청 테넌트를 실어 보내면(웹 세션 JWT 의
   * tenant 클레임 → {@code AiAgentProxyService} → ai-agent → {@code FireHubApiClient}) 그 값을 대행
   * 대상 사용자의 ACTIVE 멤버십과 대조한 뒤에만 쓴다.
   *
   * <p>이 대조가 실제로 막는 것은 <b>호출측의 잘못된 테넌트 전파</b>다(다른 워크스페이스의 요청
   * 문맥이 섞여 들어오는 경우). 내부 토큰을 쥔 쪽은 {@code X-On-Behalf-Of} 자체도 자유롭게 고를 수
   * 있어 이 대조가 악의적 호출자에 대한 상한이 되지는 않는다 — 내부 경로의 신뢰 경계는 토큰 자체다.
   * 그래도 대조하는 이유는, 테넌트를 무검증 채택하면 호출측의 전파 오류가 RLS 아래에서 조용히 남의
   * 워크스페이스 읽기·쓰기로 바뀌기 때문이다.
   *
   * <p>판정은 {@link MembershipRepository#hasActiveMembership} 를 재사용한다 — {@code AuthService}
   * 의 tenant 클레임 검증이 쓰는 것과 같은 게이트여서 규칙이 한 벌로 유지되고, 이 핫패스에서 쓰지도
   * 않는 멤버십 DTO 를 사용자 멤버십 수만큼 만들지 않는다.
   *
   * <p>헤더가 <b>없을 때</b>만 멤버십에서 역추론하는 기존 폴백을 쓴다(헤더를 아직 보내지 않는
   * eval·migration 스크립트 호환). 멤버십이 정확히 하나일 때만 그 테넌트를 쓰고, 0개이거나 2개
   * 이상이면 <b>컨텍스트를 세우지 않는다</b> — 임의로 기본 테넌트를 고르면 그 사용자가 속하지도 않은
   * 워크스페이스의 데이터를 대행자에게 열어 주게 된다. 컨텍스트가 없으면 권한이 비어 403 이 되는데,
   * 그것이 의도된 fail-closed 결과다. 특히 {@code audit_log} 정책은 형태 (b)({@code IS NOT DISTINCT
   * FROM})라 GUC 가 비면 <b>모든 테넌트의 NULL 테넌트 LOGIN 행</b>(username·IP·User-Agent)이
   * 매칭되는 fail-open 이므로, 권한만 복구하고 테넌트를 세우지 않는 절충은 교차 테넌트 유출이 된다.
   *
   * <p>헤더 값이 숫자가 아니면 폴백하지 않고 그대로 fail-closed 한다 — 깨진 헤더를 조용히 무시하면
   * 호출측 버그가 단일 멤버십 환경에서만 숨고 멀티 테넌트에서 터진다.
   *
   * <p>{@code membership}/{@code tenant} 는 전역(RLS 미적용) 테이블이라 컨텍스트도 트랜잭션도 없이
   * 조회된다({@link MembershipRepository} 클래스 주석 참고).
   *
   * @param requestedTenantRaw 테넌트 헤더 원문. 없으면 {@code null}
   */
  private Optional<Long> resolveInternalTenant(Long userId, String requestedTenantRaw) {
    if (StringUtils.hasText(requestedTenantRaw)) {
      return validateRequestedTenant(userId, requestedTenantRaw.trim());
    }

    List<MembershipResponse> memberships = membershipRepository.findActiveByUser(userId);
    Optional<Long> tenantId = MembershipResponse.soleActiveTenant(memberships);
    if (tenantId.isEmpty()) {
      log.warn(
          "내부 토큰 대행 호출: 사용자 {} 의 ACTIVE 멤버십이 {}개라 실행 테넌트가 모호하다 —"
              + " 테넌트 컨텍스트 없이 진행한다(권한 0개 → 403)."
              + " 호출측이 {} 헤더로 원요청 테넌트를 실어 보내야 한다.",
          userId,
          memberships.size(),
          InternalCallHeaders.ON_BEHALF_OF_TENANT);
    }
    return tenantId;
  }

  /** 전달받은 테넌트가 대행 대상 사용자의 ACTIVE 멤버십 안에 있을 때만 통과시킨다. */
  private Optional<Long> validateRequestedTenant(Long userId, String requestedTenantRaw) {
    Long requested;
    try {
      requested = Long.valueOf(requestedTenantRaw);
    } catch (NumberFormatException e) {
      log.warn(
          "내부 토큰 대행 호출: {} 헤더 값 '{}' 이 숫자가 아니다 — 테넌트 컨텍스트 없이 진행한다"
              + "(권한 0개 → 403).",
          InternalCallHeaders.ON_BEHALF_OF_TENANT,
          requestedTenantRaw);
      return Optional.empty();
    }

    if (!membershipRepository.hasActiveMembership(userId, requested)) {
      log.warn(
          "내부 토큰 대행 호출: 사용자 {} 는 요청된 테넌트 {} 의 ACTIVE 멤버가 아니다 —"
              + " 테넌트 컨텍스트 없이 진행한다(권한 0개 → 403).",
          userId,
          requested);
      return Optional.empty();
    }
    return Optional.of(requested);
  }

  /**
   * 운영자 평면의 SecurityContext. 권한은 플랫폼 평면에서만 로딩한다.
   *
   * <p>테넌트 권한을 함께 싣지 않는 이유: 두 평면의 authority 가 한 집합에 섞이면 평면 가드 밖에서는
   * 구분할 수 없게 되고, 운영자 토큰으로 테넌트 API 를 호출할 수 있게 된다. 반대로 테넌트 권한을
   * 로딩하려면 GUC 가 필요한데 운영자 요청에는 테넌트 컨텍스트가 없다 — 어차피 0행이 된다.
   */
  private void setPlatformSecurityContext(Long userId) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new PlatformAuthentication(
                userId, authorities(platformRoleRepository.findPlatformPermissionCodes(userId))));
  }

  private void setSecurityContext(Long userId) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                userId, null, authorities(permissionService.getUserPermissions(userId))));
  }

  /** 권한 코드 집합을 Spring Security authority 로 옮긴다. 두 평면이 같은 변환을 쓴다. */
  private static List<SimpleGrantedAuthority> authorities(Set<String> permissions) {
    return permissions.stream().map(SimpleGrantedAuthority::new).toList();
  }
}
