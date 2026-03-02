-- Phase 1-0: PostGIS 인프라 활성화
CREATE EXTENSION IF NOT EXISTS postgis;

-- GEOMETRY 타입을 dataset_column 허용 목록에 추가
ALTER TABLE dataset_column
    DROP CONSTRAINT IF EXISTS dataset_column_data_type_check;

ALTER TABLE dataset_column
    ADD CONSTRAINT dataset_column_data_type_check
    CHECK (data_type IN ('TEXT','VARCHAR','INTEGER','DECIMAL','BOOLEAN','DATE','TIMESTAMP','GEOMETRY'));
