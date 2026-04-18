-- Slack 양방향 채널의 워크스페이스별 봇 토큰·서명 비밀. 사용자별 매핑은 user_channel_binding에.
CREATE TABLE slack_workspace (
    id BIGSERIAL PRIMARY KEY,
    team_id VARCHAR(64) NOT NULL UNIQUE,
    team_name VARCHAR(255),
    bot_user_id VARCHAR(64) NOT NULL,
    bot_token_enc TEXT NOT NULL,
    signing_secret_enc TEXT NOT NULL,
    previous_signing_secret_enc TEXT,
    previous_signing_secret_expires_at TIMESTAMPTZ,
    installed_by_user_id BIGINT REFERENCES "user"(id),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ
);
