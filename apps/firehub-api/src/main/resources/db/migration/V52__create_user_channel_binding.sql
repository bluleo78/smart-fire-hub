-- 사용자별 외부 채널 연동 (OAuth 토큰·외부 id·상태). CHAT은 불필요.
CREATE TABLE user_channel_binding (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    channel_type VARCHAR(32) NOT NULL,
    workspace_id BIGINT REFERENCES slack_workspace(id),
    external_user_id VARCHAR(255),
    display_address VARCHAR(255),
    access_token_enc TEXT,
    refresh_token_enc TEXT,
    token_expires_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_user_channel UNIQUE (user_id, channel_type, workspace_id),
    CONSTRAINT chk_binding_status CHECK (status IN ('ACTIVE','TOKEN_EXPIRED','REVOKED')),
    CONSTRAINT chk_binding_channel CHECK (channel_type IN ('EMAIL','KAKAO','SLACK'))
);

CREATE INDEX idx_binding_external_user
    ON user_channel_binding (channel_type, workspace_id, external_user_id);
