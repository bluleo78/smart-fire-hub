-- V66: dataset_type 단일 컬럼을 storage_type(저장 방식) + origin_type(출처) 두 축으로 분리한다.
-- 기존 값은 무손실 이전한다: DOCUMENT→(DOCUMENT,SOURCE), 그 외→(TABLE, 기존값).

-- 1) 새 컬럼 추가 (백필 전까지 nullable)
ALTER TABLE dataset ADD COLUMN IF NOT EXISTS storage_type VARCHAR(10);
ALTER TABLE dataset ADD COLUMN IF NOT EXISTS origin_type VARCHAR(10);

-- 2) 기존 dataset_type 값을 두 축으로 백필
UPDATE dataset SET
    storage_type = CASE WHEN dataset_type = 'DOCUMENT' THEN 'DOCUMENT' ELSE 'TABLE' END,
    origin_type  = CASE WHEN dataset_type = 'DOCUMENT' THEN 'SOURCE'   ELSE dataset_type END
WHERE storage_type IS NULL OR origin_type IS NULL;

-- 3) NOT NULL + CHECK 제약 부여
ALTER TABLE dataset ALTER COLUMN storage_type SET NOT NULL;
ALTER TABLE dataset ALTER COLUMN origin_type SET NOT NULL;
ALTER TABLE dataset ADD CONSTRAINT dataset_storage_type_check CHECK (storage_type IN ('TABLE', 'DOCUMENT'));
ALTER TABLE dataset ADD CONSTRAINT dataset_origin_type_check  CHECK (origin_type IN ('SOURCE', 'DERIVED', 'TEMP'));

-- 4) 기존 컬럼/제약 제거
ALTER TABLE dataset DROP CONSTRAINT IF EXISTS dataset_dataset_type_check;
ALTER TABLE dataset DROP COLUMN dataset_type;
