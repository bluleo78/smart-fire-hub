-- V98: 신규 테넌트의 기본 RBAC·양식을 시드하는 함수.
--
-- 왜 필요한가: V97 이 role 을 테넌트 스코프로 만들었으므로, 신규 테넌트는 역할·권한이 0개다.
-- 멤버십이 있어도 권한 로딩이 0행이라 모든 API 가 403 이 된다. 내장 report_template 3건도
-- 백필로 테넌트 1 소유가 되어 신규 테넌트는 양식 0개로 시작한다.
--
-- 왜 SQL 함수인가: 테넌트 생성은 아직 운영자가 직접 SQL 로 한다(V81 주석). 정의를 앱에만 두면
-- 운영자가 만든 테넌트는 영원히 비어 있다. 함수를 정본으로 두고 앱은 이것을 호출한다.
--
-- SECURITY DEFINER 인 이유: 소유자(app)로 실행되어 RLS 를 우회해야 원본 테넌트(1)의 행을 읽고
-- 대상 테넌트의 행을 쓸 수 있다. 호출자 컨텍스트는 대상 테넌트가 아닐 수 있다(운영자 평면).
-- FORCE ROW LEVEL SECURITY 를 켜면 이 우회가 깨진다 — 그래서 전역 제약으로 금지돼 있다.
--
-- search_path 고정 + PUBLIC 실행권한 회수는 SECURITY DEFINER 함수의 표준 안전장치다
-- (V95 의 트리거 해석 함수와 같은 형태).

CREATE OR REPLACE FUNCTION provision_tenant_defaults(p_tenant_id bigint)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_source_tenant CONSTANT bigint := 1;  -- 기본 테넌트가 시스템 역할·내장 양식의 원본이다
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
  INSERT INTO report_template (tenant_id, user_id, name, description, sections, created_at, updated_at)
  SELECT p_tenant_id, NULL, t.name, t.description, t.sections, now(), now()
  FROM report_template t
  WHERE t.tenant_id = v_source_tenant
    AND t.user_id IS NULL
    AND NOT EXISTS (
      SELECT 1 FROM report_template x
      WHERE x.tenant_id = p_tenant_id AND x.user_id IS NULL AND x.name = t.name);
END;
$$;

-- 아무나 호출하면 임의 테넌트에 행을 쓸 수 있으므로 PUBLIC 실행권한을 회수하고
-- 런타임 롤에만 부여한다.
REVOKE EXECUTE ON FUNCTION provision_tenant_defaults(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION provision_tenant_defaults(bigint) TO app_tenant;

-- 이미 존재하는 비-기본 테넌트가 있으면 지금 채운다(전환 시점엔 보통 없다).
DO $$
DECLARE t bigint;
BEGIN
  FOR t IN SELECT id FROM tenant WHERE id <> 1 LOOP
    PERFORM provision_tenant_defaults(t);
  END LOOP;
END;
$$;
