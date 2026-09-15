-- 레거시 데이터 백필: 과거엔 온톨로지 바인딩 없이도 GraphRAG 적재가 조용히 id=1 온톨로지로 진행됐다
-- (문서 파이프라인이 GET /ontology 하드코딩 findById(1L)에 의존하던 시절). 이제 미바인딩 데이터셋은
-- 적재/조회 자체가 거부되므로, 과거에 실제로 id=1로 적재된 기록이 있는 데이터셋에는 그 사실을
-- dataset_ontology에 명시적으로 남겨 "적재 이력이 있으면 반드시 바인딩도 있다"는 불변식을 과거
-- 데이터에도 성립시킨다. 관리자가 원하면 이후 UI에서 재바인딩할 수 있다.
-- 주의: tenant_id=1 데이터만 대상으로 함 (모든 레거시 데이터는 V101 마이그레이션에서 이미 tenant_id=1로 백필됨).
-- 다른 테넌트의 데이터가 섞여 있다면, 이 마이그레이션은 그것을 skip하고 교차 테넌트 바인딩을 하지 않는다.
INSERT INTO dataset_ontology (dataset_id, ontology_id, bound_at, bound_by, tenant_id)
SELECT DISTINCT g.dataset_id, 1, NOW(), CAST(NULL AS BIGINT), g.tenant_id
FROM dataset_graph_ingest g
WHERE NOT EXISTS (
    SELECT 1 FROM dataset_ontology o WHERE o.dataset_id = g.dataset_id
)
AND EXISTS (SELECT 1 FROM ontology WHERE id = 1)
AND g.tenant_id = 1;
