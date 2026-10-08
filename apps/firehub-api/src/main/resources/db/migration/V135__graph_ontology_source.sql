-- V135: 지식그래프 읽기 게이트(WD-28)의 출처 기록.
--
-- 왜 필요한가: Neo4j 에 적재된 데이터는 온톨로지 단위로만 스코프된다. 데이터셋 보안 등급을 올려도 이미 쓰인 그래프는
-- 계속 읽힌다. 그래서 "이 데이터셋의 내용이 이 온톨로지 그래프에 쓰인 적이 있다"를 남기고, 읽기 시점에 출처 전부를
-- 볼 수 있는지 판정한다(GraphReadGate).
-- 현재 바인딩(dataset_ontology)만으로는 판정할 수 없다. 다른 온톨로지로 재연결해도 예전 그래프의 데이터는 남기 때문이다.
--
-- 규칙: 삽입만 한다(연결·매핑 저장 시 INSERT … ON CONFLICT DO NOTHING). 재연결·매핑 삭제로는 지우지 않는다.
-- 온톨로지 삭제 시 CASCADE 로만 사라진다.
-- dataset_id 에 FK 를 두지 않는 이유(audit 패턴): 데이터셋이 삭제돼도 그래프 잔존 사실은 남겨야 한다.
--
-- RLS 는 V102 표준 형태 (a). FORCE ROW LEVEL SECURITY 는 쓰지 않는다(V102 주석 — 소유자 함수가 0행이 된다).
-- app_tenant 권한은 V83 의 ALTER DEFAULT PRIVILEGES 가 부여하므로 GRANT 문이 필요 없다.
CREATE TABLE graph_ontology_source (
  tenant_id        bigint      NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint REFERENCES tenant(id),
  ontology_id      bigint      NOT NULL REFERENCES ontology(id) ON DELETE CASCADE,
  dataset_id       bigint      NOT NULL,
  first_written_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, ontology_id, dataset_id)
);

ALTER TABLE graph_ontology_source ENABLE ROW LEVEL SECURITY;
CREATE POLICY graph_ontology_source_tenant_isolation ON graph_ontology_source
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

-- 백필: 기존 출처를 최대한 넓게(안전 쪽) 잡는다. 현재 바인딩 전부 + 매핑 전부(draft 포함).
-- Flyway 는 소유자 롤로 GUC 없이 돌므로 tenant_id DEFAULT 가 NULL 이 된다 — 반드시 원행의 tenant_id 를 명시한다(V120 선례).
-- 한계: 과거에 다른 온톨로지로 적재했다가 재연결한 이력은 복원할 수 없다(이력에 ontology id 가 없음, deploy.md 수동 점검).
-- 아래 표식은 GraphOntologySourceBackfillTest 가 이 구간만 잘라 재실행하는 데 쓴다 — 지우지 말 것.
-- BACKFILL-BEGIN
INSERT INTO graph_ontology_source (tenant_id, ontology_id, dataset_id)
SELECT tenant_id, ontology_id, dataset_id FROM dataset_ontology
UNION
SELECT tenant_id, ontology_id, dataset_id FROM dataset_mapping
ON CONFLICT DO NOTHING;
-- BACKFILL-END
