-- 알림 발송 작업 큐. 도메인 트랜잭션 후 AFTER_COMMIT 훅으로 INSERT,
-- NotificationDispatchWorker가 SKIP LOCKED + lease 패턴으로 발송.
CREATE TABLE notification_outbox (
    id BIGSERIAL PRIMARY KEY,

    idempotency_key VARCHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_source_id BIGINT,

    channel_type VARCHAR(32) NOT NULL,
    recipient_user_id BIGINT,
    recipient_address TEXT,

    payload_ref_type VARCHAR(32),
    payload_ref_id BIGINT,
    payload JSONB,
    rendered_subject TEXT,
    payload_type VARCHAR(16) NOT NULL DEFAULT 'STANDARD',

    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ,
    claimed_by VARCHAR(64),
    sent_at TIMESTAMPTZ,
    last_error TEXT,
    last_error_at TIMESTAMPTZ,
    permanent_failure_reason VARCHAR(64),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by_user_id BIGINT,

    CONSTRAINT uk_outbox_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','SENDING','SENT','PERMANENT_FAILURE','CANCELLED')),
    CONSTRAINT chk_outbox_payload_type CHECK (payload_type IN ('STANDARD','OVERRIDE'))
);

-- 워커 폴링 — pending이 due 상태인 행을 빠르게 찾음
CREATE INDEX idx_outbox_pending_due
    ON notification_outbox (next_attempt_at)
    WHERE status = 'PENDING';

-- 좀비 회복 — SENDING 상태로 5분 이상 묶인 행
CREATE INDEX idx_outbox_zombie
    ON notification_outbox (claimed_at)
    WHERE status = 'SENDING';

-- 사용자 알림 인박스 조회
CREATE INDEX idx_outbox_recipient
    ON notification_outbox (recipient_user_id, created_at DESC);

-- correlation 묶음 조회
CREATE INDEX idx_outbox_correlation
    ON notification_outbox (correlation_id);
