CREATE TABLE async_job (
    id              VARCHAR(36)  PRIMARY KEY,
    job_type        VARCHAR(50)  NOT NULL,
    resource        VARCHAR(50)  NOT NULL,
    resource_id     VARCHAR(100) NOT NULL,
    user_id         BIGINT       NOT NULL REFERENCES "user"(id),
    stage           VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    progress        INTEGER      NOT NULL DEFAULT 0,
    message         TEXT,
    metadata        JSONB,
    error_message   TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_async_job_resource ON async_job(job_type, resource, resource_id);
CREATE INDEX idx_async_job_active ON async_job(job_type, resource, resource_id)
    WHERE stage NOT IN ('COMPLETED', 'FAILED');
CREATE INDEX idx_async_job_stale ON async_job(updated_at)
    WHERE stage NOT IN ('COMPLETED', 'FAILED');
CREATE INDEX idx_async_job_user ON async_job(user_id, job_type);
