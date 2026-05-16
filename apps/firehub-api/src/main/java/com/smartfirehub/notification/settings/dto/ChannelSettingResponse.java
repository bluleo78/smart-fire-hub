package com.smartfirehub.notification.settings.dto;

/**
 * 사용자 채널 설정 응답 DTO.
 *
 * <p>채널별 연동 상태·선호 설정·OAuth 시작 URL을 프론트엔드로 반환한다.
 *
 * @param channel 채널 종류 식별자 (CHAT | EMAIL | KAKAO | SLACK)
 * @param enabled 알림 수신 여부 (opt-out 여부; CHAT은 항상 true)
 * @param connected 외부 binding이 ACTIVE 상태이거나 이메일이 존재하면 true
 * @param needsReauth binding이 있으나 TOKEN_EXPIRED 상태일 때 true — 재인증 유도용
 * @param displayAddress 연결된 주소 표시 (이메일 주소 · Slack displayName · Kakao 닉네임 · "웹 인박스")
 * @param oauthStartUrl 미연결·재인증 시 프론트엔드가 새 창으로 열 OAuth 시작 URL. EMAIL/CHAT은 null
 * @param workspaceId SLACK 전용 — 워크스페이스 OAuth 설치가 완료되었으나 사용자 binding이 없을 때, 프론트엔드가 사용자 ID
 *     매핑 입력 UI를 노출하기 위한 식별자. SLACK 채널이고 활성 워크스페이스가 존재하면 값이 채워지고, 그 외(설치 전·다른 채널)는 null. {@code POST
 *     /api/v1/oauth/slack/link-user} 의 {@code workspaceId} 파라미터로 그대로 전달된다.
 */
public record ChannelSettingResponse(
    String channel,
    boolean enabled,
    boolean connected,
    boolean needsReauth,
    String displayAddress,
    String oauthStartUrl,
    Long workspaceId) {}
