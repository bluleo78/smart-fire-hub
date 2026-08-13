-- V82: 플랫폼(운영자) 평면 RBAC. 테넌트 평면 RBAC(role/user_role/role_permission)과 별개의 평면이다.
-- 테넌트 토큰으로는 이 평면에 도달할 수 없다(플랫폼 토큰만 접근 — 애플리케이션 레이어에서 강제).
-- 이 테이블들도 전역이므로 tenant_id/RLS 를 갖지 않는다.

CREATE TABLE platform_role (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL UNIQUE,
    description VARCHAR(255),
    is_system   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE platform_role IS '플랫폼 운영자 롤 — 전역. 테넌트 롤(role)과 다른 평면';

CREATE TABLE platform_role_permission (
    platform_role_id BIGINT NOT NULL REFERENCES platform_role(id) ON DELETE CASCADE,
    permission_id    BIGINT NOT NULL REFERENCES permission(id)    ON DELETE CASCADE,
    PRIMARY KEY (platform_role_id, permission_id)
);

CREATE TABLE platform_user_role (
    user_id          BIGINT NOT NULL REFERENCES "user"(id)        ON DELETE CASCADE,
    platform_role_id BIGINT NOT NULL REFERENCES platform_role(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, platform_role_id)
);
CREATE INDEX idx_platform_user_role_user ON platform_user_role(user_id);

-- 권한 코드는 기존 permission 카탈로그를 공유한다(카탈로그는 전역 유지).
INSERT INTO permission (code, category, description)
VALUES
  ('platform:tenant:create',  'platform', '테넌트 생성'),
  ('platform:tenant:read',    'platform', '테넌트 조회'),
  ('platform:tenant:suspend', 'platform', '테넌트 정지/활성화'),
  ('platform:member:read',    'platform', '테넌트 멤버 조회')
ON CONFLICT (code) DO NOTHING;

INSERT INTO platform_role (name, description, is_system)
VALUES ('SUPER_ADMIN', '플랫폼 전체 운영자', TRUE);

INSERT INTO platform_role_permission (platform_role_id, permission_id)
SELECT pr.id, p.id
FROM platform_role pr, permission p
WHERE pr.name = 'SUPER_ADMIN' AND p.category = 'platform';

-- 기존 전역 ADMIN 롤 보유자를 플랫폼 운영자로 승격한다.
-- (전역 ADMIN 의 의미가 P2 에서 "테넌트 ADMIN" 으로 축소되므로, 운영자 권한을 여기서 보존해 둔다.)
INSERT INTO platform_user_role (user_id, platform_role_id)
SELECT DISTINCT ur.user_id, pr.id
FROM user_role ur
JOIN role r ON r.id = ur.role_id
CROSS JOIN platform_role pr
WHERE r.name = 'ADMIN' AND pr.name = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;
