-- V138: 테넌트별 데이터셋 저장소 설정(WD-73) — 기본(PostgreSQL)이 아닌 외부 저장소를 쓰는 테넌트만 행을 가진다.
-- 행이 없으면 PostgreSQL(기본 data 스키마)이다. 그래서 backend CHECK 에는 POSTGRESQL 이 없다.
-- 두 테이블 모두 테넌트 경계 "위"의 전역 테이블이라 RLS 를 걸지 않는다:
-- 테넌트 생성은 운영자 컨텍스트(테넌트 미선택)에서 실행되고, 연결 정보는 플랫폼 운영자 소관이다(스펙 §3).
-- 명시 GRANT 도 없다 — V83 의 기본 권한으로 app_tenant 가 접근한다.

CREATE TABLE tenant_data_store (
    tenant_id      BIGINT       PRIMARY KEY REFERENCES tenant(id) ON DELETE CASCADE,
    backend        VARCHAR(16)  NOT NULL,
    host           VARCHAR(255) NOT NULL,
    port           INT          NOT NULL,
    database_name  VARCHAR(128) NOT NULL,
    schema_name    VARCHAR(128) NOT NULL,
    tablespace     VARCHAR(30),
    provision_mode VARCHAR(16)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT tenant_data_store_backend_check CHECK (backend IN ('TIBERO', 'MYSQL')),
    CONSTRAINT tenant_data_store_port_check CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT tenant_data_store_mode_check CHECK (provision_mode IN ('DBA_AUTO', 'SCRIPT')),
    CONSTRAINT tenant_data_store_status_check CHECK (status IN ('PENDING', 'READY'))
);
COMMENT ON TABLE tenant_data_store IS '테넌트별 외부 데이터셋 저장소 설정 — 전역, RLS 미적용. 행이 없으면 PostgreSQL';
COMMENT ON COLUMN tenant_data_store.provision_mode IS 'DBA_AUTO: DBA 계정으로 자동 생성, SCRIPT: 운영자가 스크립트로 수동 생성';
COMMENT ON COLUMN tenant_data_store.status IS 'PENDING: 프로비저닝 전, READY: 사용 가능';

CREATE TABLE tenant_data_store_account (
    tenant_id    BIGINT       NOT NULL REFERENCES tenant_data_store(tenant_id) ON DELETE CASCADE,
    account_role VARCHAR(8)   NOT NULL,
    username     VARCHAR(128) NOT NULL,
    password_enc TEXT         NOT NULL,
    PRIMARY KEY (tenant_id, account_role)
);
COMMENT ON TABLE tenant_data_store_account IS '외부 저장소 접속 계정(OWNER/RO/S1..S10) — 전역, RLS 미적용. 비밀번호는 암호화 저장';
