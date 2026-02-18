-- Drop old chat tables
DROP TABLE IF EXISTS chat_message;
DROP TABLE IF EXISTS conversation;

-- Remove old permissions
DELETE FROM role_permission WHERE permission_id IN (SELECT id FROM permission WHERE code IN ('chat:read', 'chat:write'));
DELETE FROM permission WHERE code IN ('chat:read', 'chat:write');

-- Create ai_session table
CREATE TABLE ai_session (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES "user"(id),
    session_id  VARCHAR(255) NOT NULL UNIQUE,
    context_type        VARCHAR(50),
    context_resource_id BIGINT,
    title       VARCHAR(255),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_session_user ON ai_session(user_id);
CREATE INDEX idx_ai_session_context ON ai_session(user_id, context_type, context_resource_id);

-- Insert new AI permissions
INSERT INTO permission (code, description, category) VALUES
    ('ai:read', 'AI 세션 조회', 'ai'),
    ('ai:write', 'AI 채팅 및 세션 관리', 'ai');

-- Grant to all roles
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.category = 'ai';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'USER' AND p.category = 'ai';
