-- V113: (1) 플랫폼 설정 권한 2건 신설, (2) provision_tenant_defaults 가 기본 데이터셋 카테고리도
-- 복제하도록 고친다.
--
-- (1) 왜 필요한가: system_settings 는 전역 18행이고 테넌트 컬럼도 RLS 도 없는데, 편집 권한
--     (ai:settings / settings:write)은 각 테넌트의 ADMIN 롤이 보유한다. 즉 한 테넌트 관리자가
--     저장하면 전 테넌트에 적용된다(설계서 §4.5). 플랫폼 기본값 편집을 운영자 평면으로 옮기기
--     위한 권한 코드를 여기서 만든다. 실제 효력 범위 분리(tenant_settings)는 P7-b 다.
--
-- (2) 왜 필요한가: 설계서 §4 는 신규 테넌트에 dataset_category 시드 복제를 결정했으나 V98/V100 의
--     함수 본문은 role·role_permission·report_template 만 채운다. 결과적으로 프로비저닝된 테넌트는
--     카테고리 0개로 시작해 데이터셋 분류가 불가능하다 — 누출이 아니라 기능 회귀다.
--
-- 왜 V98/V100 을 고치지 않는가: 둘 다 dev·test DB 에 적용돼 있다. 편집하면 checksum mismatch 로
-- 두 DB 가 부팅 불가가 된다. V100 의 선례대로 CREATE OR REPLACE 로 정의를 덮는다.

-- ── (1) 플랫폼 설정 권한 ────────────────────────────────────────────────────────
INSERT INTO permission (code, category, description)
VALUES
  ('platform:settings:read',  'platform', '플랫폼 기본 설정 조회'),
  ('platform:settings:write', 'platform', '플랫폼 기본 설정 변경')
ON CONFLICT (code) DO NOTHING;

-- SUPER_ADMIN 에 연결한다. V82 는 category='platform' 전체를 걸었으므로 같은 규칙을 유지한다.
INSERT INTO platform_role_permission (platform_role_id, permission_id)
SELECT pr.id, p.id
FROM platform_role pr, permission p
WHERE pr.name = 'SUPER_ADMIN'
  AND p.code IN ('platform:settings:read', 'platform:settings:write')
ON CONFLICT DO NOTHING;

-- ── (2) 프로비저닝에 기본 카테고리 복제 추가 ──────────────────────────────────────
-- V100 본문을 그대로 유지하고 카테고리 블록만 덧붙인다(CREATE OR REPLACE 는 전체 본문이 필요하다).
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
END;
$$;

-- CREATE OR REPLACE 는 기존 권한을 보존하지만, 이 함수의 안전성이 권한 설정에 달려 있으므로
-- 명시적으로 다시 건다(이 파일만 읽어도 계약이 드러나야 한다).
REVOKE EXECUTE ON FUNCTION provision_tenant_defaults(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION provision_tenant_defaults(bigint) TO app_tenant;

-- 이미 프로비저닝된 비-기본 테넌트에 누락된 기본 카테고리를 채운다.
-- tenant 테이블을 순회하지 않고 "역할은 있는데 카테고리는 없는 테넌트"만 대상으로 한다 —
-- 프로비저닝을 받은 적 없는 테넌트에 카테고리만 생기는 어긋난 상태를 만들지 않기 위해서다.
INSERT INTO dataset_category (tenant_id, name, created_at, updated_at)
SELECT t.id, c.name, now(), now()
FROM tenant t
CROSS JOIN unnest(ARRAY['행정', '운영', '통계']) AS c(name)
WHERE t.id <> 1
  AND EXISTS (SELECT 1 FROM role r WHERE r.tenant_id = t.id AND r.is_system = true)
  AND NOT EXISTS (
    SELECT 1 FROM dataset_category x WHERE x.tenant_id = t.id AND x.name = c.name);
