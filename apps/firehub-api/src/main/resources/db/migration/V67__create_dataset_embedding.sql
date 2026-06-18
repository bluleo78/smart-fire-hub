-- 데이터셋 카탈로그 시맨틱+키워드 검색용 인덱스 테이블 (dataset와 1:1)
-- source_text 하나로 키워드(pg_trgm)와 의미(벡터) 검색을 모두 수행 (document_chunk 패턴 복제)
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS dataset_embedding (
    dataset_id      BIGINT PRIMARY KEY REFERENCES dataset(id) ON DELETE CASCADE,
    source_text     TEXT NOT NULL,
    embedding       vector(1024),
    embedding_model VARCHAR(100),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dataset_embedding_vector
    ON dataset_embedding USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_dataset_embedding_source_trgm
    ON dataset_embedding USING gin (source_text gin_trgm_ops);
