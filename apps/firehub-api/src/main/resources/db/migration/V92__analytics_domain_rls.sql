-- V92: analytics 도메인 RLS 정책. dataset/document 도메인과 동일한 표준 형태다.

ALTER TABLE saved_query ENABLE ROW LEVEL SECURITY;
CREATE POLICY saved_query_tenant_isolation ON saved_query
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE dashboard ENABLE ROW LEVEL SECURITY;
CREATE POLICY dashboard_tenant_isolation ON dashboard
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE chart ENABLE ROW LEVEL SECURITY;
CREATE POLICY chart_tenant_isolation ON chart
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE dashboard_widget ENABLE ROW LEVEL SECURITY;
CREATE POLICY dashboard_widget_tenant_isolation ON dashboard_widget
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE query_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY query_history_tenant_isolation ON query_history
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
