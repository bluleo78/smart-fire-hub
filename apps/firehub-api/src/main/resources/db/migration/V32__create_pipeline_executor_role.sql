-- pipeline_executor 역할 생성 + 권한 부여 (멱등성 보장)
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pipeline_executor') THEN
    CREATE ROLE pipeline_executor LOGIN PASSWORD 'pipeline_exec_pwd';
  END IF;

  -- 현재 데이터베이스에 CONNECT 권한 부여 (DB 이름 하드코딩 방지)
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO pipeline_executor', current_database());
END
$$;

-- data 스키마 USAGE 권한 부여
GRANT USAGE ON SCHEMA data TO pipeline_executor;

-- data 스키마의 모든 기존 테이블에 DML 권한 부여
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA data TO pipeline_executor;

-- data 스키마의 모든 미래 테이블에 DML 권한 부여 (새 데이터셋에 필수)
ALTER DEFAULT PRIVILEGES FOR ROLE app IN SCHEMA data
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pipeline_executor;

-- data 스키마의 시퀀스 USAGE 권한 부여 (serial/identity 컬럼용)
GRANT USAGE ON ALL SEQUENCES IN SCHEMA data TO pipeline_executor;
ALTER DEFAULT PRIVILEGES FOR ROLE app IN SCHEMA data
  GRANT USAGE ON SEQUENCES TO pipeline_executor;

-- public 스키마 접근 명시적 거부 (pipeline_executor는 메타데이터를 읽으면 안 됨)
REVOKE ALL ON SCHEMA public FROM pipeline_executor;

-- search_path를 data 스키마만 포함하도록 설정
ALTER ROLE pipeline_executor SET search_path TO data;
