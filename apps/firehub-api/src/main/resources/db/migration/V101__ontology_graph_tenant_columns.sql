-- 멀티 테넌시 P2-d: 온톨로지·그래프 8테이블에 tenant_id 를 추가한다.
-- 정책(RLS)은 V102 에서 일괄로 켠다 — 중간 커밋에서 온톨로지 UI·GraphRAG MCP 툴이 죽지 않게 하기 위함.
--
-- 순서는 협상 대상이 아니다: nullable 추가 → 백필 → DEFAULT → NOT NULL → FK → 유니크 접기 → 인덱스.
-- DEFAULT 를 백필보다 먼저 넣으면 Flyway(소유자 롤, GUC 없음)에서 NULLIF(...) 가 NULL 을 내
-- NOT NULL 위반으로 마이그레이션이 깨진다. P1~P2-c 에서 세 번 확인한 레시피다.
--
-- 주의: 이 밴드는 RDB 온톨로지 스키마만 격리한다. Neo4j 그래프 자체는 여전히 미격리다.

-- 1) nullable 로 컬럼 추가
ALTER TABLE ontology                 ADD COLUMN tenant_id bigint;
ALTER TABLE ontology_entity_type     ADD COLUMN tenant_id bigint;
ALTER TABLE ontology_entity_property ADD COLUMN tenant_id bigint;
ALTER TABLE ontology_relation        ADD COLUMN tenant_id bigint;
ALTER TABLE dataset_ontology         ADD COLUMN tenant_id bigint;
ALTER TABLE dataset_mapping          ADD COLUMN tenant_id bigint;
ALTER TABLE dataset_graph_ingest     ADD COLUMN tenant_id bigint;
ALTER TABLE graph_review_item        ADD COLUMN tenant_id bigint;

-- 2) 백필 — 부모가 있으면 부모에서, 없으면 기본 테넌트(1).
--    ontology 는 루트라 부모가 없다. 기존 설치는 전부 단일 테넌트이므로 1 이 정답이다.
UPDATE ontology SET tenant_id = 1 WHERE tenant_id IS NULL;

UPDATE ontology_entity_type t SET tenant_id = o.tenant_id
  FROM ontology o WHERE o.id = t.ontology_id AND t.tenant_id IS NULL;

UPDATE ontology_entity_property p SET tenant_id = et.tenant_id
  FROM ontology_entity_type et WHERE et.id = p.entity_type_id AND p.tenant_id IS NULL;

UPDATE ontology_relation r SET tenant_id = o.tenant_id
  FROM ontology o WHERE o.id = r.ontology_id AND r.tenant_id IS NULL;

-- dataset_* 3테이블과 graph_review_item 은 dataset 에 FK 가 없다(감사 패턴).
-- 조인으로 채우되, 데이터셋이 이미 삭제된 고아 행이 남을 수 있으므로 뒤에서 1 로 마감한다.
UPDATE dataset_ontology d SET tenant_id = ds.tenant_id
  FROM dataset ds WHERE ds.id = d.dataset_id AND d.tenant_id IS NULL;
UPDATE dataset_mapping m SET tenant_id = ds.tenant_id
  FROM dataset ds WHERE ds.id = m.dataset_id AND m.tenant_id IS NULL;
UPDATE dataset_graph_ingest g SET tenant_id = ds.tenant_id
  FROM dataset ds WHERE ds.id = g.dataset_id AND g.tenant_id IS NULL;
-- graph_review_item.dataset_id 는 nullable 이다(데이터셋에 매이지 않는 검수 항목이 있다).
UPDATE graph_review_item i SET tenant_id = ds.tenant_id
  FROM dataset ds WHERE ds.id = i.dataset_id AND i.tenant_id IS NULL;

UPDATE dataset_ontology     SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE dataset_mapping      SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE dataset_graph_ingest SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE graph_review_item    SET tenant_id = 1 WHERE tenant_id IS NULL;

-- 3) DEFAULT — 반드시 백필 뒤에. 앱은 tenant_id 를 쓰지 않고 GUC 에서 받는다.
ALTER TABLE ontology                 ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE ontology_entity_type     ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE ontology_entity_property ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE ontology_relation        ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE dataset_ontology         ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE dataset_mapping          ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE dataset_graph_ingest     ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;
ALTER TABLE graph_review_item        ALTER COLUMN tenant_id SET DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint;

-- 4) NOT NULL
ALTER TABLE ontology                 ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE ontology_entity_type     ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE ontology_entity_property ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE ontology_relation        ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE dataset_ontology         ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE dataset_mapping          ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE dataset_graph_ingest     ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE graph_review_item        ALTER COLUMN tenant_id SET NOT NULL;

-- 5) FK
ALTER TABLE ontology                 ADD CONSTRAINT ontology_tenant_id_fkey                 FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE ontology_entity_type     ADD CONSTRAINT ontology_entity_type_tenant_id_fkey     FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE ontology_entity_property ADD CONSTRAINT ontology_entity_property_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE ontology_relation        ADD CONSTRAINT ontology_relation_tenant_id_fkey        FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE dataset_ontology         ADD CONSTRAINT dataset_ontology_tenant_id_fkey         FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE dataset_mapping          ADD CONSTRAINT dataset_mapping_tenant_id_fkey          FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE dataset_graph_ingest     ADD CONSTRAINT dataset_graph_ingest_tenant_id_fkey     FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE graph_review_item        ADD CONSTRAINT graph_review_item_tenant_id_fkey        FOREIGN KEY (tenant_id) REFERENCES tenant(id);

-- 6) 유니크 접기 — 유니크 인덱스는 RLS 와 무관하게 전역 적용된다.
--    접지 않으면 다른 테넌트의 보이지 않는 행과 이름이 충돌한다.
--    ※ 접은 뒤에는 ON CONFLICT 추론 대상도 (tenant_id, …) 가 되므로 해당 upsert 3곳
--      (MappingRepository / DatasetOntologyRepository / ReviewItemRepository)을 같은 커밋에서 맞췄다.
DROP INDEX ontology_domain_unique;
CREATE UNIQUE INDEX ontology_domain_unique ON ontology(tenant_id, domain)
  WHERE status <> 'archived';

ALTER TABLE dataset_ontology DROP CONSTRAINT dataset_ontology_dataset_id_key;
CREATE UNIQUE INDEX dataset_ontology_dataset_id_key ON dataset_ontology(tenant_id, dataset_id);

ALTER TABLE dataset_mapping DROP CONSTRAINT dataset_mapping_dataset_id_key;
CREATE UNIQUE INDEX dataset_mapping_dataset_id_key ON dataset_mapping(tenant_id, dataset_id);

ALTER TABLE graph_review_item DROP CONSTRAINT uq_graph_review_item;
CREATE UNIQUE INDEX uq_graph_review_item ON graph_review_item(tenant_id, item_type, dedupe_key);

-- ontology_entity_type(ontology_id, type), ontology_entity_property(entity_type_id, name),
-- ontology_relation_unique 는 이미 ontology 스코프라 접을 필요가 없다(설계서 §3).

-- 7) 테넌트 선행 조회 인덱스 — 정책이 모든 쿼리에 tenant_id 술어를 붙이므로
--    기존 인덱스만으로는 테넌트 수에 비례해 스캔이 늘어난다.
CREATE INDEX idx_ontology_tenant_status              ON ontology(tenant_id, status);
CREATE INDEX idx_graph_review_item_tenant_status     ON graph_review_item(tenant_id, status);
CREATE INDEX idx_dataset_graph_ingest_tenant_dataset ON dataset_graph_ingest(tenant_id, dataset_id, ingested_at DESC);
