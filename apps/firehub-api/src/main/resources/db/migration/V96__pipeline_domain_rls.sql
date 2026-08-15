-- P2-b 정책. 이 파일이 격리 스위치다 — V93 이 컬럼을, Task 1~8 이 배경·비인증 경로를 준비했다.
--
-- NULLIF(..., '') 은 생략할 수 없다 — 풀링 커넥션에서 GUC 가 빈 문자열로 남을 수 있고
-- ''::bigint 는 에러를 낸다. current_setting 의 두 번째 인자 true 는 미설정 시 예외 대신 NULL 을
-- 주므로, 컨텍스트가 없으면 조건이 NULL → 전 행 차단(fail-closed)이 된다.
--
-- 이 정책은 테이블 소유자에게 적용되지 않는다. 강제력의 근원은 런타임이 비특권 롤
-- app_tenant(NOBYPASSRLS)로 접속하는 것이다(V83). DataSourceRoleTest 가 그 회귀를 막는다.
--
-- pipeline_trigger 에도 정책을 건다. 비인증 경로는 V95 의 SECURITY DEFINER 해석 함수로
-- 테넌트를 복원한 뒤 이 정책 아래에서 처리된다 — 정책을 이연하지 않는다.
--
-- FORCE ROW LEVEL SECURITY 는 쓰지 않는다. 켜면 소유자에게까지 정책이 적용돼 V95 의
-- SECURITY DEFINER 해석 함수(소유자 = 테이블 소유자 = app)가 0행을 받고, 인증 전에 테넌트를
-- 알 수 없는 외부 웹훅·API 트리거 경로가 전부 404/401 로 전멸한다.
-- TriggerTenantResolverTest.definerBypassPreconditionsHold 가 이 전제를 카탈로그로 고정한다.

ALTER TABLE pipeline ENABLE ROW LEVEL SECURITY;
CREATE POLICY pipeline_tenant_isolation ON pipeline
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE pipeline_step ENABLE ROW LEVEL SECURITY;
CREATE POLICY pipeline_step_tenant_isolation ON pipeline_step
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE pipeline_step_input ENABLE ROW LEVEL SECURITY;
CREATE POLICY pipeline_step_input_tenant_isolation ON pipeline_step_input
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE pipeline_step_dependency ENABLE ROW LEVEL SECURITY;
CREATE POLICY pipeline_step_dependency_tenant_isolation ON pipeline_step_dependency
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE pipeline_execution ENABLE ROW LEVEL SECURITY;
CREATE POLICY pipeline_execution_tenant_isolation ON pipeline_execution
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE pipeline_step_execution ENABLE ROW LEVEL SECURITY;
CREATE POLICY pipeline_step_execution_tenant_isolation ON pipeline_step_execution
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE pipeline_trigger ENABLE ROW LEVEL SECURITY;
CREATE POLICY pipeline_trigger_tenant_isolation ON pipeline_trigger
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE trigger_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY trigger_event_tenant_isolation ON trigger_event
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE api_connection ENABLE ROW LEVEL SECURITY;
CREATE POLICY api_connection_tenant_isolation ON api_connection
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE async_job ENABLE ROW LEVEL SECURITY;
CREATE POLICY async_job_tenant_isolation ON async_job
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE uploaded_files ENABLE ROW LEVEL SECURITY;
CREATE POLICY uploaded_files_tenant_isolation ON uploaded_files
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
