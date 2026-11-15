-- V165: 테넌트 전용 Qdrant 설정 (WD-742) — PG V165 와 짝(MigrationParityTest). 뜻은 PG V165 머리말 참고.
--
-- tenant_data_store 와 같은 전역 테이블이라 tenant_id DEFAULT·DBMS_RLS 정책을 두지 않는다(V156.1 머리말의 「정책 없이 두는 tenant_id 테이블」).
-- 불리언은 NUMBER(1)(1=참), 시각은 기준선과 같은 UTC TIMESTAMP(WD-260 — TSTZ 금지). 런타임 계정 권한은 Flyway afterMigrate 콜백이 준다.
-- Tibero DDL 은 암묵 커밋이라 다시 돌릴 수 있게 사전 뷰로 존재를 본 뒤 만든다.
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM USER_TABLES WHERE TABLE_NAME = 'TENANT_VECTOR_STORE';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE tenant_vector_store ('
      || ' tenant_id NUMBER(19) NOT NULL,'
      || ' host VARCHAR2(255 CHAR) NOT NULL,'
      || ' grpc_port NUMBER(10) NOT NULL,'
      || ' use_tls NUMBER(1) DEFAULT 0 NOT NULL,'
      || ' api_key_enc VARCHAR2(65532) NOT NULL,'
      || ' created_at TIMESTAMP DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,'
      || ' updated_at TIMESTAMP DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,'
      || ' CONSTRAINT tenant_vector_store_pkey PRIMARY KEY (tenant_id),'
      || ' CONSTRAINT tenant_vector_store_port_check CHECK (grpc_port BETWEEN 1 AND 65535),'
      || ' CONSTRAINT tenant_vector_store_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE)';
  END IF;
END;
/
