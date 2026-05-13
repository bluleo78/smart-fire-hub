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

  // SSE 스트림 구독. 프론트엔드가 fetch + ReadableStream으로 호출하며 Authorization
  // 헤더로 JWT를 전달한다. 표준 EventSource는 헤더 미지원이라 과거 `?token=` 쿼리
  // 파라미터 fallback을 두었으나 토큰 로그 노출 위험으로 제거했다(#172).
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @RequirePermission("dataset:read")
  public SseEmitter subscribe() {
    Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return registry.register(userId);
  }
}
