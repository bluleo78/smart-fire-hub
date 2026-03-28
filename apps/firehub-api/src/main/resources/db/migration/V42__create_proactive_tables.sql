-- Proactive AI 테이블 생성 마이그레이션

-- 1. 리포트 템플릿 테이블 (user_id nullable — 빌트인은 NULL)
CREATE TABLE IF NOT EXISTS report_template (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    sections JSONB NOT NULL DEFAULT '[]',
    user_id BIGINT REFERENCES "user"(id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 2. Proactive Job 테이블
CREATE TABLE IF NOT EXISTS proactive_job (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    template_id BIGINT REFERENCES report_template(id) ON DELETE SET NULL,
    name VARCHAR(200) NOT NULL,
    prompt TEXT NOT NULL DEFAULT '',
    timezone VARCHAR(100) NOT NULL DEFAULT 'Asia/Seoul',
    config JSONB NOT NULL DEFAULT '{}',
    cron_expression VARCHAR(100),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_executed_at TIMESTAMP,
    next_execute_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 3. Proactive Job Execution 테이블
CREATE TABLE IF NOT EXISTS proactive_job_execution (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES proactive_job(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    error_message TEXT,
    result JSONB,
    created_at TIMESTAMP DEFAULT NOW()
);

-- 4. Proactive Message 테이블
CREATE TABLE IF NOT EXISTS proactive_message (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    execution_id BIGINT REFERENCES proactive_job_execution(id) ON DELETE CASCADE,
    title VARCHAR(500) NOT NULL,
    content JSONB NOT NULL DEFAULT '{}',
    message_type VARCHAR(100) NOT NULL DEFAULT 'REPORT',
    read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

-- 인덱스 3개
CREATE INDEX IF NOT EXISTS idx_proactive_execution_job
    ON proactive_job_execution(job_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_proactive_message_user_unread
    ON proactive_message(user_id, read)
    WHERE read = FALSE;

CREATE INDEX IF NOT EXISTS idx_proactive_message_user
    ON proactive_message(user_id, created_at DESC);

-- 빌트인 리포트 템플릿 3종 시드
INSERT INTO report_template (name, description, sections, user_id) VALUES
(
    '일간 요약 리포트',
    '매일 실행 결과와 데이터 현황을 요약합니다.',
    '[
        {"key": "summary", "label": "요약", "required": true, "type": "text"},
        {"key": "stats", "label": "통계", "required": false, "type": "cards"},
        {"key": "details", "label": "상세 내역", "required": false, "type": "list"},
        {"key": "attention", "label": "주의 항목", "required": false, "type": "list"},
        {"key": "recommendation", "label": "권장 사항", "required": false, "type": "text"}
    ]'::jsonb,
    NULL
),
(
    '실패 분석 리포트',
    '실패한 작업에 대한 원인 분석과 영향도를 보고합니다.',
    '[
        {"key": "overview", "label": "개요", "required": true, "type": "text"},
        {"key": "failures", "label": "실패 목록", "required": false, "type": "list"},
        {"key": "analysis", "label": "원인 분석", "required": false, "type": "text"},
        {"key": "impact", "label": "영향도", "required": false, "type": "text"},
        {"key": "resolution", "label": "해결 방안", "required": false, "type": "text"}
    ]'::jsonb,
    NULL
),
(
    '주간 트렌드 리포트',
    '주간 데이터 트렌드와 변화 추이를 분석합니다.',
    '[
        {"key": "summary", "label": "주간 요약", "required": true, "type": "text"},
        {"key": "comparison", "label": "전주 비교", "required": false, "type": "cards"},
        {"key": "trends", "label": "트렌드", "required": false, "type": "list"},
        {"key": "highlights", "label": "주요 이슈", "required": false, "type": "list"},
        {"key": "outlook", "label": "전망", "required": false, "type": "text"}
    ]'::jsonb,
    NULL
);

-- SMTP 설정 시드 6개
INSERT INTO system_settings (key, value, description) VALUES
('smtp.host', '', 'SMTP 서버 호스트'),
('smtp.port', '587', 'SMTP 서버 포트'),
('smtp.username', '', 'SMTP 인증 사용자명'),
('smtp.password', '', 'SMTP 인증 비밀번호'),
('smtp.starttls', 'true', 'SMTP STARTTLS 사용 여부'),
('smtp.from_address', '', '발신자 이메일 주소')
ON CONFLICT (key) DO NOTHING;

-- 권한 3개 추가
INSERT INTO permission (code, description, category) VALUES
('proactive:read', 'Proactive AI 리포트 조회', 'proactive'),
('proactive:write', 'Proactive AI 작업 관리', 'proactive'),
('settings:write', '시스템 설정 변경', 'settings')
ON CONFLICT (code) DO NOTHING;

-- ADMIN 역할에 3개 권한 모두 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'ADMIN'
  AND p.code IN ('proactive:read', 'proactive:write', 'settings:write')
ON CONFLICT DO NOTHING;

-- USER 역할에 proactive:read 권한 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'USER'
  AND p.code = 'proactive:read'
ON CONFLICT DO NOTHING;
