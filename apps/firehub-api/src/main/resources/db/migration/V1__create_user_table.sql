-- User table
CREATE TABLE "user" (
    id         BIGSERIAL    PRIMARY KEY,
    username   VARCHAR(50)  NOT NULL UNIQUE,
    email      VARCHAR(255),
    password   VARCHAR(255) NOT NULL,
    name       VARCHAR(50)  NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    DEFAULT NOW()
);

CREATE INDEX idx_user_username ON "user"(username);
CREATE UNIQUE INDEX idx_user_email_unique ON "user"(email) WHERE email IS NOT NULL;

-- Refresh token table
CREATE TABLE refresh_token (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMP    NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_token_user_id ON refresh_token(user_id);

-- Permission table
CREATE TABLE permission (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(50)  UNIQUE NOT NULL,
    description VARCHAR(255),
    category    VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMP    DEFAULT NOW()
);

CREATE INDEX idx_permission_category ON permission(category);

-- Role table
CREATE TABLE role (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(50)  UNIQUE NOT NULL,
    description VARCHAR(255),
    is_system   BOOLEAN      DEFAULT FALSE,
    created_at  TIMESTAMP    DEFAULT NOW(),
    updated_at  TIMESTAMP    DEFAULT NOW()
);

-- Role-Permission junction
CREATE TABLE role_permission (
    role_id       BIGINT NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX idx_role_permission_permission_id ON role_permission(permission_id);

-- User-Role junction
CREATE TABLE user_role (
    user_id BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_role_role_id ON user_role(role_id);

-- Seed permissions
INSERT INTO permission (code, description, category) VALUES
    ('user:read', '사용자 목록 조회', 'user'),
    ('user:read:self', '본인 프로필 조회', 'user'),
    ('user:write:self', '본인 프로필 수정', 'user'),
    ('user:write', '사용자 정보 수정', 'user'),
    ('user:delete', '사용자 비활성화', 'user'),
    ('role:read', '역할 목록 조회', 'role'),
    ('role:write', '역할 생성/수정', 'role'),
    ('role:delete', '역할 삭제', 'role'),
    ('role:assign', '사용자에 역할 할당', 'role'),
    ('permission:read', '권한 목록 조회', 'permission');

-- Seed default roles
INSERT INTO role (name, description, is_system) VALUES
    ('ADMIN', '시스템 관리자', TRUE),
    ('USER', '일반 사용자', TRUE);

-- Assign all permissions to ADMIN role
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'ADMIN';

-- Assign user:read:self and user:write:self to USER role
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'USER' AND p.code IN ('user:read:self', 'user:write:self');
