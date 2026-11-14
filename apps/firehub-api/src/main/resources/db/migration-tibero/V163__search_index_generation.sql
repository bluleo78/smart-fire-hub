-- V163: 행 검색 색인 세대 (WD-722) — PG V163 과 짝(MigrationParityTest). 열의 뜻은 PG V163 머리말 참고.
--
-- V161 과 같은 멱등 블록 — Tibero DDL 은 암묵 커밋이라 중간 실패 뒤 repair → migrate 로 다시 돌릴 때 이미 있는 열은 건너뛴다.
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
   WHERE TABLE_NAME = 'DATASET_SEARCH_INDEX' AND COLUMN_NAME = 'GENERATION';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE dataset_search_index ADD (generation NUMBER(19) DEFAULT 1 NOT NULL)';
  END IF;
END;
/
