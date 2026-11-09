-- V157: API 연결 인증 방식에 BASIC(아이디·비밀번호) 을 더한다 (WD-681) — PG V157 과 짝(MigrationParityTest).
--
-- 기준선(V155)의 api_connection_auth_type_check 는 API_KEY·BEARER·OAUTH2 만 받는다. 제약을 지우고 BASIC 을 더해 다시 만든다.
-- 데이터는 건드리지 않는다(옛 연결은 이관하지 않는다 — PG 와 같다).
--
-- DDL 이 둘(제약 삭제·추가)이라 1파일 1DDL 규칙 대신 사전 뷰로 존재를 확인하는 멱등 블록으로 쓴다(V156 과 같은 모양) — Tibero DDL 은
-- 암묵 커밋이라 삭제 뒤 추가가 실패하면 제약이 없는 채로 남는데, 이 모양이면 고친 뒤 repair → migrate 로 다시 돌려도 없는 제약은 지우지 않고
-- 새 제약만 만든다.
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM USER_CONSTRAINTS
   WHERE TABLE_NAME = 'API_CONNECTION' AND CONSTRAINT_NAME = 'API_CONNECTION_AUTH_TYPE_CHECK';
  IF n > 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE api_connection DROP CONSTRAINT api_connection_auth_type_check';
  END IF;
  EXECUTE IMMEDIATE 'ALTER TABLE api_connection ADD CONSTRAINT api_connection_auth_type_check'
    || ' CHECK (auth_type IN (''API_KEY'', ''BEARER'', ''OAUTH2'', ''BASIC''))';
END;
/
