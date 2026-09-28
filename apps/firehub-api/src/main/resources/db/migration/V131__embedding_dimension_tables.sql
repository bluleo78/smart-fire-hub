-- V131: 임베딩 벡터를 차원별 테이블로 분리하고 플랫폼 임베딩 설정·플랫폼 설정 권한을 지운다(#713, #392).
-- 설계: docs/superpowers/specs/2026-09-28-embedding-dimension-tables-design.md §2
--
-- ** 되돌릴 수 없다 ** — document_chunk/dataset_embedding 의 embedding·embedding_model 컬럼을 DROP 한다.
-- api 만 이 컬럼을 쓰고 api 는 이 마이그레이션과 함께 배포되므로 하위 호환 기간을 두지 않는다. 배포 전 DB 덤프 필수.
--
-- 순서가 중요하다(V87/V89 규칙): 테이블 생성(tenant_id DEFAULT 없음) → 백필(INSERT…SELECT 가 tenant_id 를 명시)
-- → DEFAULT 부착 → 인덱스 → RLS → 옛 컬럼 DROP. Flyway 는 GUC 없이 도는 소유자 롤이라 DEFAULT 를 먼저 붙이면
-- current_setting 이 NULL 을 줘 NOT NULL 위반이 난다.
--
-- 플랫폼 값을 테넌트로 복사하지 않는다(의도, #706 과 같은 방향). 적용 직후 모든 테넌트는 "임베딩 미설정"이고
-- 테넌트 관리자가 설정 > 임베딩에서 직접 저장한다.
SET LOCAL lock_timeout = '3s';

-- 0) 가정 명시: 옛 컬럼은 vector(1024) 라 다른 차원이 있을 수 없지만, 있으면 여기서 멈춘다.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM document_chunk WHERE embedding IS NOT NULL AND vector_dims(embedding) <> 1024) THEN
    RAISE EXCEPTION 'V131: document_chunk 에 1024 가 아닌 벡터가 있다 — 이관 규칙이 없다';
  END IF;
  IF EXISTS (SELECT 1 FROM dataset_embedding WHERE embedding IS NOT NULL AND vector_dims(embedding) <> 1024) THEN
    RAISE EXCEPTION 'V131: dataset_embedding 에 1024 가 아닌 벡터가 있다 — 이관 규칙이 없다';
  END IF;
END $$;

-- 1) 복합 FK 대상. 두 부모 모두 tenant_id NOT NULL(V87·V89).
ALTER TABLE document_chunk ADD CONSTRAINT uq_document_chunk_id_tenant UNIQUE (id, tenant_id);
ALTER TABLE dataset_embedding ADD CONSTRAINT uq_dataset_embedding_id_tenant UNIQUE (dataset_id, tenant_id);

-- 2) 차원별 벡터 테이블. 부모와의 복합 FK 가 "벡터 행의 tenant_id = 부모의 tenant_id" 를 DB 로 강제한다 —
--    벡터 테이블 단독 쿼리(카운트·삭제)에서 조인을 빠뜨려도 교차 테넌트 누출이 없게.
CREATE TABLE document_chunk_vec_1024 (
  chunk_id        BIGINT PRIMARY KEY,
  tenant_id       BIGINT NOT NULL REFERENCES tenant(id),
  dataset_id      BIGINT NOT NULL,  -- 비정규화: 부모 조인 전 데이터셋 필터(부모 dataset_id 는 불변)
  embedding       vector(1024) NOT NULL,
  embedding_model VARCHAR(100) NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_document_chunk_vec_1024_parent FOREIGN KEY (chunk_id, tenant_id)
    REFERENCES document_chunk(id, tenant_id) ON DELETE CASCADE
);
CREATE TABLE document_chunk_vec_1536 (
  chunk_id        BIGINT PRIMARY KEY,
  tenant_id       BIGINT NOT NULL REFERENCES tenant(id),
  dataset_id      BIGINT NOT NULL,
  embedding       vector(1536) NOT NULL,
  embedding_model VARCHAR(100) NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_document_chunk_vec_1536_parent FOREIGN KEY (chunk_id, tenant_id)
    REFERENCES document_chunk(id, tenant_id) ON DELETE CASCADE
);
CREATE TABLE dataset_embedding_vec_1024 (
  dataset_id      BIGINT PRIMARY KEY,
  tenant_id       BIGINT NOT NULL REFERENCES tenant(id),
  embedding       vector(1024) NOT NULL,
  embedding_model VARCHAR(100) NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_dataset_embedding_vec_1024_parent FOREIGN KEY (dataset_id, tenant_id)
    REFERENCES dataset_embedding(dataset_id, tenant_id) ON DELETE CASCADE
);
CREATE TABLE dataset_embedding_vec_1536 (
  dataset_id      BIGINT PRIMARY KEY,
  tenant_id       BIGINT NOT NULL REFERENCES tenant(id),
  embedding       vector(1536) NOT NULL,
  embedding_model VARCHAR(100) NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_dataset_embedding_vec_1536_parent FOREIGN KEY (dataset_id, tenant_id)
    REFERENCES dataset_embedding(dataset_id, tenant_id) ON DELETE CASCADE
);

-- 3) 백필. 모델 NULL 은 '<unknown>' — 현재 설정 모델과 절대 일치하지 않아 검색에서 빠지고 재임베딩 대상이 된다(의도).
INSERT INTO document_chunk_vec_1024 (chunk_id, tenant_id, dataset_id, embedding, embedding_model)
SELECT id, tenant_id, dataset_id, embedding, COALESCE(embedding_model, '<unknown>')
  FROM document_chunk WHERE embedding IS NOT NULL;
