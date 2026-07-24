-- 범용 AI 검수 인박스 — 기존 synonym_decision(V75)을 item_type 구분자 기반으로 일반화한다.
-- payload(JSONB)에 타입별 상세를 담고, (item_type, dedupe_key) 유니크로 중복 등록을 막는다.
-- 기존 synonym_decision 행을 item_type='synonym_merge'로 이관한 뒤 구 테이블을 제거한다.
CREATE TABLE IF NOT EXISTS graph_review_item (
    id            BIGSERIAL         PRIMARY KEY,
    item_type     VARCHAR(40)       NOT NULL,
    status        VARCHAR(20)       NOT NULL DEFAULT 'pending',
    dataset_id    BIGINT,
    signal_type   VARCHAR(40),
    signal_score  DOUBLE PRECISION,
    reason        TEXT,
    payload       JSONB             NOT NULL,
    dedupe_key    VARCHAR(512)      NOT NULL,
    decided_by    BIGINT REFERENCES "user"(id),
    decided_at    TIMESTAMP,
    created_at    TIMESTAMP         NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_graph_review_item UNIQUE (item_type, dedupe_key)
);

CREATE INDEX IF NOT EXISTS idx_graph_review_item_status ON graph_review_item (status);
CREATE INDEX IF NOT EXISTS idx_graph_review_item_type_status ON graph_review_item (item_type, status);

-- 기존 근접쌍 검수 행 이관 — dedupe_key는 (정렬된 원본표기) entity_type|name_a|name_b 로 구성해
-- 구 uq_synonym_decision_pair(entity_type,name_a,name_b) 의미를 그대로 재현한다.
INSERT INTO graph_review_item
    (item_type, status, signal_type, signal_score, reason, payload, dedupe_key, decided_by, decided_at, created_at)
SELECT
    'synonym_merge', status, 'similarity', similarity, rationale,
    jsonb_build_object('entityType', entity_type, 'nameA', name_a, 'nameB', name_b),
    entity_type || '|' || name_a || '|' || name_b,
    decided_by, decided_at, created_at
FROM synonym_decision;

DROP TABLE synonym_decision;
