-- V162: 벡터 아웃박스와 순번 시퀀스 (WD-721) — PG V162 와 짝(MigrationParityTest). 열과 순번의 뜻은 PG V162 머리말 참고.
--
-- 테넌트 테이블이라 V156.1 과 같은 두 가지를 이 파일에서 함께 한다(ADR 0001 결정 7).
--   * tenant_id DEFAULT 를 컨텍스트 테넌트로(PG 의 DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint 대응)
--   * DBMS_RLS 정책 FH_TENANT_POLICY(네 문장·update_check·동적) — TiberoTenantPolicyCatalogLiveTest 가 정책 수와 모양을 고정한다.
-- 런타임 계정 권한(테이블 DML·시퀀스 SELECT)은 Flyway afterMigrate 콜백(TiberoRuntimeGrantCallback)이 준다.
--
-- Tibero DDL 은 암묵 커밋이라 중간 실패 뒤 repair → migrate 로 다시 돌릴 수 있게 사전 뷰로 존재를 본 뒤 만드는 멱등 블록으로 쓴다.
-- created_at 은 TSTZ 를 쓰지 않는다(WD-260) — 기준선과 같은 UTC TIMESTAMP 다.
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM USER_SEQUENCES WHERE SEQUENCE_NAME = 'VECTOR_SEQ';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE SEQUENCE vector_seq START WITH 1 INCREMENT BY 1 NOCACHE';
  END IF;

  SELECT COUNT(*) INTO n FROM USER_TABLES WHERE TABLE_NAME = 'VECTOR_OUTBOX';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE vector_outbox ('
      || ' seq NUMBER(19) NOT NULL,'
      || ' tenant_id NUMBER(19) NOT NULL,'
      || ' kind VARCHAR2(40) NOT NULL,'
      || ' target_id NUMBER(19),'
      || ' keep_dim NUMBER(10),'
      || ' attempts NUMBER(10) DEFAULT 0 NOT NULL,'
      || ' last_error VARCHAR2(500),'
      || ' created_at TIMESTAMP DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,'
      || ' CONSTRAINT vector_outbox_pkey PRIMARY KEY (seq),'
      || ' CONSTRAINT vector_outbox_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE)';
  END IF;

  SELECT COUNT(*) INTO n FROM USER_INDEXES WHERE INDEX_NAME = 'IDX_VECTOR_OUTBOX_TENANT_SEQ';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE INDEX idx_vector_outbox_tenant_seq ON vector_outbox (tenant_id, seq)';
  END IF;

END;
/

-- tenant_id DEFAULT 와 정책 — V156.1 의 set_default·add_policy 와 같은 모양의 지역 프로시저로 부른다. TiberoTenantPolicyParityTest 가
-- 이 호출 모양(set_default('<테이블>')·add_policy('<테이블>', ...))을 읽어 PG 테넌트 테이블과 대조한다. 같은 DEFAULT 로 다시 MODIFY 해도
-- 오류가 없고 정책은 사전 뷰로 존재를 보므로 다시 돌려도 안전하다.
DECLARE
  PROCEDURE set_default(p_table VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE ' || p_table
      || ' MODIFY (tenant_id DEFAULT SYS_CONTEXT(''${tenantContext}'',''TENANT_ID''))';
  END set_default;

  PROCEDURE add_policy(p_table VARCHAR2, p_policy VARCHAR2, p_function VARCHAR2) IS
    v_count NUMBER;
  BEGIN
    SELECT COUNT(*) INTO v_count
      FROM USER_POLICIES
     WHERE OBJECT_NAME = p_table
       AND POLICY_NAME = p_policy;
    IF v_count = 0 THEN
      DBMS_RLS.ADD_POLICY(
        object_schema   => '${ownerSchema}',
        object_name     => p_table,
        policy_name     => p_policy,
        function_schema => '${ownerSchema}',
        policy_function => p_function,
        statement_types => 'SELECT,INSERT,UPDATE,DELETE',
        update_check    => TRUE,
        static_policy   => FALSE);
    END IF;
  END add_policy;
BEGIN
  set_default('vector_outbox');
  add_policy('VECTOR_OUTBOX', 'VECTOR_OUTBOX_TENANT_ISOLATION', 'FH_TENANT_POLICY');
END;
/
