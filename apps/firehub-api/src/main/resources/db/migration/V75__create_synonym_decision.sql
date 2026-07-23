-- 근접쌍(코사인 0.5~0.78) LLM 병합 판정 사람 검수 대기열(HITL). dataset_graph_ingest 패턴을 따름.
-- name_a/name_b는 정규화 비교로 정렬된 원본 표기(UI 가독성) — 유니크 제약으로 동일 쌍 중복 등록을 막는다.
CREATE TABLE IF NOT EXISTS synonym_decision (
    id            BIGSERIAL         PRIMARY KEY,
    entity_type   VARCHAR(50)       NOT NULL,
    name_a        VARCHAR(200)      NOT NULL,
    name_b        VARCHAR(200)      NOT NULL,
    status        VARCHAR(20)       NOT NULL DEFAULT 'pending',
    similarity    DOUBLE PRECISION,
    rationale     TEXT,
    decided_by    BIGINT REFERENCES "user"(id),
    decided_at    TIMESTAMP,
    created_at    TIMESTAMP         NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_synonym_decision_pair UNIQUE (entity_type, name_a, name_b)
);

CREATE INDEX IF NOT EXISTS idx_synonym_decision_status ON synonym_decision (status);
