-- ADMIN 역할에 pipeline:python_execute 권한 부여
-- nsjail 샌드박스가 이미 적용되어 보안 격리 완료됨 (Phase 5.7)
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.code = 'pipeline:python_execute'
ON CONFLICT DO NOTHING;

-- 권한 설명 업데이트: nsjail 적용 완료 반영
UPDATE permission
SET description = '파이프라인 Python 스크립트 실행 (nsjail 샌드박스 격리)'
WHERE code = 'pipeline:python_execute';
