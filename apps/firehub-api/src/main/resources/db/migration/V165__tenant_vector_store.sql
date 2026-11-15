-- V165: 테넌트 전용 Qdrant 설정(WD-742) — 행 검색 색인을 설치본 공유 Qdrant 가 아니라 테넌트 전용 인스턴스에 두는 테넌트만 행을 가진다.
-- 행이 없으면 공유 Qdrant(테넌트별 컬렉션 + 그 컬렉션만 허용한 토큰, WD-722)다.
--
-- 왜 테넌트별로 나누나: 행 검색 원본(데이터셋 행)이 테넌트 전용 DB 인스턴스(tenant_data_store)에 있는 고객은 색인도 자기 인프라에 두길 원한다.
-- 카탈로그·문서 청크는 원본이 공유 테이블+RLS 라 공유 컬렉션 그대로다(WD-720).
--
-- tenant_data_store 와 같은 전역 설정 테이블이라 RLS 를 걸지 않는다(TenantSchemaConformanceTest RLS_DISABLED_ALLOWLIST) — 테넌트 생성은
-- 운영자 컨텍스트(테넌트 미선택)에서 실행되고, 행 검색 배경 작업은 테넌트마다 대상 인스턴스를 해석한다. 명시 GRANT 도 없다(V83 기본 권한).
-- api_key_enc: 그 인스턴스의 API 키(EncryptionService 암호문). 토큰(JWT) 서명과 컬렉션 관리에 쓴다.
CREATE TABLE tenant_vector_store (
    tenant_id   BIGINT       PRIMARY KEY REFERENCES tenant(id) ON DELETE CASCADE,
    host        VARCHAR(255) NOT NULL,
    grpc_port   INT          NOT NULL,
    use_tls     BOOLEAN      NOT NULL DEFAULT FALSE,
    api_key_enc TEXT         NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT tenant_vector_store_port_check CHECK (grpc_port BETWEEN 1 AND 65535)
);
COMMENT ON TABLE tenant_vector_store IS '테넌트 전용 Qdrant(행 검색 색인) — 전역, RLS 미적용. 행이 없으면 공유 Qdrant';
