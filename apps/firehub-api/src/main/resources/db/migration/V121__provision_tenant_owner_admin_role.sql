-- V121: 테넌트 소유자에게 그 테넌트의 ADMIN 역할을 실제로 배정한다 + 기존 테넌트 백필.
--
-- 무엇이 문제였나: V98/V100/V113 의 provision_tenant_defaults 는 role/role_permission 을
-- "복제"만 한다 — 즉 ADMIN 역할이 정의되기는 하지만 **아무에게도 배정되지 않는다**.
-- 테넌트 생성 경로(PlatformTenantService.create)도 membership(role='OWNER') 만 넣는데, 그 role 은
-- 표시용 라벨이라 인가에 쓰이지 않는다(MembershipResponse javadoc). 결과적으로 워크스페이스
-- 소유자조차 user_role 이 0행이라 useAuth().isAdmin=false 가 되어 관리 메뉴(사용자·역할·감사
-- 로그·설정)가 통째로 사라지고, 역할을 배정할 화면(/admin/roles)도 ADMIN 전용이라 자력 복구가
-- 불가능했다.
--
-- 왜 provision_tenant_defaults 안에서 하는가: 테넌트 생성의 정본은 아직 운영자 SQL 절차이고
-- (런북 §1), 그 절차가 부르는 것은 이 함수 하나뿐이다. 배정을 앱 코드에만 배선하면 운영자가 만든
-- 테넌트는 계속 권한 0개로 태어난다 — 정의와 배정은 한 호출로 끝나야 한다.
--
-- 왜 별도 함수를 만들지 않았는가: 배정을 `provision_tenant_owner_admin(tenant, user)` 같은
-- SECURITY DEFINER 프리미티브로 빼고 app_tenant 에 EXECUTE 를 주는 형태를 먼저 만들었다가 접었다.
-- (1) 내부 호출은 정의자 권한으로 돌아 EXECUTE 가 애초에 필요 없고, (2) 인자로 user_id 를 받는
-- 변경 definer 는 쿼리 UI·파이프라인 SQL 이 공유하는 app_tenant 롤에 새 권한 상승면을 여는 것이며,
-- (3) 그 방어로 넣었던 "OWNER 멤버십 확인"은 membership 이 RLS 없는 전역 테이블이고 app_tenant 가
-- 거기에 INSERT 권한을 갖는 이상(V83) 공격자가 스스로 쓸 수 있는 전제라 방어가 되지 못했다.
-- 지키지 못할 가드를 세우느니 표면 자체를 만들지 않는다.
--
-- 순서 의존: 이 함수는 이제 membership 을 읽는다. 멤버십이 아직 없으면 0행이라 아무 일도 일어나지
-- 않으므로, 런북·앱 모두 멤버십을 먼저 넣는다(런북 §1-2 에 명시).

-- ── (1) 프로비저닝 말미에 소유자 배정 추가 ────────────────────────────────────────
-- V113 본문을 그대로 유지하고 마지막 블록만 덧붙인다(CREATE OR REPLACE 는 전체 본문이 필요하다).
-- V98/V100/V113 을 편집하지 않는 이유도 같다 — 이미 적용된 DB 가 checksum mismatch 로 부팅 불가가
-- 된다.
CREATE OR REPLACE FUNCTION provision_tenant_defaults(p_tenant_id bigint)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_source_tenant CONSTANT bigint := 1;  -- 기본 테넌트가 시스템 역할·내장 양식의 원본이다
  -- 기본 카테고리는 이름 화이트리스트로 복제한다. dataset_category 에는 is_system 같은 표식이
  -- 없고, 테넌트 1 에는 테스트가 만든 임시 카테고리가 섞여 있다 — "테넌트 1 의 전부"를 복제하면
  -- 신규 테넌트가 남의 테스트 쓰레기를 물려받는다.
  v_seed_categories CONSTANT text[] := ARRAY['행정', '운영', '통계'];
