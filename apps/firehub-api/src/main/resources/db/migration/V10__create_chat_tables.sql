CREATE TABLE conversation (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES "user"(id),
    title           VARCHAR(200),
    session_id      VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE chat_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,
    content         TEXT NOT NULL,
    tool_calls      JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conversation_user_id ON conversation(user_id);
CREATE INDEX idx_chat_message_conversation_id ON chat_message(conversation_id);

INSERT INTO permission (code, description, category) VALUES
    ('chat:read', '채팅 대화 조회', 'chat'),
    ('chat:write', '채팅 대화 생성 및 메시지 전송', 'chat');
