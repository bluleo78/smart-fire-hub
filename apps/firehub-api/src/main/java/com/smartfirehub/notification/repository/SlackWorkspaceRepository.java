package com.smartfirehub.notification.repository;

import java.util.Optional;

/** Slack 워크스페이스(봇 토큰) 조회. Stage 2/3에서 OAuth 설치 로직 확장. */
public interface SlackWorkspaceRepository {
    Optional<SlackWorkspace> findByTeamId(String teamId);

    record SlackWorkspace(
            long id,
            String teamId,
            String teamName,
            String botUserId,
            String botTokenEnc,
            String signingSecretEnc,
            String previousSigningSecretEnc,
            java.time.Instant previousSigningSecretExpiresAt,
            Long installedByUserId
    ) {}
}
