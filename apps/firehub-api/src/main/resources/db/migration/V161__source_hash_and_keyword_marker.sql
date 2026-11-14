-- V161: 원문 해시와 키워드 표시 (WD-720, ADR 0002 결정 4 ③)
--
-- 왜: V160 의 공간 표시(embedded_dim·embedded_model)는 "어느 공간으로 임베딩됐는지" 만 안다. 원문이 바뀌어도 표시가 그대로라
-- 비동기 재임베딩이 실패하면 옛 벡터가 영영 남는다. 그래서 "임베딩한 원문" 을 해시로 적어 원문이 바뀌면 백로그로 잡는다(새 동작).
-- 또 Qdrant 설치본은 키워드 sparse 벡터를 별도 저장소에 따로 써야 하므로 "키워드로 색인한 원문" 표시가 하나 더 필요하다.
--
--   dataset_embedding.source_hash    — 지금 source_text 의 SHA-256(hex). upsertSourceText 가 원문과 같은 문장에서 쓴다.
--   dataset_embedding.embedded_hash  — 벡터를 만든 원문의 해시. 벡터 쓰기 뒤 "그 해시 = 지금 source_hash" 일 때만 채운다.
--   dataset_embedding.keyword_marker — 키워드 sparse 를 만든 "규칙 버전:원문 해시"(Qdrant 설치본만 쓴다).
--   document_chunk.content_hash      — 청크 본문의 SHA-256(hex). 청크는 본문이 바뀌지 않아 dense 쪽 해시는 두지 않는다.
--   document_chunk.keyword_marker    — dataset_embedding.keyword_marker 와 같은 뜻.
--
-- 해시는 Java 한 곳(SourceHash)에서만 계산한다 — SQL 로 계산하면 Tibero 와 계산 위치가 갈라진다. 그래서 백필하지 않는다.
-- 해시가 NULL 인 기존 행은 예전처럼 공간만으로 판정하고(판정식이 NULL 을 "모름" 으로 다룬다), 원문을 고치거나 재임베딩할 때 채워진다.
--
-- Tibero 짝: db/migration-tibero/V161 (MigrationParityTest).
SET LOCAL lock_timeout = '3s';

ALTER TABLE dataset_embedding ADD COLUMN IF NOT EXISTS source_hash VARCHAR(64);
ALTER TABLE dataset_embedding ADD COLUMN IF NOT EXISTS embedded_hash VARCHAR(64);
ALTER TABLE dataset_embedding ADD COLUMN IF NOT EXISTS keyword_marker VARCHAR(80);
ALTER TABLE document_chunk ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE document_chunk ADD COLUMN IF NOT EXISTS keyword_marker VARCHAR(80);
