-- V136: 쿼리 결과 내보내기 재설계(스펙 §4.4, WD-42) — 실행 기록 ID 기반 서버 재판정·재실행.
--
-- 왜 필요한가: 예전 POST /query-results/export 는 클라이언트가 보낸 rows 를 그대로 파일로 만들었다. 서버는 그 행이 어디서 왔는지
-- 몰라 내보내기 정책을 판정할 수 없었다. 이제 애드혹 실행(/analytics/queries/execute)이 SQL 원문을 여기 남기고, 내보내기는 이 id 로
-- 그 SQL 을 내보내는 시점의 자격으로 다시 판정·실행한다(저장된 결과·플래그는 믿지 않는다).
--
-- 왜 query_history 를 쓰지 않는가: query_history.dataset_id 는 NOT NULL(V24) 이라 데이터셋 /query 전용이다. 애드혹 분석 쿼리는
-- 여러 데이터셋을 참조하거나 하나도 참조하지 않을 수 있다.
--
-- 보존: 1시간. 삽입 시 같은 사용자의 1시간 지난 행을 지운다(AnalyticsQueryRunRepository) — ai-agent 도 같은 엔드포인트를 써서 행이 쌓이기 때문.
-- 조회: 소유자만(user_id 일치). 다른 사용자의 id 는 없는 id 와 같은 404.
-- RLS 는 V102 표준 형태 (a). FORCE ROW LEVEL SECURITY 금지. app_tenant 권한은 V83 의 ALTER DEFAULT PRIVILEGES 가 부여한다.
CREATE TABLE analytics_query_run (
  id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  bigint      NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint REFERENCES tenant(id),
  user_id    bigint      NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
  sql_text   text        NOT NULL,
  max_rows   int         NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE analytics_query_run ENABLE ROW LEVEL SECURITY;
CREATE POLICY analytics_query_run_tenant_isolation ON analytics_query_run
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

-- 소유자 조회와 보존 정리(같은 사용자 + 오래된 것)가 이 인덱스를 탄다.
CREATE INDEX idx_analytics_query_run_user ON analytics_query_run (user_id, created_at);
