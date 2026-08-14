-- V88: dataset 도메인에 RLS 정책을 적용한다.
--
-- NULLIF(..., '') 은 생략할 수 없다 — 풀링 커넥션에서 GUC 가 빈 문자열로 남을 수 있고
-- ''::bigint 는 에러를 낸다. current_setting 의 두 번째 인자 true 는 미설정 시 예외 대신 NULL 을
-- 주므로, 컨텍스트가 없으면 조건이 NULL → 전 행 차단(fail-closed)이 된다.
--
-- 이 정책은 테이블 소유자에게 적용되지 않는다. 강제력의 근원은 런타임이 비특권 롤
-- app_tenant(NOBYPASSRLS)로 접속하는 것이다(V83). DataSourceRoleTest 가 그 회귀를 막는다.

ALTER TABLE dataset ENABLE ROW LEVEL SECURITY;
CREATE POLICY dataset_tenant_isolation ON dataset
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE dataset_category ENABLE ROW LEVEL SECURITY;
CREATE POLICY dataset_category_tenant_isolation ON dataset_category
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE dataset_column ENABLE ROW LEVEL SECURITY;
CREATE POLICY dataset_column_tenant_isolation ON dataset_column
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE dataset_tag ENABLE ROW LEVEL SECURITY;
CREATE POLICY dataset_tag_tenant_isolation ON dataset_tag
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE dataset_favorite ENABLE ROW LEVEL SECURITY;
CREATE POLICY dataset_favorite_tenant_isolation ON dataset_favorite
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE dataset_embedding ENABLE ROW LEVEL SECURITY;
CREATE POLICY dataset_embedding_tenant_isolation ON dataset_embedding
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE file_dataset_config ENABLE ROW LEVEL SECURITY;
CREATE POLICY file_dataset_config_tenant_isolation ON file_dataset_config
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
