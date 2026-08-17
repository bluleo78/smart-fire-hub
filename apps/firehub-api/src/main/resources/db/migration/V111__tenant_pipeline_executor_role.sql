-- V111: 테넌트 1 전용 파이프라인 실행 롤(pipeline_executor_t1).
--
-- 이 마이그레이션은 P3-b1(테넌트별 executor 롤 밴드)의 첫 조각이다. 물리 스키마는 아직 data
-- 하나뿐이고(P3-b2 가 리네임을 켠다), 이 파일은 그 앞에서 "테넌트별로 다른 DB 자격증명으로
-- 접속한다"는 기계장치만 먼저 만든다.
--
-- ⚠ 기존 pipeline_executor 롤은 이 마이그레이션에서 절대 건드리지 않는다(ALTER 도, REVOKE 도).
--   dev·prod 의 현재 파이프라인 실행 경로가 그 롤로 붙어 있고, 그 롤의 은퇴는 P3-b2 에서 물리
--   스키마 리네임과 같은 커밋으로 이뤄진다(R5). 여기서 손대면 진행 중인 실행 경로가 즉시 깨진다.
--
-- 신규 테넌트(2, 3, ...) 를 위한 같은 형태의 롤 생성은 이 마이그레이션에 넣지 않는다 — 테넌트
-- 프로비저닝은 SUPERUSER 소유 SECURITY DEFINER 함수(app_tenant 가 EXECUTE 권한을 가짐)에 넣으면
-- 안 되므로(권한 상승 경로가 열린다), 운영자가 실행하는 문서화된 절차로 간다(R6, #383).

-- 롤 생성은 멱등: 이미 있으면 건너뛴다. 평문 기본 비밀번호는 V32 의 pipeline_executor 선례를
-- 그대로 따른 것으로, 실제 비밀번호는 RolePasswordSyncCallback(T2)이 애플리케이션 기동 시마다
-- TenantPipelineRole.password(1, secret) 로 파생한 값으로 즉시 덮어쓴다. 즉 여기 적힌 문자열은
-- "롤이 처음 생성되는 그 짧은 순간"에만 유효하고, 실제 운영 비밀번호가 아니다.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pipeline_executor_t1') THEN
    CREATE ROLE pipeline_executor_t1 LOGIN PASSWORD 'pipeline_exec_pwd';
  END IF;

  EXECUTE format('GRANT CONNECT ON DATABASE %I TO pipeline_executor_t1', current_database());
END
$$;

-- data 스키마 USAGE 권한 부여 (V32 의 pipeline_executor 정책 계승 — data 는 아직 하나뿐이다, R8)
GRANT USAGE ON SCHEMA data TO pipeline_executor_t1;

-- data 스키마의 모든 기존 테이블/시퀀스에 DML·USAGE 권한 부여
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA data TO pipeline_executor_t1;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA data TO pipeline_executor_t1;

-- data 스키마의 모든 미래 테이블/시퀀스에도 자동 적용 — 단 FOR ROLE app_tenant 로 건다.
-- V83:43-48 이 이미 겪은 함정: 런타임 DDL 주체(신규 데이터셋 테이블을 실제로 만드는 롤)는 app 이
-- 아니라 app_tenant 다. ALTER DEFAULT PRIVILEGES 는 "그 롤이 만드는 객체"에 적용되는 규칙이므로,
-- FOR ROLE app 으로 걸면 app_tenant 가 만드는 신규 테이블에는 적용되지 않아 새 데이터셋이 조용히
-- pipeline_executor_t1 에게 보이지 않게 된다.
ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant IN SCHEMA data
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pipeline_executor_t1;
ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant IN SCHEMA data
  GRANT USAGE ON SEQUENCES TO pipeline_executor_t1;

-- public 스키마 접근 명시적 거부 (V32 정책 계승 — 파이프라인 실행 롤은 메타데이터를 읽으면 안 됨)
REVOKE ALL ON SCHEMA public FROM pipeline_executor_t1;

-- search_path 는 반드시 IN DATABASE 로 한정한다. current_database() 를 DO 블록 + format 으로
-- 동적으로 얻어서 EXECUTE 한다.
--
-- 왜 이게 중요한가: ALTER ROLE ... SET search_path 를 DB 한정 없이 실행하면
-- pg_db_role_setting.setdatabase 가 0(= 전 DB) 으로 기록되어, 이 클러스터에 존재하는 다른 모든
-- DB 에도 같은 search_path 가 적용된다. 기존 pipeline_executor 롤이 정확히 이 상태다(V32:32,
-- 무한정 ALTER ROLE) — 그리고 그 실수가 P3-b2 가 "리네임 전에 반드시 이 배관을 먼저 놓아야
-- 한다"고 게이트를 건 이유 중 하나다. 여기서 같은 실수를 반복하지 않는다.
DO $$
BEGIN
  EXECUTE format(
    'ALTER ROLE pipeline_executor_t1 IN DATABASE %I SET search_path TO %I',
    current_database(), 'data');
END
$$;
