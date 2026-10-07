-- V134: 검수 항목 중복 판정(dedupe)을 테넌트 단위에서 "데이터셋 단위"로 좁힌다.
--
-- 왜: V101 의 uq_graph_review_item(tenant_id, item_type, dedupe_key) 는 같은 이름(키)이 테넌트 안
-- 어느 데이터셋에든 한 번만 존재하게 했다. 데이터셋 보안 등급(V133) 이후 이것이 두 문제를 만든다.
--   1) 존재 오라클: 볼 수 없는 데이터셋 Y 에 같은 키가 있으면, 볼 수 있는 데이터셋 X 로 보낸 등록이
--      ON CONFLICT DO NOTHING 으로 흡수돼 어느 목록에도 나타나지 않는다 → "Y 에 그 이름이 있다"가 드러난다.
--   2) 기능 회귀: 자격이 낮은 사용자의 X ingest 가 숨김 Y 의 결정을 볼 수 없어 보류·큐 등록하는데,
--      그 등록이 흡수돼 엔티티가 X 그래프에서도 인박스에서도 조용히 사라진다.
-- dataset_id 를 유일 키에 넣으면 데이터셋마다 자기 항목을 갖게 되어 둘 다 사라진다.
--
-- NULL dataset_id(레거시 행 — 새 등록은 datasetId 필수)는 NULLS NOT DISTINCT 로 기존처럼
-- (tenant_id, item_type, dedupe_key) 당 1건을 유지한다(PG15+; 운영·개발·테스트 이미지 모두 PG16).
--
-- 기존 데이터 위반 불가: 새 키는 옛 키의 상위 집합(열 추가)이라 옛 제약을 만족하던 행은 새 제약도 만족한다.
-- 인덱스 이름은 그대로 둔다(OntologyGraphSchemaTest 가 tenant_id 선두를 이 이름으로 검사한다).
DROP INDEX IF EXISTS uq_graph_review_item;
CREATE UNIQUE INDEX uq_graph_review_item
    ON graph_review_item (tenant_id, item_type, dataset_id, dedupe_key) NULLS NOT DISTINCT;
