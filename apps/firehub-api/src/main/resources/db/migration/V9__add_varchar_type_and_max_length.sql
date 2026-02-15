-- dataset_column.data_type CHECK 제약조건에 VARCHAR 추가
ALTER TABLE dataset_column DROP CONSTRAINT IF EXISTS dataset_column_data_type_check;
ALTER TABLE dataset_column ADD CONSTRAINT dataset_column_data_type_check
  CHECK (data_type IN ('TEXT','VARCHAR','INTEGER','DECIMAL','BOOLEAN','DATE','TIMESTAMP'));

-- max_length 컬럼 추가 (VARCHAR일 때만 사용)
ALTER TABLE dataset_column ADD COLUMN IF NOT EXISTS max_length INT;
