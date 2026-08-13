-- V81: 멀티 테넌시 기반 — tenant/membership 전역 테이블과 기존 데이터의 기본 테넌트 귀속.
-- 이 두 테이블은 테넌트 경계 "위"의 컨트롤 플레인이므로 tenant_id 컬럼도 RLS 정책도 갖지 않는다.
-- (RLS 를 걸면 테넌트 선택 전 로그인 단계에서 멤버십을 조회할 수 없어 순환이 된다.)

-- 1) 테넌트 = 한 고객사 워크스페이스. 운영자 프로비저닝 전용(셀프서브 가입 없음).
CREATE TABLE tenant (
    id         BIGSERIAL    PRIMARY KEY,
    slug       VARCHAR(64)  UNIQUE,
    name       VARCHAR(255) NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT tenant_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);
COMMENT ON TABLE tenant IS '테넌트(고객사 워크스페이스) — 전역, RLS 미적용';
COMMENT ON COLUMN tenant.slug IS 'URL/식별용 짧은 키. 서브도메인 UX 는 후속 과제이므로 지금은 컬럼만 확보';

-- 2) 사용자↔테넌트 멤버십. 전역 계정 하나가 여러 테넌트에 동시 소속될 수 있다(Slack 방식).
--    role 은 표시용 라벨이며, 실제 인가는 테넌트 평면 RBAC(P2 의 role/user_role)이 담당한다.
CREATE TABLE membership (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    tenant_id  BIGINT      NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    role       VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT membership_role_check   CHECK (role   IN ('OWNER', 'ADMIN', 'MEMBER')),
    CONSTRAINT membership_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT membership_unique UNIQUE (user_id, tenant_id)
);
CREATE INDEX idx_membership_user   ON membership(user_id);
CREATE INDEX idx_membership_tenant ON membership(tenant_id);
COMMENT ON TABLE membership IS '사용자-테넌트 소속(다중 소속 허용) — 전역, RLS 미적용';
COMMENT ON COLUMN membership.role IS '표시용 라벨(OWNER/ADMIN/MEMBER). 인가 판단에 쓰지 않는다';

-- 3) 기본 테넌트 시드. 기존 단일테넌트 데이터 전부가 여기에 귀속된다.
--    id 를 명시 삽입했으므로 시퀀스를 1로 맞춰 다음 테넌트가 2번을 받게 한다.
INSERT INTO tenant (id, slug, name, status)
VALUES (1, 'default', 'Default Workspace', 'ACTIVE');
SELECT setval('tenant_id_seq', 1, true);

-- 4) 기존 모든 사용자를 기본 테넌트의 ACTIVE 멤버로 백필.
--    첫 사용자를 OWNER 로 승격해 운영 주체를 명시한다(없으면 소유자 없는 테넌트가 된다).
INSERT INTO membership (user_id, tenant_id, role, status)
SELECT id, 1, 'MEMBER', 'ACTIVE' FROM "user";

UPDATE membership SET role = 'OWNER'
WHERE tenant_id = 1
  AND user_id = (SELECT MIN(id) FROM "user");
