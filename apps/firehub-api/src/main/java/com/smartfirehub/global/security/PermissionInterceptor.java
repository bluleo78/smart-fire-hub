package com.smartfirehub.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequirePermission methodAnnotation = handlerMethod.getMethodAnnotation(RequirePermission.class);
        RequirePermission classAnnotation = handlerMethod.getBeanType().getAnnotation(RequirePermission.class);

        RequirePermission annotation = methodAnnotation != null ? methodAnnotation : classAnnotation;
        if (annotation == null) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required");
        }

        Set<String> userPermissions = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        for (String requiredPermission : annotation.value()) {
            if (!userPermissions.contains(requiredPermission)) {
                throw new AccessDeniedException("Missing required permission: " + requiredPermission);
            }
        }

        return true;
    }
}
