package com.smartfirehub.notification.repository;

import static com.smartfirehub.jooq.Tables.SLACK_WORKSPACE;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Slack 워크스페이스 조회·upsert jOOQ 구현. revoked 행은 findByTeamId에서 제외. */
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

    /**
     * 워크스페이스 ID로 단건 조회 (revoked 포함).
     *
     * <p>linkUser에서 봇 토큰을 꺼낼 때 사용한다.
     */
    @Override
    public Optional<SlackWorkspace> findById(long id) {
        return dsl.selectFrom(SLACK_WORKSPACE)
                .where(SLACK_WORKSPACE.ID.eq(id))
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

    /**
     * team_id UNIQUE 제약 충돌 시 UPDATE, 없으면 INSERT.
     *
     * <p>signing_secret_enc는 기존 값을 유지한다 (재설치해도 signing secret는 변경되지 않는 경우가 많으므로).
     * V51 마이그레이션의 NOT NULL 제약이 있으므로 기존 값이 없는 최초 설치 시에는 빈 문자열로 임시 저장.
     * bot_token_enc만 재설치 시 반드시 갱신.
     *
     * @return 생성 또는 갱신된 레코드의 PK id
     */
    @Override
    public long upsertFromOAuth(
            String teamId,
            String teamName,
            String botUserId,
            String botTokenEnc,
            Long installedByUserId) {
        return dsl.insertInto(SLACK_WORKSPACE)
                .set(SLACK_WORKSPACE.TEAM_ID, teamId)
                .set(SLACK_WORKSPACE.TEAM_NAME, teamName)
                .set(SLACK_WORKSPACE.BOT_USER_ID, botUserId)
                .set(SLACK_WORKSPACE.BOT_TOKEN_ENC, botTokenEnc)
                // 최초 설치 시 signing_secret은 빈 문자열 (NOT NULL 제약 만족)
                .set(SLACK_WORKSPACE.SIGNING_SECRET_ENC, "")
                .set(SLACK_WORKSPACE.INSTALLED_BY_USER_ID, installedByUserId)
                .onConflict(SLACK_WORKSPACE.TEAM_ID)
                .doUpdate()
                .set(SLACK_WORKSPACE.TEAM_NAME, teamName)
                .set(SLACK_WORKSPACE.BOT_USER_ID, botUserId)
                .set(SLACK_WORKSPACE.BOT_TOKEN_ENC, botTokenEnc)
                .set(SLACK_WORKSPACE.INSTALLED_BY_USER_ID, installedByUserId)
                // 재설치 시 revoked_at을 NULL로 초기화하여 활성 상태로 복원
                .set(SLACK_WORKSPACE.REVOKED_AT, (OffsetDateTime) null)
                .returning(SLACK_WORKSPACE.ID)
                .fetchOne()
                .getValue(SLACK_WORKSPACE.ID);
    }

    /**
     * 워크스페이스 취소 — revoked_at=NOW() 설정.
     *
     * <p>이후 findByTeamId는 해당 워크스페이스를 반환하지 않는다.
     */
    @Override
    public void revoke(String teamId) {
        dsl.update(SLACK_WORKSPACE)
                .set(SLACK_WORKSPACE.REVOKED_AT, OffsetDateTime.now())
                .where(SLACK_WORKSPACE.TEAM_ID.eq(teamId))
                .execute();
    }
}
