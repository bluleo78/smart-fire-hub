package com.smartfirehub.ai.dto;

import java.time.LocalDateTime;

/**
 * AI 세션 응답 DTO.
 * channelSource: 세션 출처 ('WEB' | 'SLACK' | 'KAKAO').
 * slack* 필드: Slack inbound 세션일 때만 값이 존재하며, WEB 세션은 null.
 */
public record AiSessionResponse(
    Long id,
    Long userId,
    String sessionId,
    String contextType,
    Long contextResourceId,
    String title,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String channelSource,
    String slackTeamId,
    String slackChannelId,
    String slackThreadTs) {

    /**
     * 기존 WEB 세션 생성 팩토리 — Slack 필드를 기본값(null)으로 설정한다.
     * 레포지토리의 기존 fetch 매퍼에서 사용.
     */
    public static AiSessionResponse ofWeb(
        Long id,
        Long userId,
        String sessionId,
        String contextType,
        Long contextResourceId,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
        return new AiSessionResponse(
            id, userId, sessionId, contextType, contextResourceId,
            title, createdAt, updatedAt, "WEB", null, null, null);
    }
}
