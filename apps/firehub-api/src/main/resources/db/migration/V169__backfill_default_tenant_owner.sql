-- V169: OWNER 멤버가 하나도 없는 기본 워크스페이스(테넌트 1)에 OWNER 한 명을 정해 준다 (WD-1100).
--
-- 왜 필요한가: 새 설치본은 첫 가입자가 기본 워크스페이스 OWNER 가 되도록 고쳤지만(WD-1100), 이미 사용자가 있는데 OWNER 가 없는
-- 기존 설치본은 그대로 남는다. V81 은 그때 있던 전역 ADMIN 을 OWNER 로 올렸을 뿐이라 이후 생긴 설치본은 OWNER 가 없다. 이 보정은 그런
-- 설치본에만 한 명을 올린다.
--
-- 조건: 테넌트 1 에 membership.role='OWNER' 행이 하나도 없을 때만. OWNER 가 한 명이라도 있으면(상태 무관) 아무것도 하지 않는다
--       (다시 돌려도 결과가 같다).
-- 대상: V166 과 같다 — 테넌트 1 에서 테넌트 역할 ADMIN(user_role → role.name='ADMIN', 같은 tenant_id)을 가진 사용자 중 계정이 활성
--       ("user".is_active)이고 멤버십이 활성(membership.status='ACTIVE')인 사람 가운데 가장 먼저 만든 한 명("user".created_at, 같으면
--       id 가 작은 쪽). 조건에 맞는 ADMIN 이 없으면 서브쿼리가 NULL 이라 0행을 바꾼다(실패하지 않음).
-- 한 명만: ADMIN 전원을 올리지 않는다. 다른 소유자는 필요할 때 추가한다.
--
-- RLS: Flyway 는 소유자(app)로 접속한다. user_role 은 ENABLE 만 하고 FORCE 하지 않으므로(V99) 소유자에게 정책이 적용되지 않아 모든
-- 테넌트 행이 보인다. membership 은 RLS 가 없다(V81). 테넌트 필터는 여기서 명시한다. membership.role 은 표시용 라벨이라(V81) 인가에는
-- 영향이 없다. 감사 로그는 남기지 않는다(V121·V158·V166 과 같은 데이터 보정).
-- Tibero 짝: db/migration-tibero/V169 (MigrationParityTest).
UPDATE membership
SET role = 'OWNER'
WHERE tenant_id = 1
  AND user_id = (
    SELECT u.id
    FROM "user" u
    JOIN user_role ur ON ur.user_id = u.id AND ur.tenant_id = 1
    JOIN role r ON r.id = ur.role_id AND r.tenant_id = 1 AND r.name = 'ADMIN'
    JOIN membership m ON m.user_id = u.id AND m.tenant_id = 1 AND m.status = 'ACTIVE'
    WHERE u.is_active = TRUE
    ORDER BY u.created_at, u.id
    LIMIT 1
  )
  AND NOT EXISTS (SELECT 1 FROM membership WHERE tenant_id = 1 AND role = 'OWNER');
