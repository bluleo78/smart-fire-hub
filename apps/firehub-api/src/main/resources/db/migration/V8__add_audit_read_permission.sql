-- Add audit:read permission
INSERT INTO permission (code, description, category) VALUES
    ('audit:read', '감사 로그 조회', 'audit');

-- Assign to ADMIN role
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.code = 'audit:read';
