CREATE TABLE query_history (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES "user"(id),
    sql_text TEXT NOT NULL,
    query_type VARCHAR(20) NOT NULL,
    affected_rows INT DEFAULT 0,
    execution_time_ms BIGINT DEFAULT 0,
    success BOOLEAN NOT NULL DEFAULT true,
    error_message TEXT,
    executed_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_query_history_dataset ON query_history(dataset_id, executed_at DESC);
CREATE INDEX idx_query_history_user ON query_history(user_id, executed_at DESC);
