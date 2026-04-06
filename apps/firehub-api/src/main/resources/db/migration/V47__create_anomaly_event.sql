-- V47__create_anomaly_event.sql
-- 이상 탐지 이벤트 이력 저장 테이블
-- proactive_job과 연결하여 각 작업에서 탐지된 이상 이벤트를 영구 보관한다
CREATE TABLE IF NOT EXISTS anomaly_event (
    id              BIGSERIAL        PRIMARY KEY,
    job_id          BIGINT           NOT NULL REFERENCES proactive_job(id) ON DELETE CASCADE,
    metric_id       VARCHAR(100)     NOT NULL,
    metric_name     VARCHAR(200)     NOT NULL,
    current_value   DOUBLE PRECISION NOT NULL,
    mean            DOUBLE PRECISION NOT NULL,
    stddev          DOUBLE PRECISION NOT NULL,
    deviation       DOUBLE PRECISION NOT NULL,  -- (current_value - mean) / stddev (표준편차 배수)
    sensitivity     VARCHAR(20)      NOT NULL,   -- LOW / MEDIUM / HIGH
    detected_at     TIMESTAMP        NOT NULL DEFAULT NOW()
);

-- job_id + detected_at 복합 인덱스: 특정 작업의 최근 이벤트 조회 성능 최적화
CREATE INDEX IF NOT EXISTS idx_anomaly_event_job_detected
    ON anomaly_event (job_id, detected_at DESC);
