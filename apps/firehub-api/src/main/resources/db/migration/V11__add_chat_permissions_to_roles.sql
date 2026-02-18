-- ADMIN에게 모든 채팅 권한 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.category = 'chat';

-- USER에게 채팅 권한 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'USER' AND p.category = 'chat';
