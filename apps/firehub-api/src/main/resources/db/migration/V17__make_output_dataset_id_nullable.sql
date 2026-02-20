-- output_dataset_id를 선택사항으로 변경 (메타데이터 용도)
ALTER TABLE pipeline_step ALTER COLUMN output_dataset_id DROP NOT NULL;
