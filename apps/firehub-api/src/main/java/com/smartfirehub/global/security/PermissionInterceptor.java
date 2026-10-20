package com.smartfirehub.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.stream.Collectors;
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
        throw new AccessDeniedException(missingPermissionMessage(requiredPermission));
      }
    }

    return true;
  }
}
