-- V166: 운영자(platform_user_role)가 하나도 없는 기존 설치본에 플랫폼 SUPER_ADMIN 한 명을 정해 준다 (WD-757) — PG V166 과 짝
-- (MigrationParityTest).
--
-- 대상·이유는 PG V166 과 같다. Tibero 설치본은 V155/V155.1 기준선으로 시작해 platform_user_role 이 비어 있고(시드는 platform_role
-- SUPER_ADMIN 만 넣는다), 공개 가입이 닫힌 뒤에는 첫 가입(WD-748)·첫 운영자 만들기(WD-749)에 닿지 않는다.
--
-- PG 와 다른 표기만:
--   - 사용자 테이블은 따옴표 대문자 "USER"(V155), is_active 는 NUMBER(1)(1=활성).
--   - LIMIT 대신 정렬한 인라인 뷰 + ROWNUM = 1(V156.1 의 v_top_level 조회와 같은 관용구). ROWNUM 은 정렬된 뷰 밖에서 매겨지므로
--     created_at, id 순 첫 행이다.
-- 조건(platform_user_role 이 비었을 때만)·대상 없음·SUPER_ADMIN 시드 없음에서 아무것도 넣지 않는 것도 같다. DML 이라 실패하면 롤백되고,
-- 다시 돌려도 결과가 같다(멱등).
--
-- RLS: user_role 에는 V156.1 의 USER_ROLE_TENANT_ISOLATION 정책이 있지만, 정책 함수 FH_TENANT_POLICY 는 소유자 세션
-- (SESSION_USER = 소유자 스키마 — Flyway 가 소유자로 돈다)에는 NULL(걸러내지 않음)을 돌려주므로 모든 행이 보인다. membership·"USER"·
-- role(정책 없음 또는 소유자 우회)·platform_* 도 마찬가지다. 테넌트 필터는 여기서 명시한다.
INSERT INTO platform_user_role (user_id, platform_role_id)
SELECT first_admin.user_id, pr.id
FROM (
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
) first_admin
JOIN platform_role pr ON pr.name = 'SUPER_ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM platform_user_role);
