-- V162.1: Tibero 전용 — SQL 본문 열 세 개(analytics_query_run·query_history·saved_query 의 sql_text)를 CLOB 로 바꾸는 1/3, 새 CLOB 열 추가(WD-740).
-- (2/3 V162.2 복사, 3/3 V162.3 교체 — 한 일이 DDL·DML·DDL 로 나뉘어 파일을 셋으로 쪼갰다: ADR 0001 「Tibero 마이그레이션 작성 규칙」.)
--
-- 왜: 기준선은 PG text 를 VARCHAR2(65532) 바이트로 옮겼고, 사용자가 쓰는 SQL 본문은 길이를 통제할 수 없다.
-- 오류 메시지처럼 잘라 저장하면 SQL 이 깨져 실행·재현이 틀려지므로(WD-728 은 SQL 본문에 잘림 금지를 적용) 한도 자체를 없앤다.
-- 실측: Tibero 는 VARCHAR2 열을 CLOB 로 ALTER TABLE ... MODIFY 할 수 없다(JDBC-7237 "cannot be modified").
-- 그래서 새 CLOB 열을 더해 값을 복사하고 옛 열을 지운 뒤 이름을 되돌린다(열 순서만 뒤로 바뀌며 코드는 열 이름으로만 접근한다).
-- PG 짝 마이그레이션은 없다(PG text 는 한도가 없다) — MigrationParityTest.TIBERO_ONLY 에 올려 둔다.
-- RLS: 세 테이블의 FH_TENANT_POLICY 는 tenant_id 술어라 열 종류와 무관하다. Flyway 는 소유자 세션이라 정책 함수가 면제하므로
--      V162.2 의 복사 UPDATE 가 모든 행을 본다.
--
-- 멱등 블록(V160·V161 과 같은 방식) — Tibero DDL 은 암묵 커밋이라 중간에 실패하면 원인을 고친 뒤 repair → migrate 로 다시 돌린다
-- (docs/adr/0001-platform-db-tibero.md 「실패 복구」). 이미 새 열이 있거나 sql_text 가 이미 CLOB 이면 건너뛴다.
DECLARE
  n NUMBER;

  PROCEDURE add_new_column(p_table VARCHAR2) IS
  BEGIN
    SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
     WHERE TABLE_NAME = UPPER(p_table) AND COLUMN_NAME = 'SQL_TEXT_NEW';
    IF n = 0 THEN
      SELECT COUNT(*) INTO n FROM USER_TAB_COLUMNS
       WHERE TABLE_NAME = UPPER(p_table) AND COLUMN_NAME = 'SQL_TEXT' AND DATA_TYPE <> 'CLOB';
      IF n > 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE ' || p_table || ' ADD (sql_text_new CLOB)';
      END IF;
    END IF;
  END;
BEGIN
  add_new_column('analytics_query_run');
  add_new_column('query_history');
  add_new_column('saved_query');
END;
/
