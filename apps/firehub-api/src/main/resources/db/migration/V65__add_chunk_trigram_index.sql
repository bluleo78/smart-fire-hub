-- 문서 청크 본문 키워드(트라이그램) 검색 인프라 추가.
-- 왜: 순수 벡터 검색은 고유명사·정확한 용어·코드/숫자 식별자에 약하다.
--     pg_trgm 트라이그램 검색을 더해 하이브리드(RRF)로 recall 을 높인다.
-- pg_trgm 은 PostgreSQL 기본 contrib 확장이라 Docker 이미지 재빌드 없이 사용 가능하다.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- content 트라이그램 GIN 인덱스: word_similarity 키워드 검색을 가속한다.
CREATE INDEX IF NOT EXISTS idx_document_chunk_content_trgm
  ON document_chunk USING gin (content gin_trgm_ops);
