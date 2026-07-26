-- 표 데이터셋 → 지식그래프(슬라이스 1): 컬럼→온톨로지 요소 수동 매핑 문서를 데이터셋당 1개 저장한다.
-- 로더가 매핑을 원자적으로 소비하므로 정규화 대신 JSONB 문서 1행(정합은 저장/활성화 시점 코드 검증).
CREATE TABLE IF NOT EXISTS dataset_mapping (
    id          BIGSERIAL   PRIMARY KEY,
    dataset_id  BIGINT      NOT NULL UNIQUE,                         -- 데이터셋당 1개(N:1은 dataset_ontology가 보장)
    ontology_id BIGINT      NOT NULL REFERENCES ontology(id) ON DELETE CASCADE,
    spec        JSONB       NOT NULL,                                -- {entities:[...], relations:[...]}
    status      VARCHAR(20) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','active')),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by  BIGINT
);

-- 온톨로지별 매핑 역조회용 인덱스.
CREATE INDEX IF NOT EXISTS idx_dataset_mapping_ontology ON dataset_mapping (ontology_id);
