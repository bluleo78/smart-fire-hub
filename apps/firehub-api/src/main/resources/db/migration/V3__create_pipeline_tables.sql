-- 파이프라인
CREATE TABLE pipeline (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_by  BIGINT NOT NULL REFERENCES "user"(id),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_pipeline_name ON pipeline(name);

-- 파이프라인 Step
CREATE TABLE pipeline_step (
    id                BIGSERIAL PRIMARY KEY,
    pipeline_id       BIGINT NOT NULL REFERENCES pipeline(id) ON DELETE CASCADE,
    name              VARCHAR(100) NOT NULL,
    description       TEXT,
    script_type       VARCHAR(10) NOT NULL CHECK (script_type IN ('SQL', 'PYTHON')),
    script_content    TEXT NOT NULL,
    output_dataset_id BIGINT NOT NULL REFERENCES dataset(id),
    step_order        INT NOT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (pipeline_id, name)
);
CREATE INDEX idx_pipeline_step_pipeline ON pipeline_step(pipeline_id);

-- Step 입력 데이터셋
CREATE TABLE pipeline_step_input (
    step_id    BIGINT NOT NULL REFERENCES pipeline_step(id) ON DELETE CASCADE,
    dataset_id BIGINT NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    PRIMARY KEY (step_id, dataset_id)
);

-- Step 의존성 (DAG 엣지)
CREATE TABLE pipeline_step_dependency (
    step_id            BIGINT NOT NULL REFERENCES pipeline_step(id) ON DELETE CASCADE,
    depends_on_step_id BIGINT NOT NULL REFERENCES pipeline_step(id) ON DELETE CASCADE,
    PRIMARY KEY (step_id, depends_on_step_id),
    CHECK (step_id != depends_on_step_id)
);

-- 파이프라인 실행
CREATE TABLE pipeline_execution (
    id           BIGSERIAL PRIMARY KEY,
    pipeline_id  BIGINT NOT NULL REFERENCES pipeline(id) ON DELETE CASCADE,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED')),
    executed_by  BIGINT NOT NULL REFERENCES "user"(id),
    started_at   TIMESTAMP,
    completed_at TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pipeline_exec_pipeline ON pipeline_execution(pipeline_id);

-- Step별 실행 상태
CREATE TABLE pipeline_step_execution (
    id              BIGSERIAL PRIMARY KEY,
    execution_id    BIGINT NOT NULL REFERENCES pipeline_execution(id) ON DELETE CASCADE,
    step_id         BIGINT NOT NULL REFERENCES pipeline_step(id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED','SKIPPED')),
    output_rows     INT,
    log             TEXT,
    error_message   TEXT,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP
);
CREATE INDEX idx_step_exec_execution ON pipeline_step_execution(execution_id);

-- 파이프라인 권한 시딩
INSERT INTO permission (code, description, category) VALUES
    ('pipeline:read', '파이프라인 조회', 'pipeline'),
    ('pipeline:write', '파이프라인 생성/수정', 'pipeline'),
    ('pipeline:execute', '파이프라인 실행', 'pipeline'),
    ('pipeline:delete', '파이프라인 삭제', 'pipeline');

-- ADMIN에게 파이프라인 권한 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.category = 'pipeline';
