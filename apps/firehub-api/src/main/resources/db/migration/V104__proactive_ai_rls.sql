-- V104: P2-e 격리 스위치. V103 이 프로액티브 5테이블 + AI 2테이블에 tenant_id 를 심었고,
-- Task 3~5 가 그 테이블을 읽고 쓰는 리포지토리 7개와 배경 경로(부팅 재등록·크론 발화·메트릭 폴러·
-- 이상탐지 @Async·채팅 채널 발송·AI 추론 캐시)에 테넌트 컨텍스트와 트랜잭션을 붙였다.
-- 이 파일이 실제로 정책을 켠다.
--
-- 왜 컬럼과 정책을 나눴는가: 중간 커밋에서 정책이 먼저 걸리면 컨텍스트 없는 배경 경로가 전부
-- 0행이 되어 프로액티브 잡·AI 챗이 조용히 죽는다(에러 없이 빈 목록). 배선이 끝난 뒤에 켠다.
-- V101/V102 와 같은 순서다.
--
-- 전부 표준 형태 (a) 다 — V103 이 7테이블 모두 tenant_id NOT NULL 을 걸었으므로 NULL 테넌트 행이
-- 존재할 수 없다. audit_log 가 쓰는 형태 (b)(NOT (tenant_id IS DISTINCT FROM ...))는 GUC 가 비면
-- NULL-vs-NULL 이 참이 되어 fail-open 이므로 여기서는 쓰지 않는다.
--
-- NULLIF(..., '') 은 생략할 수 없다 — 풀링 커넥션에서 GUC 가 빈 문자열로 남을 수 있고
-- ''::bigint 는 에러를 낸다. current_setting 의 두 번째 인자 true 는 미설정 시 NULL 을 주므로
-- 컨텍스트가 없으면 조건이 NULL → 전 행 차단(fail-closed)이 된다.
--
-- 이 정책은 테이블 소유자(app)에게 적용되지 않는다. 강제력의 근원은 런타임이 비특권 롤
-- app_tenant(NOBYPASSRLS, V83)로 접속한다는 사실이다. DataSourceRoleTest 가 그 회귀를 막는다.
--
-- FORCE ROW LEVEL SECURITY 는 쓰지 않는다. 켜면 소유자에게까지 정책이 적용돼 V98 의
-- provision_tenant_defaults(SECURITY DEFINER)와 V95 의 트리거 테넌트 해석 함수가 0행을 받아
-- 프로비저닝·외부 웹훅 트리거가 전멸한다.
--
-- 범위 주의: 채널 도메인(slack_workspace, user_channel_binding, user_channel_preference,
-- notification_outbox)은 P2-f 소관이라 아직 미격리다. ProactiveJobAsyncRunner 가 outbox 에 쓰는
-- 지점은 P2-f 가 정책을 켤 때 테넌트를 싣고 있어야 한다.

ALTER TABLE proactive_job ENABLE ROW LEVEL SECURITY;
CREATE POLICY proactive_job_tenant_isolation ON proactive_job
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE proactive_job_execution ENABLE ROW LEVEL SECURITY;
CREATE POLICY proactive_job_execution_tenant_isolation ON proactive_job_execution
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE proactive_message ENABLE ROW LEVEL SECURITY;
CREATE POLICY proactive_message_tenant_isolation ON proactive_message
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE metric_snapshot ENABLE ROW LEVEL SECURITY;
CREATE POLICY metric_snapshot_tenant_isolation ON metric_snapshot
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE anomaly_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY anomaly_event_tenant_isolation ON anomaly_event
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE ai_session ENABLE ROW LEVEL SECURITY;
CREATE POLICY ai_session_tenant_isolation ON ai_session
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE ai_inference_cache ENABLE ROW LEVEL SECURITY;
CREATE POLICY ai_inference_cache_tenant_isolation ON ai_inference_cache
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
