-- V156: 로그인 IP 기준 제한용 실패 기록 테이블 (WD-578) — PG V156 과 짝(MigrationParityTest).
--
-- DDL 이 둘(테이블·인덱스)이라 1파일 1DDL 규칙 대신 사전 뷰로 존재를 확인하는 멱등 블록으로 쓴다 — Tibero DDL 은 암묵 커밋이라
-- 중간에 실패하면 반쯤 적용된 객체가 남는데, 이 모양이면 고친 뒤 repair → migrate 로 다시 돌려도 이미 만든 객체를 건너뛴다.
-- 타입 대응은 V155 머리말과 같다(varchar(n) → VARCHAR2(n CHAR), timestamp → TIMESTAMP). 주석(COMMENT)은 V155 와 같이 두지 않는다.
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM USER_TABLES WHERE TABLE_NAME = 'LOGIN_IP_FAILURES';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE login_ip_failures ('
      || ' ip_key VARCHAR2(64 CHAR) NOT NULL,'
      || ' username VARCHAR2(255 CHAR) NOT NULL,'
      || ' expires_at TIMESTAMP NOT NULL,'
      || ' CONSTRAINT login_ip_failures_pkey PRIMARY KEY (ip_key, username))';
  END IF;
  SELECT COUNT(*) INTO n FROM USER_INDEXES WHERE INDEX_NAME = 'IDX_LOGIN_IP_FAILURES_EXPIRES_AT';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE INDEX idx_login_ip_failures_expires_at ON login_ip_failures (expires_at)';
  END IF;
END;
/
