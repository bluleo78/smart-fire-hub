-- V87: dataset 도메인에 tenant_id 를 추가한다 (정책은 V88 에서 분리해 적용).
--
-- 순서가 중요하다: nullable 추가 → 백필 → DEFAULT → NOT NULL.
-- DEFAULT 를 먼저 넣으면 Flyway 는 GUC 없이 도는 소유자 롤이라 current_setting 이 NULL 을 주고
-- NOT NULL 위반으로 마이그레이션이 깨진다.
--
-- 루트 테이블은 부모가 없으므로 기본 테넌트(V81 이 시드한 id=1)로 백필한다. 전환 시점에 테넌트는
-- 하나뿐이라 이것이 정확한 값이다. 별도의 NULL 잔존 가드는 두지 않는다 — SET NOT NULL 이 그 가드다.

-- ── 루트: dataset_category ─────────────────────────────────────────────
ALTER TABLE dataset_category ADD COLUMN tenant_id BIGINT;
UPDATE dataset_category SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE dataset_category ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE dataset_category ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE dataset_category
  ADD CONSTRAINT fk_dataset_category_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_dataset_category_tenant ON dataset_category(tenant_id);

-- 이름 유니크에 테넌트를 접는다. 유니크 인덱스는 RLS 와 무관하게 전역으로 적용되므로, 접지 않으면
-- 다른 테넌트가 같은 카테고리명을 만들 때 충돌 에러가 표면화된다.
ALTER TABLE dataset_category DROP CONSTRAINT dataset_category_name_key;
CREATE UNIQUE INDEX dataset_category_tenant_name_key ON dataset_category(tenant_id, name);

-- ── 루트: dataset ──────────────────────────────────────────────────────
-- category_id / source_pipeline_step_id FK 가 있지만 둘 다 nullable 이라 백필 경로로 쓸 수 없다.
ALTER TABLE dataset ADD COLUMN tenant_id BIGINT;
UPDATE dataset SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE dataset ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE dataset ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE dataset ADD CONSTRAINT fk_dataset_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_dataset_tenant ON dataset(tenant_id);

DROP INDEX idx_dataset_name;
CREATE UNIQUE INDEX idx_dataset_name ON dataset(tenant_id, name);

-- idx_dataset_table_name 은 의도적으로 건드리지 않는다. table_name 은 공유 data 스키마의 실제
-- 테이블명이라, 스키마가 하나로 남아 있는 동안 테넌트별 유니크로 바꾸면 두 테넌트가 같은
-- 물리 테이블을 주장한다. P3(스키마-per-테넌트)에서 함께 바꾼다.

-- ── 자식: dataset_id 로 백필 (모두 NOT NULL FK) ────────────────────────
ALTER TABLE dataset_column ADD COLUMN tenant_id BIGINT;
UPDATE dataset_column c SET tenant_id = p.tenant_id FROM dataset p WHERE c.dataset_id = p.id;
ALTER TABLE dataset_column ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE dataset_column ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE dataset_column
  ADD CONSTRAINT fk_dataset_column_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_dataset_column_tenant ON dataset_column(tenant_id);

ALTER TABLE dataset_tag ADD COLUMN tenant_id BIGINT;
UPDATE dataset_tag c SET tenant_id = p.tenant_id FROM dataset p WHERE c.dataset_id = p.id;
ALTER TABLE dataset_tag ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE dataset_tag ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE dataset_tag
  ADD CONSTRAINT fk_dataset_tag_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_dataset_tag_tenant ON dataset_tag(tenant_id);

ALTER TABLE dataset_favorite ADD COLUMN tenant_id BIGINT;
UPDATE dataset_favorite c SET tenant_id = p.tenant_id FROM dataset p WHERE c.dataset_id = p.id;
ALTER TABLE dataset_favorite ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE dataset_favorite ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE dataset_favorite
  ADD CONSTRAINT fk_dataset_favorite_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_dataset_favorite_tenant ON dataset_favorite(tenant_id);

ALTER TABLE dataset_embedding ADD COLUMN tenant_id BIGINT;
UPDATE dataset_embedding c SET tenant_id = p.tenant_id FROM dataset p WHERE c.dataset_id = p.id;
ALTER TABLE dataset_embedding ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE dataset_embedding ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE dataset_embedding
  ADD CONSTRAINT fk_dataset_embedding_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_dataset_embedding_tenant ON dataset_embedding(tenant_id);

ALTER TABLE file_dataset_config ADD COLUMN tenant_id BIGINT;
UPDATE file_dataset_config c SET tenant_id = p.tenant_id FROM dataset p WHERE c.dataset_id = p.id;
ALTER TABLE file_dataset_config ALTER COLUMN tenant_id
  SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE file_dataset_config ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE file_dataset_config
  ADD CONSTRAINT fk_file_dataset_config_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
CREATE INDEX idx_file_dataset_config_tenant ON file_dataset_config(tenant_id);
