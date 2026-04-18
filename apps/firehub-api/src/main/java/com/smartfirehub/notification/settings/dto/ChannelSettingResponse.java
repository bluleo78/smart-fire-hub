package com.smartfirehub.notification.settings.dto;

/**
 * 사용자 채널 설정 응답 DTO.
 *
 * <p>채널별 연동 상태·선호 설정·OAuth 시작 URL을 프론트엔드로 반환한다.
 *
 * @param channel        채널 종류 식별자 (CHAT | EMAIL | KAKAO | SLACK)
 * @param enabled        알림 수신 여부 (opt-out 여부; CHAT은 항상 true)
 * @param connected      외부 binding이 ACTIVE 상태이거나 이메일이 존재하면 true
 * @param needsReauth    binding이 있으나 TOKEN_EXPIRED 상태일 때 true — 재인증 유도용
 * @param displayAddress 연결된 주소 표시 (이메일 주소 · Slack displayName · Kakao 닉네임 · "웹 인박스")
 * @param oauthStartUrl  미연결·재인증 시 프론트엔드가 새 창으로 열 OAuth 시작 URL. EMAIL/CHAT은 null
 */
public record ChannelSettingResponse(
        String channel,
        boolean enabled,
        boolean connected,
        boolean needsReauth,
        String displayAddress,
        String oauthStartUrl
) {}
