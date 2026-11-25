-- V169: OWNER 멤버가 하나도 없는 기본 워크스페이스(테넌트 1)에 OWNER 한 명을 정해 준다 (WD-1100) — PG V169 과 짝
-- (MigrationParityTest).
--
-- 대상·조건·이유는 PG V169 과 같다(OWNER 행이 없을 때만, V166 의 first_admin 과 같은 사용자 한 명).
--
-- PG 와 다른 표기만:
--   - 사용자 테이블은 따옴표 대문자 "USER"(V155), is_active 는 NUMBER(1)(1=활성).
--   - LIMIT 대신 정렬한 인라인 뷰 + ROWNUM = 1(V166 Tibero 와 같은 관용구).
--   - UPDATE 대상 테이블을 같은 문장의 NOT EXISTS 서브쿼리에서 읽는 것을 피하려고, OWNER 개수를 인라인 뷰 한 겹으로 감싸 같은 뜻을 유지한다.
-- DML 이라 실패하면 롤백되고, 다시 돌려도 결과가 같다(멱등).
--
-- RLS: V166 Tibero 와 같다 — 소유자 세션에는 FH_TENANT_POLICY 가 NULL(걸러내지 않음)을 돌려주므로 모든 행이 보이고, 테넌트 필터는
-- 여기서 명시한다.
UPDATE membership
SET role = 'OWNER'
WHERE tenant_id = 1
  AND user_id = (
    SELECT user_id
    FROM (
        SELECT u.id AS user_id
        FROM "USER" u
        JOIN user_role ur ON ur.user_id = u.id AND ur.tenant_id = 1
        JOIN role r ON r.id = ur.role_id AND r.tenant_id = 1 AND r.name = 'ADMIN'
        JOIN membership m ON m.user_id = u.id AND m.tenant_id = 1 AND m.status = 'ACTIVE'
        WHERE u.is_active = 1
        ORDER BY u.created_at, u.id
    )
    WHERE ROWNUM = 1
  )
  AND NOT EXISTS (
    SELECT 1 FROM (SELECT user_id FROM membership WHERE tenant_id = 1 AND role = 'OWNER') o
  );
