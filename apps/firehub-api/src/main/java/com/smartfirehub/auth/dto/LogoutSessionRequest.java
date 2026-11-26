package com.smartfirehub.auth.dto;

/**
 * 이 기기 세션 로그아웃 요청 본문(WD-1176). {@code expectedUserId} 는 화면이 알고 있는 현재 사용자 id 로, 공유 쿠키가 다른 사용자 것으로 바뀐
 * 경우를 걸러 낸다. 본문 자체가 없거나 값이 null 이면 검사를 건너뛴다(뒤 호환).
 */
public record LogoutSessionRequest(Long expectedUserId) {}
