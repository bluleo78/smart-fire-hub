-- V100: provision_tenant_defaults 가 report_template.style 도 복제하도록 고친다 + 기존 행 백필.
--
-- 무엇이 문제였나: V98 의 내장 양식 INSERT 가 컬럼 목록에
-- (tenant_id, user_id, name, description, sections, created_at, updated_at) 만 나열하고 style 을
-- 빠뜨렸다. style 은 nullable 이라 마이그레이션이 실패하지 않아 조용히 통과했지만, 원본 테넌트의
-- 내장 양식 3건은 모두 style 이 NOT NULL 이고 ProactiveJobAsyncRunner 가 그 값을 AI 클라이언트에
-- 넘긴다. 결과적으로 프로비저닝된 모든 신규 테넌트는 스타일 없는 내장 양식을 받는다.
--
-- 왜 V98 을 고치지 않고 V100 인가: V98 은 이미 dev·test DB 에 적용돼 있다. 편집하면 Flyway
-- checksum mismatch 로 두 DB 가 모두 부팅 불가가 된다. 그래서 CREATE OR REPLACE 로 정의를 덮는다.
--
-- V98 함수의 성질은 전부 그대로 유지한다: SECURITY DEFINER(소유자로 실행해 RLS 를 우회해야 원본
-- 테넌트 행을 읽고 대상 테넌트 행을 쓸 수 있다), SET search_path 고정, 원본 테넌트 자신에 대한 조기
-- 반환, NOT EXISTS 멱등성 가드.
--
-- V98 말미의 "기존 비-기본 테넌트 전수 재프로비저닝" DO 블록은 <b>의도적으로 옮겨오지 않는다</b>.
-- 그 블록은 fresh bootstrap 에서 한 번 64건 테스트를 깨뜨린 전례가 있고(최종 리뷰 triage #2),
-- 여기서 필요한 것은 누락된 style 을 채우는 좁은 UPDATE 뿐이다.

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
  --
  -- style 이 V100 에서 추가된 컬럼이다 — 리포트 생성 시 AI 에 전달되는 서술 스타일이라
  -- 빠지면 신규 테넌트의 리포트만 문체가 달라진다.
  INSERT INTO report_template (tenant_id, user_id, name, description, sections, style, created_at, updated_at)
  SELECT p_tenant_id, NULL, t.name, t.description, t.sections, t.style, now(), now()
  FROM report_template t
  WHERE t.tenant_id = v_source_tenant
    AND t.user_id IS NULL
    AND NOT EXISTS (
      SELECT 1 FROM report_template x
      WHERE x.tenant_id = p_tenant_id AND x.user_id IS NULL AND x.name = t.name);
END;
$$;

-- CREATE OR REPLACE 는 기존 권한을 보존하지만, 이 함수의 안전성이 권한 설정에 달려 있으므로
-- 명시적으로 다시 건다(누가 V98 없이 이 파일만 읽어도 계약이 드러나야 한다).
REVOKE EXECUTE ON FUNCTION provision_tenant_defaults(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION provision_tenant_defaults(bigint) TO app_tenant;

-- 이미 V98 로 프로비저닝된 내장 양식의 누락된 style 을 채운다.
-- 오늘은 무동작이다(프로비저닝된 테넌트가 없다) — 그래도 올바르게 써 둔다.
--
-- 매칭 기준은 함수의 복제 규칙과 같다: 원본=테넌트 1의 user_id IS NULL 행, 대상=같은 이름의
-- 다른 테넌트 user_id IS NULL 행. tgt.style IS NULL 조건이 두 가지를 동시에 한다 —
-- 재실행해도 멱등이고, 운영자가 직접 손본 style 을 덮어쓰지 않는다.
UPDATE report_template tgt
SET style = src.style,
    updated_at = now()
FROM report_template src
WHERE src.tenant_id = 1
  AND src.user_id IS NULL
  AND src.style IS NOT NULL
  AND tgt.tenant_id <> 1
  AND tgt.user_id IS NULL
  AND tgt.name = src.name
  AND tgt.style IS NULL;
