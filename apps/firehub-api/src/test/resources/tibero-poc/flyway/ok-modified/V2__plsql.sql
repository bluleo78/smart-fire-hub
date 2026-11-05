-- WD-488 PoC: PL/SQL 블록(패키지·트리거·멱등 래퍼) — Oracle 파서의 "/" 구분이 Tibero 에서 통하는지 본다.
CREATE OR REPLACE PACKAGE poc_ctx_pkg AS
  PROCEDURE set_tenant(p_tenant IN NUMBER);
END poc_ctx_pkg;
/
CREATE OR REPLACE PACKAGE BODY poc_ctx_pkg AS
  PROCEDURE set_tenant(p_tenant IN NUMBER) IS
  BEGIN
    NULL;
  END set_tenant;
END poc_ctx_pkg;
/
CREATE OR REPLACE TRIGGER trg_poc_item_name
BEFORE INSERT ON poc_item
FOR EACH ROW
BEGIN
  :NEW.name := TRIM(:NEW.name);
END;
/
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM USER_TABLES WHERE TABLE_NAME = 'POC_IDEMPOTENT';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE poc_idempotent (id NUMBER PRIMARY KEY)';
  END IF;
END;
/
-- 체크섬 변경(validate 실패 → repair 시험)
