package com.smartfirehub.notification.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

  private final SseEmitterRegistry registry;

  // NOTE: EventSource API does not support custom headers, so the JWT token is passed
  // as a query parameter (?token=...). This is a known security tradeoff — the token may
  // appear in server access logs and proxy logs. Mitigation: JwtAuthenticationFilter only
  // accepts query param tokens for this specific endpoint. TODO: Replace with short-lived
  // SSE ticket (POST /api/v1/notifications/ticket → 30s TTL single-use token).
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @RequirePermission("dataset:read")
  public SseEmitter subscribe() {
    Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return registry.register(userId);
  }
}
