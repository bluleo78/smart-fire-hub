-- V162.3: Tibero 전용 — 옛 VARCHAR2 열을 지우고 CLOB 열을 sql_text 로 바꾸는 3/3(WD-740). 앞 두 파일은 V162.1(열 추가)·V162.2(복사).
--
-- 단계마다 현재 상태를 보고 해당할 때만 한다(멱등 블록 — 어느 시점에서 다시 돌려도 한 번에 같은 끝 상태로 모인다):
--   1) sql_text 가 VARCHAR2 이고 sql_text_new 가 있다 → 복사가 안 된 행(sql_text_new IS NULL)이 없을 때만 sql_text 를 지운다
--      (있으면 데이터를 잃지 않게 -20740 으로 멈춘다. V162.2 는 이미 성공 이력이라 repair → migrate 로는 다시 돌지
--      않는다 — V162.2 뒤에 옛 버전 pod 가 넣은 행 등이 원인이므로, 그 테이블에
--      UPDATE <table> SET sql_text_new = sql_text WHERE sql_text_new IS NULL; COMMIT; 을 손으로 실행한 뒤 migrate).
--   2) sql_text_new 가 있고 sql_text 가 없다 → sql_text_new 를 sql_text 로 이름을 바꾼다.
--   3) sql_text 가 CLOB 이고 NULL 허용이다 → NOT NULL 을 건다(기존 행은 모두 값이 있다).
-- Tibero DDL 은 암묵 커밋이라 중간에 실패하면 원인을 고친 뒤 repair → migrate 로 다시 돌린다(docs/adr/0001-platform-db-tibero.md 「실패 복구」,
-- repair 는 이력만 정리하고 반쯤 적용된 객체는 남기므로 이 블록이 남은 상태를 보고 이어 간다).
-- RLS·PG 짝 없음은 V162.1 머리말 참고.
DECLARE
  n NUMBER;
  missing NUMBER;

  PROCEDURE swap_column(p_table VARCHAR2) IS
  BEGIN
    -- 1) 옛 VARCHAR2 열 삭제(복사 확인 뒤).
    SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = UPPER(p_table) AND COLUMN_NAME = 'SQL_TEXT' AND DATA_TYPE <> 'CLOB';
    IF n > 0 THEN
      SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
       WHERE TABLE_NAME = UPPER(p_table) AND COLUMN_NAME = 'SQL_TEXT_NEW';
      IF n > 0 THEN
        EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM ' || p_table || ' WHERE sql_text_new IS NULL' INTO missing;
        IF missing > 0 THEN
          RAISE_APPLICATION_ERROR(-20740, p_table || ': sql_text_new 가 비어 있는 행 ' || missing || '건 — UPDATE ' || p_table || ' SET sql_text_new = sql_text WHERE sql_text_new IS NULL 실행 후 migrate');
        END IF;
        EXECUTE IMMEDIATE 'ALTER TABLE ' || p_table || ' DROP COLUMN sql_text';
      END IF;
    END IF;
    -- 2) 이름 되돌리기.
    SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = UPPER(p_table) AND COLUMN_NAME = 'SQL_TEXT_NEW';
    IF n > 0 THEN
      SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
       WHERE TABLE_NAME = UPPER(p_table) AND COLUMN_NAME = 'SQL_TEXT';
      IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE ' || p_table || ' RENAME COLUMN sql_text_new TO sql_text';
      END IF;
    END IF;
    -- 3) NOT NULL 복원.
    SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = UPPER(p_table) AND COLUMN_NAME = 'SQL_TEXT' AND DATA_TYPE = 'CLOB' AND NULLABLE = 'Y';
    IF n > 0 THEN
      EXECUTE IMMEDIATE 'ALTER TABLE ' || p_table || ' MODIFY (sql_text NOT NULL)';
    END IF;
  END;
BEGIN
  swap_column('analytics_query_run');
  swap_column('query_history');
  swap_column('saved_query');
END;
/
