-- V160: 임베딩 표시 열 (WD-717) — PG V160 과 짝(MigrationParityTest).
--
-- Tibero 기준선에는 벡터 테이블(*_vec_N)이 없어 채울 값이 없다 — 열만 더한다(전부 NULL = 임베딩 안 됨). 열 정의는 PG 와 같다
-- (TiberoBaselineSchemaLiveTest 가 PG 스키마와 열을 대조한다). 표시 열의 뜻은 PG V160 머리말 참고.
--
-- DDL 이 둘이라 사전 뷰로 열 존재를 확인하는 멱등 블록으로 쓴다(V157 과 같은 모양) — Tibero DDL 은 암묵 커밋이라 앞의 ALTER 뒤 실패하면
-- 고친 뒤 repair → migrate 로 다시 돌릴 때 이미 있는 열은 건너뛴다.
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
   WHERE TABLE_NAME = 'DATASET_EMBEDDING' AND COLUMN_NAME = 'EMBEDDED_DIM';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE dataset_embedding ADD (embedded_dim NUMBER(10), embedded_model VARCHAR2(100))';
  END IF;
  SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
   WHERE TABLE_NAME = 'DOCUMENT_CHUNK' AND COLUMN_NAME = 'EMBEDDED_DIM';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE document_chunk ADD (embedded_dim NUMBER(10), embedded_model VARCHAR2(100))';
  END IF;
END;
/
