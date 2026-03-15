-- 1. dataset_type CHECK 제약조건에 TEMP 추가
ALTER TABLE dataset DROP CONSTRAINT IF EXISTS dataset_dataset_type_check;
ALTER TABLE dataset ADD CONSTRAINT dataset_dataset_type_check
    CHECK (dataset_type IN ('SOURCE', 'DERIVED', 'TEMP'));

-- 2. 임시 데이터셋과 파이프라인 스텝 연결 FK
ALTER TABLE dataset ADD COLUMN IF NOT EXISTS source_pipeline_step_id BIGINT
    REFERENCES pipeline_step(id) ON DELETE SET NULL;

-- 3. 인덱스 (임시 데이터셋 검색 최적화)
CREATE INDEX IF NOT EXISTS idx_dataset_source_pipeline_step
    ON dataset(source_pipeline_step_id) WHERE source_pipeline_step_id IS NOT NULL;
