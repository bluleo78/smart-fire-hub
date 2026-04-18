package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.SLACK_WORKSPACE;

import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Slack 워크스페이스 조회 jOOQ 구현. revoked 행은 제외. */
@Repository
class SlackWorkspaceRepositoryImpl implements SlackWorkspaceRepository {

    private final DSLContext dsl;

    SlackWorkspaceRepositoryImpl(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<SlackWorkspace> findByTeamId(String teamId) {
        return dsl.selectFrom(SLACK_WORKSPACE)
                .where(SLACK_WORKSPACE.TEAM_ID.eq(teamId))
                .and(SLACK_WORKSPACE.REVOKED_AT.isNull())
                .fetchOptional()
                .map(r -> new SlackWorkspace(
                        r.getId(),
                        r.getTeamId(),
                        r.getTeamName(),
                        r.getBotUserId(),
                        r.getBotTokenEnc(),
                        r.getSigningSecretEnc(),
                        r.getPreviousSigningSecretEnc(),
                        r.getPreviousSigningSecretExpiresAt() == null ? null
                                : r.getPreviousSigningSecretExpiresAt().toInstant(),
                        r.getInstalledByUserId()
                ));
    }
}
