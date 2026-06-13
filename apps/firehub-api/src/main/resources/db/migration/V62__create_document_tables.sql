-- 문서 원본 메타 (영구 저장 — uploaded_files 와 달리 만료 없음)
CREATE TABLE IF NOT EXISTS document_file (
    id              BIGSERIAL PRIMARY KEY,
    dataset_id      BIGINT NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    original_name   VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(100) NOT NULL,
    file_size       BIGINT NOT NULL,
    storage_path    VARCHAR(500) NOT NULL,
    checksum        VARCHAR(64),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','PARSING','EMBEDDING','COMPLETED','FAILED')),
    page_count      INT,
    chunk_count     INT,
    error_detail    TEXT,
    uploaded_by     BIGINT NOT NULL REFERENCES "user"(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_document_file_dataset ON document_file(dataset_id);
CREATE INDEX IF NOT EXISTS idx_document_file_status ON document_file(status);

-- 문서 청크 + 임베딩 벡터 (기본 차원 bge-m3 = 1024)
CREATE TABLE IF NOT EXISTS document_chunk (
    id                BIGSERIAL PRIMARY KEY,
    document_file_id  BIGINT NOT NULL REFERENCES document_file(id) ON DELETE CASCADE,
    -- 쿼리 성능을 위한 비정규화 컬럼 (document_file.dataset_id 와 동일; 삭제는 CASCADE로 처리)
    dataset_id        BIGINT NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    chunk_index       INT NOT NULL,
    content           TEXT NOT NULL,
    token_count       INT,
    embedding         vector(1024),
    embedding_model   VARCHAR(100),
    metadata          JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_document_chunk_dataset ON document_chunk(dataset_id);
CREATE INDEX IF NOT EXISTS idx_document_chunk_file ON document_chunk(document_file_id);
-- HNSW 코사인 인덱스 (pgvector >= 0.5.0)
CREATE INDEX IF NOT EXISTS idx_document_chunk_embedding
    ON document_chunk USING hnsw (embedding vector_cosine_ops);
