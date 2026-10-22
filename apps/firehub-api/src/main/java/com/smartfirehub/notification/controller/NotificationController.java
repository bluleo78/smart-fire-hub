package com.smartfirehub.notification.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.tenant.repository.MembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
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
  // 구독 시점 워크스페이스 상태 확인(WD-190) — 테넌트 선택·갱신 게이트와 같은 판정을 재사용한다
  private final MembershipRepository membershipRepository;

  // SSE 스트림 구독. 프론트엔드가 fetch + ReadableStream으로 호출하며 Authorization
  // 헤더로 JWT를 전달한다. 표준 EventSource는 헤더 미지원이라 과거 `?token=` 쿼리
  // 파라미터 fallback을 두었으나 토큰 로그 노출 위험으로 제거했다(#172).
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @RequirePermission("dataset:read")
  public SseEmitter subscribe() {
    Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    long tenantId = TenantContext.require("알림 스트림 구독");
    // 정지된 워크스페이스는 구독을 거절한다(WD-190). 권한 판정(@RequirePermission)은 멤버십 상태만 보고 워크스페이스 상태는 보지 않아,
    // 정지 때 연결을 닫아도 기존 토큰(최대 30분)으로 곧바로 다시 붙었다. 다른 API 는 공지대로 토큰 만료까지 열려 있고, 알림 구독만 여기서 막는다.
    //
    // 스프링 시큐리티 AccessDeniedException 을 던지는 이유: 웹은 Accept: text/event-stream 으로 구독해 전역 예외 처리기의 JSON
    // 오류 본문을
    // 쓸 수 없다(처리기가 HttpMediaTypeNotAcceptable 로 실패해 예외가 그대로 올라가고, 다른 예외면 500 이 된다). 이 예외는 그 뒤
    // 시큐리티 필터(ExceptionTranslationFilter)가 받아 403 으로 바꾼다 —
    // 권한 없음(@RequirePermission) 403 과 같은 경로라 웹이 같은 규칙(재시도 중단)으로 다룬다.
    if (!membershipRepository.hasActiveMembership(userId, tenantId)) {
      throw new AccessDeniedException("정지되었거나 접근할 수 없는 워크스페이스라 알림을 받을 수 없습니다.");
    }
    // 연결마다 구독 테넌트를 남긴다 — 테넌트 범위 알림(데이터셋 변경 등)이 다른 테넌트 연결로 새지 않게.
    return registry.register(userId, tenantId);
  }
}
