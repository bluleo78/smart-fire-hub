-- V164: 키워드(pg_trgm) 검색 색인을 GIN 에서 GiST(gist_trgm_ops)로 바꾼다 (WD-725).
--
-- 왜: 키워드 검색은 임계값을 0.1 로 낮춘 `col %> 질의` 로 거르고 word_similarity 내림차순으로 상위 k 개를 자른다. 임계값 0.1 이면
-- 거의 모든 행이 조건을 통과해 플래너가 GIN 을 버리고 seq scan + 행마다 word_similarity 계산을 했다(만 건에 질의당 0.4~4초).
-- GiST 는 거리 연산자 `col <->> 질의`(= 1 - word_similarity)로 거리 순 색인 스캔(KNN)을 해 상위 k 개만 읽는다 — 같은 조건의
-- 2만 행 실측에서 356ms → 5ms. 순위는 거리 = 1 - word_similarity 라 지금과 같다(질의 쪽 ORDER BY 를 거리로 바꾼다).
--
-- GIN 은 지운다 — 거르기(`%>`)도 GiST 가 맡아 GIN 은 쓰기 비용만 남는다. 이름은 그대로 둔다(jOOQ 생성 코드의 색인 이름과 같게).
-- 테이블 크기만큼 색인을 새로 만드느라 이 마이그레이션 동안 두 테이블 쓰기가 잠긴다(읽기는 된다).
--
-- 데이터셋 행 검색 색인 테이블(데이터 저장소의 row_search_idx_*)은 동적이라 여기서 바꾸지 않는다 — 새로 만드는 테이블은 GiST 로 만들고,
-- 옛 테이블은 행 검색 동기화가 처음 돌 때 한 번 GiST 로 올린다(PgRowSearchIndex.ensureKeywordIndex).
DROP INDEX IF EXISTS idx_dataset_embedding_source_trgm;
CREATE INDEX IF NOT EXISTS idx_dataset_embedding_source_trgm
    ON dataset_embedding USING gist (source_text gist_trgm_ops);

DROP INDEX IF EXISTS idx_document_chunk_content_trgm;
CREATE INDEX IF NOT EXISTS idx_document_chunk_content_trgm
    ON document_chunk USING gist (content gist_trgm_ops);
