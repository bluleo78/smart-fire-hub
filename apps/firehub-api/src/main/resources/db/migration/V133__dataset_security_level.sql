-- V133: 데이터셋 보안 등급(S1) — 테넌트별 순서 있는 등급, 역할 열람 자격, 데이터셋 허용 목록, 권한 4종.
--
-- 왜: 같은 테넌트 안에서도 일부 데이터셋(인사 등)은 특정 인원만 봐야 한다(스펙 §1.1-B).
-- 배포 전후 가시성 동일 원칙(스펙 §7.7): 기존 데이터셋·역할 = 기본(내부), 시스템 ADMIN = 최상위(기밀).
-- 내부 이하는 허용 목록이 없으므로, 백필 직후 모든 사용자가 배포 전과 같은 데이터셋을 본다.

-- ── (1) security_level ─────────────────────────────────────────────────────────
CREATE TABLE security_level (
  id                 BIGSERIAL PRIMARY KEY,
  tenant_id          BIGINT      NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint,
  rank               INT         NOT NULL,
  name               VARCHAR(50) NOT NULL,
  is_default         BOOLEAN     NOT NULL DEFAULT FALSE,
  allowlist_required BOOLEAN     NOT NULL DEFAULT FALSE,
  -- allowlist_required 일 때만 의미가 있다(스펙 §2.2). 꺼진 등급에서는 무시된다.
  admin_bypass       BOOLEAN     NOT NULL DEFAULT FALSE,
  export_policy      VARCHAR(16) NOT NULL DEFAULT 'ALLOW'
    CHECK (export_policy IN ('ALLOW', 'PERMISSION', 'DENY')),
  ai_policy          VARCHAR(16) NOT NULL DEFAULT 'ALL'
    CHECK (ai_policy IN ('ALL', 'SELF_HOSTED_ONLY', 'DENY')),
  share_policy       VARCHAR(16) NOT NULL DEFAULT 'ALLOW'
    CHECK (share_policy IN ('ALLOW', 'DENY')),
  audit_access       BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at         TIMESTAMP   NOT NULL DEFAULT NOW(),
  -- created_by/updated_by 는 FK 를 걸지 않는다: 사용자 삭제(테스트 정리 포함)가 등급 이력 때문에 막히면 안 된다.
  created_by         BIGINT,
  updated_at         TIMESTAMP   NOT NULL DEFAULT NOW(),
  updated_by         BIGINT,
  CONSTRAINT fk_security_level_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
  -- 순서 변경은 여러 행의 rank 를 한 트랜잭션에서 맞바꾼다 → DEFERRABLE 이어야 중간 상태 충돌이 없다.
  CONSTRAINT uq_security_level_rank UNIQUE (tenant_id, rank) DEFERRABLE INITIALLY IMMEDIATE,
  CONSTRAINT uq_security_level_name UNIQUE (tenant_id, name),
  -- (tenant_id, id) 복합 FK 의 대상. 다른 테넌트의 등급을 가리키는 행을 DB 가 거부하게 한다.
  CONSTRAINT uq_security_level_tenant_id UNIQUE (tenant_id, id)
);
-- 테넌트당 기본 등급 정확히 1개(최대 1개는 부분 UNIQUE, 최소 1개는 서비스 규칙 — 기본 등급 삭제 불가).
CREATE UNIQUE INDEX uq_security_level_default ON security_level (tenant_id) WHERE is_default;
CREATE INDEX idx_security_level_tenant ON security_level (tenant_id);

ALTER TABLE security_level ENABLE ROW LEVEL SECURITY;
CREATE POLICY security_level_tenant_isolation ON security_level
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

-- ── (2) 기존 전 테넌트에 기본 4등급 시드 ──────────────────────────────────────────
-- Flyway 는 테이블 소유자(app)로 접속하고 FORCE RLS 가 없으므로 전 테넌트 행에 닿는다(V113 선례).
INSERT INTO security_level
  (tenant_id, rank, name, is_default, allowlist_required, admin_bypass,
   export_policy, ai_policy, share_policy, audit_access)
