package com.smartfirehub.global.security;

import com.smartfirehub.permission.service.PermissionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
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
  private final String internalToken;

  public JwtAuthenticationFilter(
      JwtTokenProvider jwtTokenProvider,
      @Lazy PermissionService permissionService,
      @Value("${agent.internal-token:}") String internalToken) {
    this.jwtTokenProvider = jwtTokenProvider;
    this.permissionService = permissionService;
    this.internalToken = internalToken;
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    String authHeader = request.getHeader("Authorization");

    String token = null;
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      token = authHeader.substring(7);
    } else if (request.getRequestURI().equals("/api/v1/notifications/stream")) {
      token = request.getParameter("token");
    }

    if (token != null) {
      authenticateWithJwt(token);
    } else if (StringUtils.hasText(authHeader) && authHeader.startsWith("Internal ")) {
      authenticateWithInternalToken(authHeader.substring(9), request);
    }

    filterChain.doFilter(request, response);
  }

  private void authenticateWithJwt(String token) {
    if (jwtTokenProvider.validateAccessToken(token)) {
      Long userId = jwtTokenProvider.getUserIdFromToken(token);
      setSecurityContext(userId);
    }
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
      setSecurityContext(userId);
    } catch (NumberFormatException ignored) {
      // Invalid userId, skip authentication
    }
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
