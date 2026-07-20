-- FILE(오브젝트) 데이터셋 타입 추가.
-- storage_type CHECK 제약을 재정의하고, MinIO 버킷/프리픽스 매핑 테이블을 만든다.

-- 1) storage_type 허용값에 'FILE' 추가 (drop 후 재생성 — V61/V66 패턴)
ALTER TABLE dataset DROP CONSTRAINT IF EXISTS dataset_storage_type_check;
ALTER TABLE dataset ADD CONSTRAINT dataset_storage_type_check
    CHECK (storage_type IN ('TABLE', 'DOCUMENT', 'FILE'));

-- 2) FILE 데이터셋의 MinIO 버킷/프리픽스 매핑 (데이터셋 1:1, 개별 파일 행은 저장하지 않음)
CREATE TABLE IF NOT EXISTS file_dataset_config (
    dataset_id  BIGINT PRIMARY KEY REFERENCES dataset(id) ON DELETE CASCADE,
    bucket      VARCHAR(63)  NOT NULL,
    prefix      VARCHAR(500) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE file_dataset_config IS 'FILE 데이터셋 → MinIO 버킷/프리픽스 매핑';
