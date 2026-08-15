-- V97: P2-c 컬럼 단계. RBAC 3테이블 + audit_log + report_template 에 tenant_id 를 추가한다.
-- 정책은 V99 에서 분리해 적용한다 — 여기서 정책까지 켜면 이 커밋부터 V99 직전까지 모든
-- 인증 요청이 권한 0개가 되고 회원가입이 깨진다(P2-b 의 "정책은 맨 뒤" 규칙과 같은 이유).
--
-- 순서: nullable 추가 → 백필 → DEFAULT → NOT NULL → FK → 인덱스.
-- DEFAULT 를 먼저 넣으면 Flyway 는 GUC 없이 도는 소유자 롤이라 current_setting 이 NULL 을 주고
-- NOT NULL 위반으로 마이그레이션이 깨진다.
--
-- 전환 시점에 테넌트는 하나뿐이므로 루트 백필값 1 은 정확하다.

-- ── role ───────────────────────────────────────────────────────────────
-- 시스템 역할(ADMIN/USER)도 테넌트별 사본이 된다. 전역 역할 하나를 여러 테넌트가 공유하면
-- role_permission 편집이 크로스테넌트로 새기 때문이다.
ALTER TABLE role ADD COLUMN tenant_id BIGINT;
UPDATE role SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE role ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE role ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE role ADD CONSTRAINT fk_role_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_role_tenant ON role(tenant_id);

-- 이름 유니크에 테넌트를 접는다. 유니크 인덱스는 RLS 와 무관하게 전역으로 적용되므로,
-- 접지 않으면 두 번째 테넌트의 'USER' 역할 생성이 충돌 에러로 표면화된다.
ALTER TABLE role DROP CONSTRAINT role_name_key;
CREATE UNIQUE INDEX role_tenant_name_key ON role(tenant_id, name);

-- ── role_permission (role_id 로 백필) ──────────────────────────────────
-- permission 은 전역 코드 카탈로그라 tenant_id 를 갖지 않는다 — 매핑 테이블만 스코프한다.
ALTER TABLE role_permission ADD COLUMN tenant_id BIGINT;
UPDATE role_permission rp SET tenant_id = r.tenant_id FROM role r WHERE rp.role_id = r.id;
ALTER TABLE role_permission ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE role_permission ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE role_permission
  ADD CONSTRAINT fk_role_permission_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_role_permission_tenant ON role_permission(tenant_id);

-- ── user_role (role_id 로 백필) ────────────────────────────────────────
-- PK 는 (user_id, role_id) 그대로 둔다. role_id 가 이미 테넌트 스코프이므로 한 사용자가
-- 여러 테넌트에서 각각 역할을 갖는 것이 자연스럽게 표현된다.
ALTER TABLE user_role ADD COLUMN tenant_id BIGINT;
UPDATE user_role ur SET tenant_id = r.tenant_id FROM role r WHERE ur.role_id = r.id;
ALTER TABLE user_role ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE user_role ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE user_role
  ADD CONSTRAINT fk_user_role_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_user_role_tenant ON user_role(tenant_id);

-- ── audit_log — 표준 레시피의 SET NOT NULL 을 적용하지 않는다 ──────────
-- 로그인·회원가입 감사 이벤트는 테넌트 선택 전에 발생해 tenant_id 가 NULL 이다.
-- NOT NULL 을 걸면 로그인이 깨진다. V99 의 정책도 형태 (b)(IS NOT DISTINCT FROM)를 쓴다.
ALTER TABLE audit_log ADD COLUMN tenant_id BIGINT;
UPDATE audit_log SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE audit_log ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE audit_log
  ADD CONSTRAINT fk_audit_log_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_audit_log_tenant ON audit_log(tenant_id);

-- ── report_template ────────────────────────────────────────────────────
-- 내장 양식 3건은 V42 가 user_id = NULL 로 시드한 전역 행이다. 백필로 테넌트 #1 소유가 되고,
-- 신규 테넌트용 사본은 V98 의 provision_tenant_defaults 가 만든다.
ALTER TABLE report_template ADD COLUMN tenant_id BIGINT;
UPDATE report_template SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE report_template ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE report_template ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE report_template
  ADD CONSTRAINT fk_report_template_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_report_template_tenant ON report_template(tenant_id);

-- ── 이슈 #384-1: per-tenant 정리 루프용 복합 인덱스 ────────────────────
-- 정리 잡은 테넌트마다 도는데 술어는 비-테넌트 컬럼이라, Postgres 가 RLS 술어를 인덱스 스캔
-- 뒤에 행 필터로 적용한다 → 주기당 작업량이 O(행수 × 테넌트수)가 된다. 테넌트 선두 인덱스로
-- 각 패스가 자기 테넌트 구간만 훑게 만든다.
CREATE INDEX idx_async_job_tenant_updated ON async_job(tenant_id, updated_at)
  WHERE stage NOT IN ('COMPLETED', 'FAILED');
CREATE INDEX idx_async_job_tenant_created ON async_job(tenant_id, created_at);
CREATE INDEX idx_trigger_event_tenant_created ON trigger_event(tenant_id, created_at);
CREATE INDEX idx_api_connection_tenant_healthcheck ON api_connection(tenant_id)
  WHERE health_check_path IS NOT NULL;
