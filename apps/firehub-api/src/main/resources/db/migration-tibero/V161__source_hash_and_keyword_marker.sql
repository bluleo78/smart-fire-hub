-- V161: 원문 해시와 키워드 표시 (WD-720) — PG V161 과 짝(MigrationParityTest). 열의 뜻은 PG V161 머리말 참고.
--
-- PG 처럼 백필하지 않는다(해시는 Java 한 곳에서만 계산). V160 과 같은 멱등 블록 — Tibero DDL 은 암묵 커밋이라 중간 실패 뒤
-- repair → migrate 로 다시 돌릴 때 이미 있는 열은 건너뛴다.
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
   WHERE TABLE_NAME = 'DATASET_EMBEDDING' AND COLUMN_NAME = 'SOURCE_HASH';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE dataset_embedding ADD (source_hash VARCHAR2(64), embedded_hash VARCHAR2(64), keyword_marker VARCHAR2(80))';
  END IF;
  SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
   WHERE TABLE_NAME = 'DOCUMENT_CHUNK' AND COLUMN_NAME = 'CONTENT_HASH';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE document_chunk ADD (content_hash VARCHAR2(64), keyword_marker VARCHAR2(80))';
  END IF;
END;
/
