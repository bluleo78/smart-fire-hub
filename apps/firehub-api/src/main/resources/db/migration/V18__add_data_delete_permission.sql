-- data:delete 권한 추가
INSERT INTO permission (code, description, category) VALUES
    ('data:delete', '데이터셋 행 삭제', 'data');

-- ADMIN 역할에 자동 부여
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.code = 'data:delete';
