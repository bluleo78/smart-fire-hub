-- proactive_job 테이블에 trigger_type 컬럼 추가
ALTER TABLE proactive_job ADD COLUMN IF NOT EXISTS trigger_type VARCHAR(20) DEFAULT 'SCHEDULE';

-- 메트릭 스냅샷 히스토리 테이블
CREATE TABLE IF NOT EXISTS metric_snapshot (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES proactive_job(id) ON DELETE CASCADE,
    metric_id VARCHAR(100) NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    collected_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_metric_snapshot_job_metric
    ON metric_snapshot(job_id, metric_id, collected_at DESC);
