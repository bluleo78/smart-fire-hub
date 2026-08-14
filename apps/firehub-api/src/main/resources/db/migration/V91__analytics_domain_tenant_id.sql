-- V91: analytics 도메인에 tenant_id 를 추가한다 (정책은 V92).
--
-- saved_query 는 dataset_id 가 nullable 이라 조인 백필이 NULL 을 남긴다 → 루트로 취급해
-- 기본 테넌트로 백필한다. dashboard 도 목록 안에 부모가 없어 같다.
-- 루트 → 자식 순서를 지킨다(자식이 부모의 tenant_id 를 읽는다).

-- ── 루트: saved_query ──────────────────────────────────────────────────
ALTER TABLE saved_query ADD COLUMN tenant_id BIGINT;
UPDATE saved_query SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE saved_query ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE saved_query ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE saved_query
  ADD CONSTRAINT fk_saved_query_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_saved_query_tenant ON saved_query(tenant_id);

-- ── 루트: dashboard ────────────────────────────────────────────────────
ALTER TABLE dashboard ADD COLUMN tenant_id BIGINT;
UPDATE dashboard SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE dashboard ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE dashboard ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE dashboard
  ADD CONSTRAINT fk_dashboard_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_dashboard_tenant ON dashboard(tenant_id);

-- ── 자식: chart ← saved_query (NOT NULL) ───────────────────────────────
ALTER TABLE chart ADD COLUMN tenant_id BIGINT;
UPDATE chart c SET tenant_id = p.tenant_id FROM saved_query p WHERE c.saved_query_id = p.id;
ALTER TABLE chart ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE chart ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE chart ADD CONSTRAINT fk_chart_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_chart_tenant ON chart(tenant_id);

-- ── 자식: dashboard_widget ← dashboard (NOT NULL) ──────────────────────
ALTER TABLE dashboard_widget ADD COLUMN tenant_id BIGINT;
UPDATE dashboard_widget c SET tenant_id = p.tenant_id FROM dashboard p WHERE c.dashboard_id = p.id;
ALTER TABLE dashboard_widget ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE dashboard_widget ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE dashboard_widget
  ADD CONSTRAINT fk_dashboard_widget_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_dashboard_widget_tenant ON dashboard_widget(tenant_id);

-- ── 자식: query_history ← dataset (NOT NULL) ───────────────────────────
ALTER TABLE query_history ADD COLUMN tenant_id BIGINT;
UPDATE query_history c SET tenant_id = p.tenant_id FROM dataset p WHERE c.dataset_id = p.id;
ALTER TABLE query_history ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE query_history ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE query_history
  ADD CONSTRAINT fk_query_history_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_query_history_tenant ON query_history(tenant_id);

-- 이 도메인에는 tenant_id 를 접어야 하는 유니크 인덱스가 없다 — 전부 서로게이트 PK 이거나
-- 이미 부모 스코프다(2026-08-14 라이브 확인).
