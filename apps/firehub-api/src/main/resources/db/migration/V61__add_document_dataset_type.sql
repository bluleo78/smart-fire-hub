-- dataset_type CHECK 제약에 DOCUMENT(비정형 문서 데이터셋) 추가
ALTER TABLE dataset DROP CONSTRAINT IF EXISTS dataset_dataset_type_check;
ALTER TABLE dataset ADD CONSTRAINT dataset_dataset_type_check
    CHECK (dataset_type IN ('SOURCE', 'DERIVED', 'TEMP', 'DOCUMENT'));