SELECT t.id, s.rank, s.name, s.is_default, s.allowlist_required, FALSE,
       s.export_policy, s.ai_policy, s.share_policy, s.audit_access
FROM tenant t
CROSS JOIN (VALUES
  (1, '공개', FALSE, FALSE, 'ALLOW',      'ALL',              'ALLOW', FALSE),
  (2, '내부', TRUE,  FALSE, 'ALLOW',      'ALL',              'ALLOW', FALSE),
  (3, '민감', FALSE, FALSE, 'PERMISSION', 'SELF_HOSTED_ONLY', 'ALLOW', TRUE),
  (4, '기밀', FALSE, TRUE,  'DENY',       'SELF_HOSTED_ONLY', 'DENY',  TRUE)
) AS s(rank, name, is_default, allowlist_required, export_policy, ai_policy, share_policy, audit_access);

-- ── (3) 기본 등급 DEFAULT 함수 ─────────────────────────────────────────────────
-- tenant_id DEFAULT 와 같은 원리로, 현재 GUC 테넌트의 기본 등급 id 를 돌려준다. 이것이 있어야
-- 기존 INSERT 경로(DatasetRepository.save, RoleRepository.save, 테스트 픽스처의 직접 INSERT)가
-- 코드 변경 없이 NOT NULL 을 통과한다. SECURITY INVOKER(기본) — RLS 아래에서 자기 테넌트만 본다.
CREATE OR REPLACE FUNCTION tenant_default_security_level_id()
RETURNS bigint
LANGUAGE sql
STABLE
AS $$
  SELECT id FROM security_level
  WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint
    AND is_default
$$;

-- ── (4) dataset.security_level_id / auto_raised_at ─────────────────────────────
ALTER TABLE dataset ADD COLUMN security_level_id BIGINT;
-- 자동 상향 배너용(스펙 §3). S2 에서는 파이프라인 TEMP 출력 상향만 기록한다.
ALTER TABLE dataset ADD COLUMN security_level_auto_raised_at TIMESTAMP;
UPDATE dataset d SET security_level_id = sl.id
FROM security_level sl
WHERE sl.tenant_id = d.tenant_id AND sl.is_default AND d.security_level_id IS NULL;
ALTER TABLE dataset ALTER COLUMN security_level_id SET DEFAULT tenant_default_security_level_id();
ALTER TABLE dataset ALTER COLUMN security_level_id SET NOT NULL;
ALTER TABLE dataset ADD CONSTRAINT fk_dataset_security_level
  FOREIGN KEY (tenant_id, security_level_id) REFERENCES security_level (tenant_id, id);
CREATE INDEX idx_dataset_security_level ON dataset (security_level_id);

-- ── (5) role.max_security_level_id ─────────────────────────────────────────────
ALTER TABLE role ADD COLUMN max_security_level_id BIGINT;
-- 시스템 ADMIN = 최상위(스펙 §2.3), 그 외 = 기본.
UPDATE role r SET max_security_level_id = (
  SELECT x.id FROM security_level x WHERE x.tenant_id = r.tenant_id ORDER BY x.rank DESC LIMIT 1)
WHERE r.name = 'ADMIN' AND r.is_system;
UPDATE role r SET max_security_level_id = sl.id
FROM security_level sl
WHERE sl.tenant_id = r.tenant_id AND sl.is_default AND r.max_security_level_id IS NULL;
ALTER TABLE role ALTER COLUMN max_security_level_id SET DEFAULT tenant_default_security_level_id();
ALTER TABLE role ALTER COLUMN max_security_level_id SET NOT NULL;
ALTER TABLE role ADD CONSTRAINT fk_role_security_level
  FOREIGN KEY (tenant_id, max_security_level_id) REFERENCES security_level (tenant_id, id);