BEGIN
  IF p_tenant_id = v_source_tenant THEN
    RETURN;  -- 원본 테넌트 자신은 시드 대상이 아니다
  END IF;

  -- 시스템 역할 복제. 이미 있으면(재실행) 건너뛴다 — 멱등해야 운영자가 안심하고 다시 부를 수 있다.
  INSERT INTO role (tenant_id, name, description, is_system)
  SELECT p_tenant_id, r.name, r.description, r.is_system
  FROM role r
  WHERE r.tenant_id = v_source_tenant
    AND r.is_system = true
    AND NOT EXISTS (
      SELECT 1 FROM role x WHERE x.tenant_id = p_tenant_id AND x.name = r.name);

  -- 역할-권한 매핑 복제. permission 은 전역 카탈로그라 id 를 그대로 쓴다.
  -- 역할은 이름으로 매칭한다(id 는 테넌트마다 다르다).
  INSERT INTO role_permission (tenant_id, role_id, permission_id)
  SELECT p_tenant_id, tgt.id, srp.permission_id
  FROM role_permission srp
  JOIN role src ON src.id = srp.role_id AND src.tenant_id = v_source_tenant AND src.is_system = true
  JOIN role tgt ON tgt.tenant_id = p_tenant_id AND tgt.name = src.name
  WHERE NOT EXISTS (
    SELECT 1 FROM role_permission x
    WHERE x.role_id = tgt.id AND x.permission_id = srp.permission_id);

  -- 내장 리포트 양식 복제. 내장 여부는 컬럼이 아니라 user_id IS NULL 로 표현된다
  -- (ReportTemplateRepository.toResponse 가 builtin = (userId == null) 로 계산한다).
  INSERT INTO report_template (tenant_id, user_id, name, description, sections, style, created_at, updated_at)
  SELECT p_tenant_id, NULL, t.name, t.description, t.sections, t.style, now(), now()
  FROM report_template t
  WHERE t.tenant_id = v_source_tenant
    AND t.user_id IS NULL
    AND NOT EXISTS (
      SELECT 1 FROM report_template x
      WHERE x.tenant_id = p_tenant_id AND x.user_id IS NULL AND x.name = t.name);

  -- 기본 데이터셋 카테고리 복제(V113 추가). 원본을 참조하지 않고 이름 목록에서 직접 만든다 —
  -- 테넌트 1 의 카테고리가 사람 손으로 바뀌어 있어도 신규 테넌트는 항상 같은 3건을 받는다.
  INSERT INTO dataset_category (tenant_id, name, created_at, updated_at)
  SELECT p_tenant_id, c.name, now(), now()
  FROM unnest(v_seed_categories) AS c(name)
  WHERE NOT EXISTS (
    SELECT 1 FROM dataset_category x
    WHERE x.tenant_id = p_tenant_id AND x.name = c.name);

  -- 소유자에게 ADMIN 배정(V121 추가). 역할을 "정의" 만 하고 끝내면 소유자도 권한이 0개다.
  -- PK(user_id, role_id) 충돌은 무시 — 운영자가 몇 번을 불러도 안전해야 한다.
  INSERT INTO user_role (user_id, role_id, tenant_id)
  SELECT m.user_id, r.id, p_tenant_id
  FROM membership m
  JOIN role r ON r.tenant_id = p_tenant_id AND r.name = 'ADMIN'
  WHERE m.tenant_id = p_tenant_id
    AND m.role = 'OWNER'
  ON CONFLICT (user_id, role_id) DO NOTHING;
END;
$$;

-- CREATE OR REPLACE 는 기존 권한을 보존하지만, 이 함수의 안전성이 권한 설정에 달려 있으므로
-- 명시적으로 다시 건다(V113 의 선례 — 이 파일만 읽어도 계약이 드러나야 한다).
REVOKE EXECUTE ON FUNCTION provision_tenant_defaults(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION provision_tenant_defaults(bigint) TO app_tenant;

-- ── (2) 기존 테넌트 백필 ────────────────────────────────────────────────────────
-- 위 블록과 같은 문장에서 테넌트 필터만 뺐다. 이미 ADMIN 이 붙은 소유자는 ON CONFLICT 로
-- 걸러지므로 조건을 더 두지 않는다("user_role 이 0행인 소유자만" 같은 필터를 두면 다른 경로로
-- USER 역할만 받은 소유자를 놓친다).
--
-- provision_tenant_defaults 를 테넌트마다 부르지 않는 이유: 그건 기존 테넌트에 역할·양식·카테고리
-- 재시드까지 돌리는 것이라 이 마이그레이션의 범위를 넘는다. 여기서 고치는 것은 배정 하나뿐이다.
--
-- Flyway 는 소유자(app)로 접속하고 user_role 은 FORCE ROW LEVEL SECURITY 가 아니므로(V99),
-- 이 평문 INSERT 가 전 테넌트 행에 닿는다 — V113 의 카테고리 백필과 같은 전제다.
INSERT INTO user_role (user_id, role_id, tenant_id)
SELECT m.user_id, r.id, m.tenant_id
FROM membership m
JOIN role r ON r.tenant_id = m.tenant_id AND r.name = 'ADMIN'
WHERE m.role = 'OWNER'
ON CONFLICT (user_id, role_id) DO NOTHING;
