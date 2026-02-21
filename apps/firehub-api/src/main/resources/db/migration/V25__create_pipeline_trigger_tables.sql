-- 트리거 정의 테이블
CREATE TABLE pipeline_trigger (
    id              BIGSERIAL PRIMARY KEY,
    pipeline_id     BIGINT NOT NULL REFERENCES pipeline(id) ON DELETE CASCADE,
    trigger_type    VARCHAR(30) NOT NULL
                    CHECK (trigger_type IN ('SCHEDULE','API','PIPELINE_CHAIN','WEBHOOK','DATASET_CHANGE')),
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    is_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    config          JSONB NOT NULL DEFAULT '{}',
    trigger_state   JSONB NOT NULL DEFAULT '{}',
    created_by      BIGINT NOT NULL REFERENCES "user"(id),
    updated_by      BIGINT REFERENCES "user"(id),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_trigger_pipeline ON pipeline_trigger(pipeline_id);
CREATE INDEX idx_trigger_enabled_type ON pipeline_trigger(trigger_type) WHERE is_enabled = TRUE;

-- 트리거 이벤트 로그
CREATE TABLE trigger_event (
    id              BIGSERIAL PRIMARY KEY,
    trigger_id      BIGINT NOT NULL REFERENCES pipeline_trigger(id) ON DELETE CASCADE,
    pipeline_id     BIGINT NOT NULL REFERENCES pipeline(id) ON DELETE CASCADE,
    execution_id    BIGINT REFERENCES pipeline_execution(id),
    event_type      VARCHAR(30) NOT NULL,
    detail          JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_trigger_event_trigger_time ON trigger_event(trigger_id, created_at DESC);
CREATE INDEX idx_trigger_event_pipeline ON trigger_event(pipeline_id);

-- pipeline_execution에 트리거 출처 추가
ALTER TABLE pipeline_execution
    ADD COLUMN triggered_by VARCHAR(30) DEFAULT 'MANUAL',
    ADD COLUMN trigger_id BIGINT REFERENCES pipeline_trigger(id);

-- 트리거 관리 권한
INSERT INTO permission (code, description, category) VALUES
    ('trigger:read', '트리거 조회', 'trigger'),
    ('trigger:write', '트리거 생성/수정', 'trigger'),
    ('trigger:delete', '트리거 삭제', 'trigger');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.category = 'trigger';
