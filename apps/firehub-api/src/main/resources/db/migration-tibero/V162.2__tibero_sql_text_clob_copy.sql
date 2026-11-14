-- V162.2: Tibero 전용 — sql_text 를 새 CLOB 열 sql_text_new 로 복사하는 2/3(WD-740). DML 만 있다(DDL 과 같은 파일에 섞지 않는 규칙).
--
-- 옛 열 sql_text 는 모두 NOT NULL 이었으므로 복사 뒤 빈 값이 없다. RLS: Flyway 소유자 세션은 FH_TENANT_POLICY 가 면제해 모든 행을 본다.
-- 열 존재 여부를 미리 봐야 하는 가드(두 열이 다 있을 때만 복사)라 UPDATE 를 EXECUTE IMMEDIATE 로 싼 PL/SQL 블록이다 — 안의 문장은
-- UPDATE 뿐이고 DDL 은 없다. WHERE sql_text_new IS NULL 이라 다시 돌려도 이미 복사한 행은 건드리지 않아 결과가 같다.
DECLARE
  n NUMBER;

  PROCEDURE copy_text(p_table VARCHAR2) IS
  BEGIN
    SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = UPPER(p_table) AND COLUMN_NAME IN ('SQL_TEXT', 'SQL_TEXT_NEW');
    IF n = 2 THEN
      EXECUTE IMMEDIATE 'UPDATE ' || p_table || ' SET sql_text_new = sql_text WHERE sql_text_new IS NULL';
    END IF;
  END;
BEGIN
  copy_text('analytics_query_run');
  copy_text('query_history');
  copy_text('saved_query');
END;
/
