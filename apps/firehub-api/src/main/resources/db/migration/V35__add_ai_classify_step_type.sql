-- V35__add_ai_classify_step_type.sql

-- 1. script_type 컬럼 폭 확장 (AI_CLASSIFY = 11자, 기존 VARCHAR(10))
ALTER TABLE pipeline_step ALTER COLUMN script_type TYPE VARCHAR(20);

-- 2. 기존 CHECK 제약조건 교체
ALTER TABLE pipeline_step DROP CONSTRAINT IF EXISTS pipeline_step_script_type_check;
ALTER TABLE pipeline_step ADD CONSTRAINT pipeline_step_script_type_check
    CHECK (script_type IN ('SQL', 'PYTHON', 'API_CALL', 'AI_CLASSIFY'));

-- 3. ai_config JSONB 컬럼 추가
ALTER TABLE pipeline_step ADD COLUMN IF NOT EXISTS ai_config JSONB;

-- 4. inference cache 테이블
CREATE TABLE IF NOT EXISTS ai_inference_cache (
    id BIGSERIAL PRIMARY KEY,
    row_hash VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    label TEXT NOT NULL,
    confidence NUMERIC(5,4) NOT NULL,
    reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(row_hash, prompt_version)
);

CREATE INDEX IF NOT EXISTS idx_ai_inference_cache_lookup
    ON ai_inference_cache(row_hash, prompt_version);

-- 5. 권한 추가
INSERT INTO permission (code, description, category) VALUES
    ('pipeline:ai_execute', 'AI 분류 스텝 실행 권한', 'pipeline')
ON CONFLICT (code) DO NOTHING;

-- 6. ADMIN 역할에 권한 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.code = 'pipeline:ai_execute'
ON CONFLICT DO NOTHING;
