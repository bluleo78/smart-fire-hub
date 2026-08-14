-- V90: document 도메인 RLS 정책. dataset 도메인(V88)과 동일한 표준 형태다.

ALTER TABLE document_file ENABLE ROW LEVEL SECURITY;
CREATE POLICY document_file_tenant_isolation ON document_file
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE document_chunk ENABLE ROW LEVEL SECURITY;
CREATE POLICY document_chunk_tenant_isolation ON document_chunk
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
