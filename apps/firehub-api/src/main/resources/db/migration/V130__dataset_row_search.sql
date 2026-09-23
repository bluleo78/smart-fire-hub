-- 데이터셋 행 검색(설계: docs/superpowers/specs/2026-09-23-dataset-row-search-design.md)
-- 1) 검색 대상 필드 설정: TEXT/VARCHAR 필드에만 true 허용(서비스 검증). 합치는 순서는 column_order.
-- 2) 데이터셋당 색인 상태(0..1행). 색인 데이터 자체는 테넌트 데이터 스키마의 fh_search_{dataset_id} 에 런타임 DDL 로 만든다.
SET LOCAL lock_timeout = '3s';

ALTER TABLE dataset_column
  ADD COLUMN IF NOT EXISTS is_searchable BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS dataset_search_index (
  dataset_id           BIGINT PRIMARY KEY REFERENCES dataset(id) ON DELETE CASCADE,
  tenant_id            BIGINT NOT NULL
                       DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint
                       REFERENCES tenant(id),
  status               VARCHAR(20) NOT NULL DEFAULT 'SYNCING',  -- IDLE | SYNCING | ERROR
  config_hash          VARCHAR(64) NOT NULL DEFAULT '',          -- '' = 강제 전체 재색인
  embedding_model      VARCHAR(100),
  embedding_dim        INT,
  source_table_oid     BIGINT,                                   -- 원본 테이블 OID(swap 감지)
  sync_cursor          TIMESTAMPTZ,                              -- 완료된 패스의 _updated_at 책갈피
  pass_cursor          TIMESTAMPTZ,                              -- 진행 중 패스의 책갈피 후보(패스 시작 시 캡처)
  resume_after_id      BIGINT,                                   -- 진행 중 패스의 키셋 위치
  indexed_rows         BIGINT NOT NULL DEFAULT 0,
  total_rows           BIGINT NOT NULL DEFAULT 0,
  consecutive_failures INT NOT NULL DEFAULT 0,
  next_attempt_at      TIMESTAMPTZ,
  sync_lease_until     TIMESTAMPTZ,
  last_synced_at       TIMESTAMPTZ,
  last_error           TEXT,
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dataset_search_index_tenant ON dataset_search_index(tenant_id);

-- V88 패턴: FORCE 는 쓰지 않는다(소유자 롤 우회 정책 유지).
ALTER TABLE dataset_search_index ENABLE ROW LEVEL SECURITY;
-- 부분 적용 후 재실행에도 안전하도록 정책을 먼저 지운다(다른 문장은 IF NOT EXISTS 로 멱등).
DROP POLICY IF EXISTS dataset_search_index_tenant_isolation ON dataset_search_index;
CREATE POLICY dataset_search_index_tenant_isolation ON dataset_search_index
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
