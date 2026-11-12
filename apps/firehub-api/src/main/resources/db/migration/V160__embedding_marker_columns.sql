-- V160: 임베딩 표시 열 — 카탈로그·청크 행이 "어느 공간(차원·모델)으로 임베딩됐는지" 를 부모 행에 적는다(WD-717, ADR 0002 결정 4).
--
-- 왜: 재임베딩 백로그 판정(countMissing/findMissing)과 진행률 분자(countEmbedded)가 지금은 부모 행과 차원 테이블(*_vec_N)을
-- anti-join 한다. 벡터를 다른 저장소(Qdrant 등)로 옮기면 이 조인을 할 수 없으므로, 판정 근거를 플랫폼 DB 의 부모 행으로 옮긴다.
--
-- 의미: 표시 열은 벡터 행의 거울이다. 벡터 행을 쓰거나 지우는 리포지토리 메서드가 같은 트랜잭션에서 함께 채우거나 비운다.
--   embedded_dim   — 벡터가 들어 있는 차원 테이블의 차원(1024·1536). 벡터가 없으면 NULL.
--   embedded_model — 그 벡터의 embedding_model. 벡터가 없으면 NULL.
-- 원문 해시 판정은 넣지 않는다 — 원문이 바뀌면 백로그·진행률이 달라지는 새 동작이라 WD-720(키워드 표시)에서 다룬다.
-- 이름은 V131 이 지운 부모의 embedding / embedding_model 과 겹치지 않게 embedded_* 로 짓는다.
--
-- 백필: 지금 차원 테이블에 있는 벡터를 그대로 옮긴다. V131 이 남긴 '<unknown>' 모델도 그대로 옮겨 계속 백로그로 남게 한다(현재 모델과
-- 일치하지 않는다). 한 부모의 벡터는 차원 테이블 전체에서 최대 1행이다(upsertEmbeddings 의 불변식) — 두 UPDATE 가 같은 행을 두 번 덮지 않는다.
-- Flyway 는 소유자 롤로 돌고 두 테이블에 FORCE RLS 가 없어 전 테넌트 행이 보인다(V131 백필과 같은 전제). 백필 건수는
-- EmbeddingMarkerBackfillTest 가 "표시 열이 찬 행 수 = 벡터 행 수" 로 고정한다.
--
-- Tibero 짝: db/migration-tibero/V160 (MigrationParityTest) — Tibero 에는 벡터 테이블이 없어 열 추가만 한다.
SET LOCAL lock_timeout = '3s';

ALTER TABLE dataset_embedding ADD COLUMN IF NOT EXISTS embedded_dim INTEGER;
ALTER TABLE dataset_embedding ADD COLUMN IF NOT EXISTS embedded_model VARCHAR(100);
ALTER TABLE document_chunk ADD COLUMN IF NOT EXISTS embedded_dim INTEGER;
ALTER TABLE document_chunk ADD COLUMN IF NOT EXISTS embedded_model VARCHAR(100);

UPDATE dataset_embedding de
   SET embedded_dim = 1024, embedded_model = v.embedding_model
  FROM dataset_embedding_vec_1024 v
 WHERE v.dataset_id = de.dataset_id;
UPDATE dataset_embedding de
   SET embedded_dim = 1536, embedded_model = v.embedding_model
  FROM dataset_embedding_vec_1536 v
 WHERE v.dataset_id = de.dataset_id;

UPDATE document_chunk c
   SET embedded_dim = 1024, embedded_model = v.embedding_model
  FROM document_chunk_vec_1024 v
 WHERE v.chunk_id = c.id;
UPDATE document_chunk c
   SET embedded_dim = 1536, embedded_model = v.embedding_model
  FROM document_chunk_vec_1536 v
 WHERE v.chunk_id = c.id;