CREATE INDEX idx_role_security_level ON role (max_security_level_id);

-- ── (6) dataset_access_grant ────────────────────────────────────────────────────
CREATE TABLE dataset_access_grant (
  id         BIGSERIAL PRIMARY KEY,
  tenant_id  BIGINT    NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint,
  dataset_id BIGINT    NOT NULL REFERENCES dataset (id) ON DELETE CASCADE,
  -- 사용자 삭제·역할 삭제 시 항목도 사라진다. 역할 삭제로 허용 목록이 비는 것은 RoleService 가 사전에 막는다.
  user_id    BIGINT    REFERENCES "user" (id) ON DELETE CASCADE,
  role_id    BIGINT    REFERENCES role (id) ON DELETE CASCADE,
  granted_by BIGINT,
  granted_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_dataset_access_grant_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  -- 사용자 XOR 역할(스펙 §3).
  CONSTRAINT ck_dataset_access_grant_subject CHECK ((user_id IS NULL) <> (role_id IS NULL)),
  CONSTRAINT uq_dataset_access_grant_user UNIQUE (dataset_id, user_id),
  CONSTRAINT uq_dataset_access_grant_role UNIQUE (dataset_id, role_id)
);
CREATE INDEX idx_dataset_access_grant_tenant ON dataset_access_grant (tenant_id);
CREATE INDEX idx_dataset_access_grant_dataset ON dataset_access_grant (dataset_id);

ALTER TABLE dataset_access_grant ENABLE ROW LEVEL SECURITY;
CREATE POLICY dataset_access_grant_tenant_isolation ON dataset_access_grant
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

