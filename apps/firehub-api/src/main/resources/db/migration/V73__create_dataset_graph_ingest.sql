-- GraphRAG 적재 이력. audit_log/async_job 패턴(BIGSERIAL, VARCHAR status, TIMESTAMP DEFAULT NOW(), JSONB).
-- schema_version_at_ingest 로 온톨로지 버전 드리프트를 추적한다(적재에 실제 쓰인 버전).
CREATE TABLE IF NOT EXISTS dataset_graph_ingest (
    id                        BIGSERIAL    PRIMARY KEY,
    dataset_id                BIGINT       NOT NULL,
    ingested_at               TIMESTAMP    NOT NULL DEFAULT NOW(),
    schema_version_at_ingest  INT          NOT NULL,
    chunk_count               INTEGER      NOT NULL DEFAULT 0,
    node_count                INTEGER      NOT NULL DEFAULT 0,
    edge_count                INTEGER      NOT NULL DEFAULT 0,
    extraction_failures       INTEGER      NOT NULL DEFAULT 0,
    status                    VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS',
    metadata                  JSONB
);
-- 데이터셋별 최신 이력(latest-per-dataset, stale 질의) 가속.
CREATE INDEX IF NOT EXISTS idx_dataset_graph_ingest_dataset_time
    ON dataset_graph_ingest (dataset_id, ingested_at DESC);
