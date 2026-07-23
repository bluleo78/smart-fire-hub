-- ontology:write 권한 추가 — 지식 모델(온톨로지) 편집(B-2b 슬라이스 5-1).
-- 읽기(dataset:read, USER 포함)와 분리된 특권. 편집=추출 config 변경이므로 ADMIN에게만 부여.
INSERT INTO permission (code, description, category)
VALUES ('ontology:write', '지식 모델(온톨로지) 편집', 'ontology')
ON CONFLICT (code) DO NOTHING;

-- ADMIN 역할에 부여(멱등: role_permission 복합 PK에 ON CONFLICT DO NOTHING).
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'ADMIN' AND p.code = 'ontology:write'
ON CONFLICT DO NOTHING;
