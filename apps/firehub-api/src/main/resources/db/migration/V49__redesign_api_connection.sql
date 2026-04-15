-- V49__redesign_api_connection.sql
-- Phase 9: API 연결 리디자인 — Base URL 필수화 및 헬스체크 상태 컬럼 추가
-- 기존 레코드가 없는 전제로 base_url NOT NULL 즉시 적용

ALTER TABLE api_connection
  ADD COLUMN base_url VARCHAR(500) NOT NULL DEFAULT '',
  ADD COLUMN health_check_path VARCHAR(500),
  ADD COLUMN last_status VARCHAR(16),
  ADD COLUMN last_checked_at TIMESTAMP,
  ADD COLUMN last_latency_ms INT,
  ADD COLUMN last_error_message VARCHAR(1000);

-- base_url DEFAULT '' 제거: 신규 INSERT는 반드시 값 제공 필요
ALTER TABLE api_connection ALTER COLUMN base_url DROP DEFAULT;

CREATE INDEX idx_api_connection_last_status ON api_connection(last_status);

-- API 연결 권한 seed: 기존 apiconnection:* 패턴과 동일하게 추가
-- (이미 존재하는 경우 ON CONFLICT DO NOTHING으로 멱등성 보장)
INSERT INTO permission(code, description, category) VALUES
  ('apiconnection:read',   'API 연결 조회',              'apiconnection'),
  ('apiconnection:write',  'API 연결 생성/수정/삭제/테스트', 'apiconnection'),
  ('apiconnection:delete', 'API 연결 삭제',              'apiconnection')
ON CONFLICT (code) DO NOTHING;

-- ADMIN 역할에 api_connection 권한 부여
-- role 테이블에 code 컬럼이 없으므로 name 컬럼으로 조회
INSERT INTO role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'ADMIN'
  AND p.code IN ('apiconnection:read', 'apiconnection:write', 'apiconnection:delete')
ON CONFLICT DO NOTHING;
