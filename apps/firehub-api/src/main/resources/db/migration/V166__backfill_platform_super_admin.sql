-- V166: 운영자(platform_user_role)가 하나도 없는 기존 설치본에 플랫폼 SUPER_ADMIN 한 명을 정해 준다 (WD-757).
--
-- 왜 필요한가: SUPER_ADMIN 은 V82 가 그때 있던 전역 ADMIN 을 한 번 승격해 생겼고, 새 설치본은 WD-748(첫 가입자에게 SUPER_ADMIN)·
-- WD-749(사용자 0명일 때 첫 운영자 만들기)로 생긴다. 그런데 V82 가 빈 DB 에서 돈 뒤 사용자가 생긴 설치본은 공개 가입이 닫혀 있어 두 경로에
-- 닿지 않고, 운영자 콘솔에 들어갈 계정이 없다. 이 보정은 그런 설치본에만 한 명을 올린다.
--
-- 조건: platform_user_role 에 행이 하나도 없을 때만. 운영자가 한 명이라도 있으면 아무것도 하지 않는다(다시 돌려도 결과가 같다).
-- 대상: 기본 테넌트(id=1, MembershipRepository.DEFAULT_TENANT_ID)에서 테넌트 역할 ADMIN(user_role → role.name='ADMIN', 같은
--       tenant_id)을 가진 사용자 중, 계정이 활성("user".is_active)이고 그 테넌트 멤버십이 활성(membership.status='ACTIVE')인 사람
--       가운데 가장 먼저 만든 한 명("user".created_at, 같으면 id 가 작은 쪽). 비활성·정지 계정은 콘솔에 들어갈 수 없어 뺀다.
-- 한 명만: V82 처럼 ADMIN 전원을 올리지 않는다 — 이 설치본의 ADMIN 은 테넌트 관리자 의미라 플랫폼 권한은 최소로 준다. 다른 운영자는
--          콘솔에서 추가한다.
-- 대상이 없거나 SUPER_ADMIN 시드가 없으면 SELECT 가 0행이라 아무것도 넣지 않는다(실패하지 않음).
--
-- RLS: Flyway 는 소유자(app)로 접속한다. user_role 은 ENABLE 만 하고 FORCE 하지 않으므로(V99) 소유자에게 정책이 적용되지 않아 모든
-- 테넌트 행이 보인다. membership·"user"·role 의 테넌트 필터는 여기서 명시한다. membership·platform_* 은 RLS 가 없다(V81·V82).
-- 감사 로그는 남기지 않는다(V121·V158 과 같은 데이터 보정).
-- Tibero 짝: db/migration-tibero/V166 (MigrationParityTest).
INSERT INTO platform_user_role (user_id, platform_role_id)
SELECT first_admin.user_id, pr.id
FROM (
    SELECT u.id AS user_id
    FROM "user" u
    JOIN user_role ur ON ur.user_id = u.id AND ur.tenant_id = 1
    JOIN role r ON r.id = ur.role_id AND r.tenant_id = 1 AND r.name = 'ADMIN'
    JOIN membership m ON m.user_id = u.id AND m.tenant_id = 1 AND m.status = 'ACTIVE'
    WHERE u.is_active = TRUE
    ORDER BY u.created_at, u.id
    LIMIT 1
) first_admin
JOIN platform_role pr ON pr.name = 'SUPER_ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM platform_user_role);
