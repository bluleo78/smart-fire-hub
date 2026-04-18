package com.smartfirehub.notification.repository;

import com.smartfirehub.notification.ChannelType;
import java.time.Instant;

/** 사용자별 외부 채널 연동 도메인 모델. jOOQ generated table record와 분리. */
public record UserChannelBinding(
        Long id,
        long userId,
        ChannelType channelType,
        Long workspaceId,
        String externalUserId,
        String displayAddress,
        String accessTokenEnc,
        String refreshTokenEnc,
        Instant tokenExpiresAt,
        String status,
        Instant lastVerifiedAt,
        Instant createdAt,
        Instant updatedAt
) {}
