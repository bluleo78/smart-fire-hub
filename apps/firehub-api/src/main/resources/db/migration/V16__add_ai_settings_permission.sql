-- AI 설정 관리 권한 추가
INSERT INTO permission (code, description, category) VALUES
('ai:settings', 'AI 에이전트 설정 변경', 'ai');

-- ADMIN 역할에 자동 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.code = 'ai:settings';
