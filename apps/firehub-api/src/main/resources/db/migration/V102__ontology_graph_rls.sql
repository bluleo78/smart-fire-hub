-- V102: P2-d 격리 스위치. V101 이 8테이블에 tenant_id 를 심었고, Task 2~4 가 그 테이블을
-- 읽고 쓰는 리포지토리 6개에 트랜잭션 경계를 붙였다. 이 파일이 실제로 정책을 켠다.
--
-- 왜 컬럼과 정책을 나눴는가: 중간 커밋에서 정책이 먼저 걸리면 온톨로지 UI·GraphRAG MCP 툴이
-- 트랜잭션 없는 경로에서 전부 0행이 되어 죽는다. 배선이 끝난 뒤에 켠다.
--
-- 전부 표준 형태 (a) 다 — V101 이 8테이블 모두 tenant_id NOT NULL 을 걸었으므로 NULL 테넌트 행이
-- 존재할 수 없다. audit_log 가 쓰는 형태 (b)(IS NOT DISTINCT FROM)는 GUC 가 비면 NULL-vs-NULL 이
-- 참이 되어 fail-open 이므로 여기서는 쓰지 않는다(constraints.md 판정 B).
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
-- 범위 주의: 이 밴드는 RDB 온톨로지 스키마만 격리한다. Neo4j 그래프 자체는 여전히 미격리다.

ALTER TABLE ontology ENABLE ROW LEVEL SECURITY;
CREATE POLICY ontology_tenant_isolation ON ontology
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE ontology_entity_type ENABLE ROW LEVEL SECURITY;
CREATE POLICY ontology_entity_type_tenant_isolation ON ontology_entity_type
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE ontology_entity_property ENABLE ROW LEVEL SECURITY;
CREATE POLICY ontology_entity_property_tenant_isolation ON ontology_entity_property
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE ontology_relation ENABLE ROW LEVEL SECURITY;
CREATE POLICY ontology_relation_tenant_isolation ON ontology_relation
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE dataset_ontology ENABLE ROW LEVEL SECURITY;
CREATE POLICY dataset_ontology_tenant_isolation ON dataset_ontology
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE dataset_mapping ENABLE ROW LEVEL SECURITY;
CREATE POLICY dataset_mapping_tenant_isolation ON dataset_mapping
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE dataset_graph_ingest ENABLE ROW LEVEL SECURITY;
CREATE POLICY dataset_graph_ingest_tenant_isolation ON dataset_graph_ingest
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE graph_review_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY graph_review_item_tenant_isolation ON graph_review_item
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