INSERT INTO dataset_embedding_vec_1024 (dataset_id, tenant_id, embedding, embedding_model, updated_at)
SELECT dataset_id, tenant_id, embedding, COALESCE(embedding_model, '<unknown>'), updated_at
  FROM dataset_embedding WHERE embedding IS NOT NULL;

-- 4) DEFAULT 는 백필 뒤(위 주석).
ALTER TABLE document_chunk_vec_1024   ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE document_chunk_vec_1536   ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE dataset_embedding_vec_1024 ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE dataset_embedding_vec_1536 ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;

-- 5) 인덱스(백필 뒤에 만들어야 HNSW 빌드가 한 번이다).
CREATE INDEX idx_document_chunk_vec_1024_embedding ON document_chunk_vec_1024 USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_document_chunk_vec_1024_dataset   ON document_chunk_vec_1024 (dataset_id);
CREATE INDEX idx_document_chunk_vec_1024_tenant    ON document_chunk_vec_1024 (tenant_id);
CREATE INDEX idx_document_chunk_vec_1536_embedding ON document_chunk_vec_1536 USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_document_chunk_vec_1536_dataset   ON document_chunk_vec_1536 (dataset_id);
CREATE INDEX idx_document_chunk_vec_1536_tenant    ON document_chunk_vec_1536 (tenant_id);
CREATE INDEX idx_dataset_embedding_vec_1024_embedding ON dataset_embedding_vec_1024 USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_dataset_embedding_vec_1024_tenant    ON dataset_embedding_vec_1024 (tenant_id);
CREATE INDEX idx_dataset_embedding_vec_1536_embedding ON dataset_embedding_vec_1536 USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_dataset_embedding_vec_1536_tenant    ON dataset_embedding_vec_1536 (tenant_id);

-- 6) 테넌트 재임베딩 상태(테넌트당 0..1행). 임대(lease_until)로 동시 실행을 막고 실패 사유를 화면에 보여준다.
--    JobRunr 고정 잡 ID 는 쓰지 않는다 — JobRunr 7 은 같은 id 재투입을 무시해 A→B→A 전환의 두 번째 A 가 조용히 버려진다.
CREATE TABLE embedding_reembed_state (
  tenant_id   BIGINT PRIMARY KEY
              DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint
              REFERENCES tenant(id) ON DELETE CASCADE,
  status      VARCHAR(20) NOT NULL DEFAULT 'IDLE',  -- IDLE | RUNNING | DONE | FAILED | SUPERSEDED
  model       VARCHAR(100),
  dimension   INT,
  lease_until TIMESTAMPTZ,
  last_error  TEXT,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 7) RLS — V90 표준 정책. FORCE 는 쓰지 않는다(소유자 롤 우회 유지, 프로젝트 규칙).
ALTER TABLE document_chunk_vec_1024 ENABLE ROW LEVEL SECURITY;
CREATE POLICY document_chunk_vec_1024_tenant_isolation ON document_chunk_vec_1024
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
ALTER TABLE document_chunk_vec_1536 ENABLE ROW LEVEL SECURITY;
CREATE POLICY document_chunk_vec_1536_tenant_isolation ON document_chunk_vec_1536
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
ALTER TABLE dataset_embedding_vec_1024 ENABLE ROW LEVEL SECURITY;
CREATE POLICY dataset_embedding_vec_1024_tenant_isolation ON dataset_embedding_vec_1024
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
ALTER TABLE dataset_embedding_vec_1536 ENABLE ROW LEVEL SECURITY;
CREATE POLICY dataset_embedding_vec_1536_tenant_isolation ON dataset_embedding_vec_1536
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
ALTER TABLE embedding_reembed_state ENABLE ROW LEVEL SECURITY;
CREATE POLICY embedding_reembed_state_tenant_isolation ON embedding_reembed_state
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

-- 8) 옛 벡터 인덱스·컬럼 제거(비가역).
DROP INDEX IF EXISTS idx_document_chunk_embedding;
DROP INDEX IF EXISTS idx_dataset_embedding_vector;
ALTER TABLE document_chunk    DROP COLUMN embedding, DROP COLUMN embedding_model;
ALTER TABLE dataset_embedding DROP COLUMN embedding, DROP COLUMN embedding_model;

-- 9) 플랫폼 임베딩 설정 제거(V126 선례). 읽는 코드는 이미 없다(EmbeddingProviderFactory 는 테넌트 문서만 읽는다).
DELETE FROM system_settings WHERE key LIKE 'embedding.%';

-- 10) 플랫폼 설정 권한 2건 제거(V116·V117 선례). 이 두 코드를 요구하던 유일한 라우트
--     PlatformSettingsController(플랫폼 임베딩 설정)는 #713 Task 2 에서 삭제됐다. 부여 행을 남겨 두면 누군가
--     다른 라우트에 @RequirePermission("platform:settings:*") 를 붙이는 순간 부여 결정 없이 SUPER_ADMIN 에게
--     열린다 — 고아 권한은 "무해"가 아니다.
--     permission_id FK 는 role_permission(V1)·platform_role_permission(V82) 모두 ON DELETE CASCADE 지만,
--     이 파일만 읽어도 범위가 드러나도록 매핑을 먼저 명시적으로 지운다. Flyway 는 소유자 롤(FORCE 없음)이라
--     role_permission 의 RLS 를 우회해 모든 테넌트의 행에 닿는다. 코드 완전 일치라 다른 platform:* 권한은 무관.
DELETE FROM role_permission
 WHERE permission_id IN (SELECT id FROM permission WHERE code IN ('platform:settings:read', 'platform:settings:write'));
DELETE FROM platform_role_permission
 WHERE permission_id IN (SELECT id FROM permission WHERE code IN ('platform:settings:read', 'platform:settings:write'));
DELETE FROM permission WHERE code IN ('platform:settings:read', 'platform:settings:write');
