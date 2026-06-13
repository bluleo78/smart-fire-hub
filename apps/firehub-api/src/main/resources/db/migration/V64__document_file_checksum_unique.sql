-- 데이터셋 내 동일 내용 문서 중복 방지(원자적 dedup; 앱 레벨 existsByChecksum의 TOCTOU 경합 차단)
CREATE UNIQUE INDEX IF NOT EXISTS uq_document_file_dataset_checksum
    ON document_file(dataset_id, checksum);
