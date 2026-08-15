-- V99: P2-c 격리 스위치. V97 이 컬럼을, Task 2~4 가 인증·비동기·회원가입·프로비저닝 경로를
-- 준비했다. 이 파일이 실제로 정책을 켠다.
--
-- 정책을 맨 뒤에 둔 이유(P2-b 와 같은 구조이지만 RBAC 은 더 치명적이다): 중간 커밋에서
-- role/user_role 에 정책이 걸리면 그 커밋의 모든 인증 요청이 권한 0개가 되고 회원가입이 깨진다.
--
-- NULLIF(..., '') 은 생략할 수 없다 — 풀링 커넥션에서 GUC 가 빈 문자열로 남을 수 있고
-- ''::bigint 는 에러를 낸다. current_setting 의 두 번째 인자 true 는 미설정 시 NULL 을 주므로
-- 컨텍스트가 없으면 조건이 NULL → 전 행 차단(fail-closed)이 된다.
--
-- 이 정책은 테이블 소유자에게 적용되지 않는다. 강제력의 근원은 런타임이 비특권 롤
-- app_tenant(NOBYPASSRLS, V83)로 접속한다는 사실이다. DataSourceRoleTest 가 그 회귀를 막는다.
--
-- FORCE ROW LEVEL SECURITY 는 쓰지 않는다. 켜면 소유자에게까지 정책이 적용돼 V98 의
-- provision_tenant_defaults(SECURITY DEFINER)가 원본 테넌트 행을 못 읽고, V95 의 트리거
-- 해석 함수도 0행을 받아 외부 웹훅·API 트리거가 전멸한다.

ALTER TABLE role ENABLE ROW LEVEL SECURITY;
CREATE POLICY role_tenant_isolation ON role
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE role_permission ENABLE ROW LEVEL SECURITY;
CREATE POLICY role_permission_tenant_isolation ON role_permission
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE user_role ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_role_tenant_isolation ON user_role
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE report_template ENABLE ROW LEVEL SECURITY;
CREATE POLICY report_template_tenant_isolation ON report_template
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

-- audit_log 만 형태 (b) — NULL 테넌트를 허용한다.
-- 로그인·회원가입 감사 이벤트는 테넌트 선택 전에 발생해 tenant_id 가 NULL 이다. 표준 WITH CHECK
-- 는 NULL INSERT 를 거부해 로그인을 깨뜨리고, 이어서 INSERT ... RETURNING 이 반환 행에 USING
-- 정책까지 적용해 또 깨진다(참조 프로젝트가 V55→V56→V57 로 두 번 낸 버그). IS NOT DISTINCT FROM
-- 은 NULL-vs-NULL 을 참으로 만들어 RETURNING 을 통과시키면서, 실제 테넌트 컨텍스트에서는
-- NULL 행을 계속 안 보이게 한다.
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_log_tenant_isolation ON audit_log
  USING      (tenant_id IS NOT DISTINCT FROM NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id IS NOT DISTINCT FROM NULLIF(current_setting('app.tenant_id', true), '')::bigint);
