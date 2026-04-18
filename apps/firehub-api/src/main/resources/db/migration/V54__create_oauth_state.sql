-- OAuth 시작 시 발급되는 CSRF state. single-use, 10분 TTL.
CREATE TABLE oauth_state (
    id BIGSERIAL PRIMARY KEY,
    state VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    channel_type VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_oauth_state_expires ON oauth_state (expires_at);
