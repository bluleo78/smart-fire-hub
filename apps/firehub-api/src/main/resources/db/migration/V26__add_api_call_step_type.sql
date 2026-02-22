-- API_CALL 스텝 타입 지원을 위한 스키마 변경

-- 1. pipeline_step 제약 수정
ALTER TABLE pipeline_step ALTER COLUMN script_content DROP NOT NULL;
ALTER TABLE pipeline_step DROP CONSTRAINT pipeline_step_script_type_check;
ALTER TABLE pipeline_step ADD CONSTRAINT pipeline_step_script_type_check
    CHECK (script_type IN ('SQL', 'PYTHON', 'API_CALL'));
ALTER TABLE pipeline_step ADD COLUMN api_config JSONB;

-- 2. api_connection 테이블 생성
CREATE TABLE api_connection (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(100) NOT NULL,
    description             TEXT,
    auth_type               VARCHAR(20) NOT NULL CHECK (auth_type IN ('API_KEY', 'BEARER', 'OAUTH2')),
    auth_config             TEXT NOT NULL,
    oauth_state             TEXT,
    oauth_token_expires_at  TIMESTAMP,
    created_by              BIGINT NOT NULL REFERENCES "user"(id),
    created_at              TIMESTAMP DEFAULT NOW(),
    updated_at              TIMESTAMP DEFAULT NOW()
);

-- 3. api_connection_id FK 컬럼 추가
ALTER TABLE pipeline_step ADD COLUMN api_connection_id BIGINT REFERENCES api_connection(id);

-- 4. 권한 seed
INSERT INTO permission (code, description, category) VALUES
    ('apiconnection:read', 'API 연결 설정을 조회할 수 있습니다', 'apiconnection'),
    ('apiconnection:write', 'API 연결 설정을 생성하고 수정할 수 있습니다', 'apiconnection'),
    ('apiconnection:delete', 'API 연결 설정을 삭제할 수 있습니다', 'apiconnection');

-- ADMIN 역할에 모든 API 연결 권한 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.category = 'apiconnection';

-- USER 역할에 읽기 권한만 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'USER' AND p.code = 'apiconnection:read';