-- ── (7) 권한 4종 + 시스템 ADMIN 부여 ───────────────────────────────────────────
INSERT INTO permission (code, description, category) VALUES
  ('security:settings',      '보안 등급·정책 관리',                 'security'),
  ('dataset:classify',       '데이터셋 보안 등급 변경',             'security'),
  ('dataset:grant',          '데이터셋 허용 목록 관리',             'security'),
  ('data:export_restricted', '''권한 필요'' 등급 데이터 내보내기',   'security')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (tenant_id, role_id, permission_id)
SELECT r.tenant_id, r.id, p.id
FROM role r
JOIN permission p ON p.code IN ('security:settings', 'dataset:classify', 'dataset:grant', 'data:export_restricted')
WHERE r.name = 'ADMIN' AND r.is_system
ON CONFLICT DO NOTHING;

-- ── (8) 프로비저닝 — V121 본문 + 등급 시드(역할 복제보다 먼저) ─────────────────────
-- CREATE OR REPLACE 는 전체 본문이 필요하다. V98/V100/V113/V121 을 편집하지 않는 이유: 적용된 DB 의 checksum mismatch.
-- 순서가 중요하다: role.max_security_level_id 가 NOT NULL 이고, 이 함수는 정의자 권한으로 돌며 대상 테넌트의
-- GUC 가 설정돼 있지 않을 수 있어(운영자 SQL 절차) DEFAULT 함수가 NULL 을 낸다 — 그래서 등급을 먼저 넣고 역할에 명시한다.
CREATE OR REPLACE FUNCTION provision_tenant_defaults(p_tenant_id bigint)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_source_tenant CONSTANT bigint := 1;
  v_seed_categories CONSTANT text[] := ARRAY['행정', '운영', '통계'];
  v_default_level bigint;
  v_top_level bigint;
BEGIN
  IF p_tenant_id = v_source_tenant THEN
    RETURN;
  END IF;

  -- (V133) 기본 4등급. 이미 있으면(재실행) 건너뛴다.
  INSERT INTO security_level
    (tenant_id, rank, name, is_default, allowlist_required, admin_bypass,
     export_policy, ai_policy, share_policy, audit_access)
  SELECT p_tenant_id, s.rank, s.name, s.is_default, s.allowlist_required, FALSE,
         s.export_policy, s.ai_policy, s.share_policy, s.audit_access
  FROM (VALUES
    (1, '공개', FALSE, FALSE, 'ALLOW',      'ALL',              'ALLOW', FALSE),
    (2, '내부', TRUE,  FALSE, 'ALLOW',      'ALL',              'ALLOW', FALSE),
    (3, '민감', FALSE, FALSE, 'PERMISSION', 'SELF_HOSTED_ONLY', 'ALLOW', TRUE),
    (4, '기밀', FALSE, TRUE,  'DENY',       'SELF_HOSTED_ONLY', 'DENY',  TRUE)
  ) AS s(rank, name, is_default, allowlist_required, export_policy, ai_policy, share_policy, audit_access)
  -- 이름·순위 단위로 건너뛴다: 테스트 지원 코드(createActiveTenant)가 기본 등급 1개만 먼저 심은 테넌트에도 나머지가 채워진다.
  WHERE NOT EXISTS (
    SELECT 1 FROM security_level x
    WHERE x.tenant_id = p_tenant_id AND (x.name = s.name OR x.rank = s.rank));

  SELECT id INTO v_default_level FROM security_level WHERE tenant_id = p_tenant_id AND is_default;
  SELECT id INTO v_top_level FROM security_level WHERE tenant_id = p_tenant_id ORDER BY rank DESC LIMIT 1;

  -- 시스템 역할 복제(V98~) + (V133) 자격 명시: ADMIN=최상위, 그 외=기본.
  INSERT INTO role (tenant_id, name, description, is_system, max_security_level_id)
  SELECT p_tenant_id, r.name, r.description, r.is_system,
         CASE WHEN r.name = 'ADMIN' THEN v_top_level ELSE v_default_level END
  FROM role r
  WHERE r.tenant_id = v_source_tenant
    AND r.is_system = true
    AND NOT EXISTS (
      SELECT 1 FROM role x WHERE x.tenant_id = p_tenant_id AND x.name = r.name);

  INSERT INTO role_permission (tenant_id, role_id, permission_id)
  SELECT p_tenant_id, tgt.id, srp.permission_id
  FROM role_permission srp
  JOIN role src ON src.id = srp.role_id AND src.tenant_id = v_source_tenant AND src.is_system = true
  JOIN role tgt ON tgt.tenant_id = p_tenant_id AND tgt.name = src.name
  WHERE NOT EXISTS (
    SELECT 1 FROM role_permission x
    WHERE x.role_id = tgt.id AND x.permission_id = srp.permission_id);

  INSERT INTO report_template (tenant_id, user_id, name, description, sections, style, created_at, updated_at)
  SELECT p_tenant_id, NULL, t.name, t.description, t.sections, t.style, now(), now()
  FROM report_template t
  WHERE t.tenant_id = v_source_tenant
    AND t.user_id IS NULL
    AND NOT EXISTS (
      SELECT 1 FROM report_template x
      WHERE x.tenant_id = p_tenant_id AND x.user_id IS NULL AND x.name = t.name);

  INSERT INTO dataset_category (tenant_id, name, created_at, updated_at)
  SELECT p_tenant_id, c.name, now(), now()
  FROM unnest(v_seed_categories) AS c(name)
  WHERE NOT EXISTS (
    SELECT 1 FROM dataset_category x
    WHERE x.tenant_id = p_tenant_id AND x.name = c.name);

  INSERT INTO user_role (user_id, role_id, tenant_id)
  SELECT m.user_id, r.id, p_tenant_id
  FROM membership m
  JOIN role r ON r.tenant_id = p_tenant_id AND r.name = 'ADMIN'
  WHERE m.tenant_id = p_tenant_id
    AND m.role = 'OWNER'
  ON CONFLICT (user_id, role_id) DO NOTHING;
END;
$$;

REVOKE EXECUTE ON FUNCTION provision_tenant_defaults(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION provision_tenant_defaults(bigint) TO app_tenant;
