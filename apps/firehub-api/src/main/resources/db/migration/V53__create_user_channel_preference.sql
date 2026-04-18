-- 사용자 글로벌 채널 on/off. CHAT은 DB CHECK로 disable 불가능 강제 (안전망).
CREATE TABLE user_channel_preference (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    channel_type VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_preference UNIQUE (user_id, channel_type),
    CONSTRAINT chat_always_enabled CHECK (channel_type <> 'CHAT' OR enabled = TRUE)
);
