package com.smartfirehub.global.security;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PermissionInterceptor implements HandlerInterceptor {

  /** 운영자가 전역 계정을 비활성화한 사용자의 권한 필요 요청 — 권한 요청 대신 계정 상태를 알린다(WD-315). */
  public static final String ACCOUNT_DEACTIVATED = "ACCOUNT_DEACTIVATED";

  /** 워크스페이스 관리자가 멤버십을 정지한 사용자의 권한 필요 요청(WD-315). */
  public static final String MEMBERSHIP_SUSPENDED = "MEMBERSHIP_SUSPENDED";

  /*
   * 거부 사유 조회용 저장소. ObjectProvider 인 이유: @WebMvcTest 슬라이스(45개 클래스)는 이 인터셉터를 올리지만 저장소 빈은 없다 —
   * 직접 주입하면 그 컨텍스트가 모두 깨진다. 빈이 없으면 사유 조회를 건너뛰고 지금처럼 권한 요청 안내를 낸다.
   */
  private final ObjectProvider<UserRepository> userRepository;
  private final ObjectProvider<MembershipRepository> membershipRepository;

  public PermissionInterceptor(
      ObjectProvider<UserRepository> userRepository,
      ObjectProvider<MembershipRepository> membershipRepository) {
    this.userRepository = userRepository;
    this.membershipRepository = membershipRepository;
  }

  /**
   * 권한 부족 403 의 사용자용 문구. 웹은 서버 message 를 그대로 토스트로 보여 주므로 영어 원문("Missing required permission: …")이
   * 화면에 뜨던 것을 한국어 안내로 바꾼다(WD-127). 필요 권한 코드는 관리자가 어떤 권한을 줘야 하는지 알 수 있게 괄호로 남긴다. 인터셉터 밖에서 같은 403 을
   * 던지는 곳(UserController·DatasetAccessGuard)도 이 문구를 쓴다.
   */
  public static String missingPermissionMessage(String permission) {
    return "이 작업을 할 권한이 없습니다. 관리자에게 권한을 요청하세요. (필요 권한: " + permission + ")";
  }

  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler) {
    // Skip permission check on async dispatch (e.g., SseEmitter completion)
    if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) {
      return true;
    }

    if (!(handler instanceof HandlerMethod handlerMethod)) {
      return true;
    }

    RequirePermission methodAnnotation = handlerMethod.getMethodAnnotation(RequirePermission.class);
    RequirePermission classAnnotation =
        handlerMethod.getBeanType().getAnnotation(RequirePermission.class);

    RequirePermission annotation = methodAnnotation != null ? methodAnnotation : classAnnotation;
    if (annotation == null) {
      return true;
    }

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new AccessDeniedException("Authentication required");
    }

    Set<String> userPermissions =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());

    for (String requiredPermission : annotation.value()) {
      if (!userPermissions.contains(requiredPermission)) {
        rejectInactiveAccess(authentication);
        throw new AccessDeniedException(missingPermissionMessage(requiredPermission));
      }
    }

    return true;
  }

  /**
   * 권한이 비어 거부된 까닭이 계정 비활성·멤버십 정지면 그 사유로 403 을 던진다(WD-315).
   *
   * <p>필터가 싣는 권한은 ACTIVE 멤버십·활성 계정에서만 나오므로({@code
   * PermissionRepository#findPermissionCodesByUserId}), 열린 화면에서 비활성화·정지된 사용자는 소유자여도 권한이 0 이 되어
   * 「관리자에게 권한을 요청하세요」를 받았다 — 요청할 대상이 없거나 역할 문제로 오해한다. 거부 경로에서만 조회하므로 정상 요청에는 비용이 없다. 운영자 평면은 멤버십·계정
   * 활성과 무관한 권한이라 보지 않는다.
   *
   * <p>멤버십 사유는 TenantContext 가 선 요청(웹 세션)에서만 낸다. 내부 위임 요청(AI 등)은 정지된 멤버십이면 필터가 테넌트를 세우지 않으므로 계정
   * 비활성만 가리고, 나머지는 지금처럼 권한 안내로 거부된다(fail-closed 는 같다). 워크스페이스 정지는 권한을 0 으로 만들지 않아 여기로 오지 않는다.
   */
  private void rejectInactiveAccess(Authentication authentication) {
    if (authentication instanceof PlatformAuthentication
        || !(authentication.getPrincipal() instanceof Long userId)) {
      return;
    }
    UserRepository users = userRepository.getIfAvailable();
    MembershipRepository memberships = membershipRepository.getIfAvailable();
    if (users == null || memberships == null) {
      return;
    }
    if (users.findById(userId).map(u -> !u.isActive()).orElse(false)) {
      throw new CodedApiException(
          HttpStatus.FORBIDDEN, ACCOUNT_DEACTIVATED, "계정이 비활성화되어 이 작업을 할 수 없습니다. 운영자에게 문의하세요.");
    }
    Long tenantId = TenantContext.get();
    if (tenantId != null
        && memberships.findInTenant(userId, tenantId).map(m -> !m.isActive()).orElse(false)) {
      throw new CodedApiException(
          HttpStatus.FORBIDDEN,
          MEMBERSHIP_SUSPENDED,
          "이 워크스페이스의 멤버십이 정지되어 이 작업을 할 수 없습니다. 워크스페이스 관리자에게 문의하세요.");
    }
  }
}
