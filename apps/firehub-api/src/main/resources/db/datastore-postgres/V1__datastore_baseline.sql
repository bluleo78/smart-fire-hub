-- 데이터 저장소(PG) 기준선 V1 — 분리 모드(firehub.data-store.postgres.separate=true) 전용 (WD-494 스펙 4절).
--
-- 무엇: PG 설치본에서는 플랫폼 Flyway(db/migration)가 데이터 저장소 객체(사용자 데이터셋 테이블이 사는 data 스키마,
-- 확장, 런타임·파이프라인 롤과 그 권한, 증분 처리 함수)도 함께 만든다. 분리 모드에서는 데이터 저장소가 따로 둔 PG DB 라
-- 이 폴더(db/datastore-postgres)의 두 번째 Flyway(DataStoreFlywayConfig)가 데이터 저장소 소유자 연결로 만든다.
-- 기준: 2026-11-06 현재 PG 최종 상태(V155) 에서 데이터 저장소 객체만. 새로 설치하는 빈 DB 만 지원한다(스펙 4절).
--
-- 이후 규칙: PG 마이그레이션이 데이터 저장소 객체(스키마·확장·롤·권한·fh_* 함수)를 바꾸면 이 폴더에도 같은 변경을 둔다.
-- DataStoreBaselineParityTest 가 두 쪽(플랫폼 마이그레이션 전부 vs 이 기준선만, 서로 다른 서버)을 대조한다.
--
-- 근거 목록(V2~V155 훑음 — data 스키마·롤·기본 권한·확장·fh_* 를 건드린 것):
--   V2   CREATE SCHEMA data                                                → 옮김
--   V29  CREATE EXTENSION postgis                                          → 옮김
--   V32  pipeline_executor 롤·CONNECT·data USAGE·기본 권한(FOR ROLE 소유자)·public 회수·search_path → 옮김
--        (search_path 는 V111 이 지적한 대로 IN DATABASE 로 한정 — 유효 값은 같다)
--   V56  app 의 SUPERUSER 제거 시도                                          → 옮기지 않음(플랫폼 소유자 롤 정책, 데이터 저장소 객체 아님)
--   V60  CREATE EXTENSION vector                                           → 옮김
--   V65·V67 CREATE EXTENSION pg_trgm(·vector)                              → 옮김
--   V83  app_tenant 롤·CONNECT·소유자 멤버십·data USAGE,CREATE·기본 권한(FOR ROLE app_tenant → app_tenant·pipeline_executor) → 옮김
--        (public 스키마 권한·기본 권한은 플랫폼 메타데이터용이라 옮기지 않음. ALL TABLES/SEQUENCES IN SCHEMA data GRANT 는
--         빈 DB 에서 대상이 없어 생략)
--   V86  data 기존 테이블 소유권 이전                                         → 빈 DB 라 대상 없음(생략)
--   V111 pipeline_executor_t1 시드 롤과 그 권한                               → 옮기지 않음(테넌트 롤은 TenantPipelineRoleProvisioner 가 만든다)
--   V123 fh_touch_updated_at·fh_incremental_cursor_candidate·REVOKE/GRANT EXECUTE → 본문 그대로 옮김
--   V124 기존 데이터셋 테이블 _updated_at·트리거 백필                         → 빈 DB 라 대상 없음(생략)
--   V130 행 검색 색인 상태 테이블(public, 메타데이터)                         → 옮기지 않음(색인 테이블은 런타임 DDL)
--   V137 Python 읽기 슬롯 롤(pipeline_py_t{id}_s{k})                         → 전부 테넌트별이라 테넌트 무관 부분 없음(옮기지 않음)
--   그 밖의 GRANT … TO app_tenant(V85·V95·V98·V100·V106·V113·V121·V133·V149)는 public 메타데이터 테이블·함수 대상 → 옮기지 않음
--
-- 롤은 DB 가 아니라 서버 전체 객체다. 같은 서버에 다른 DB(예: 플랫폼 PG)가 이미 롤을 만들었어도 깨지지 않게 롤 생성은
-- IF NOT EXISTS DO 블록(V32·V83 모양)으로 쓰고, 이미 있는 롤의 비밀번호·속성은 건드리지 않는다.
-- 소유자 롤 이름은 박지 않는다(CURRENT_USER) — 데이터 저장소 소유자 계정은 설치마다 다를 수 있다.

-- ── 1) data 스키마 (V2) ─────────────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS data;

-- ── 2) 확장 (V29·V60·V65·V67) — 공간(GEOMETRY)·임베딩·행 검색은 데이터 저장소 기능이다 ─────────────
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ── 3) 롤 (V32·V83) ─────────────────────────────────────────────────────────────────────
-- 초기 비밀번호는 추측 불가한 임의값이다(V137 선례 — V32·V83 의 공개 리터럴은 따르지 않는다). 실제 비밀번호는 같은 기동에서
-- 이 Flyway 의 AFTER_MIGRATE 동기화(DataStoreRolePasswordSync)가 설정값으로 맞춘 뒤에야 데이터 저장소 풀이 접속한다.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_tenant') THEN
    EXECUTE format(
      'CREATE ROLE app_tenant LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS',
      md5(random()::text || clock_timestamp()::text));
  END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO app_tenant', current_database());

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pipeline_executor') THEN
    EXECUTE format(
      'CREATE ROLE pipeline_executor LOGIN PASSWORD %L',
      md5(random()::text || clock_timestamp()::text));
  END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO pipeline_executor', current_database());
END
$$;

-- 소유자가 app_tenant 의 멤버여야 아래 "ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant" 가 허용되고, app_tenant 가 런타임에
-- 만든 테이블을 소유자 연결(TenantSchemaProvisioner 등)이 다룰 수 있다. V83 과 같은 이유로 반드시 기본 권한보다 앞에 둔다.
GRANT app_tenant TO CURRENT_USER;

-- ── 4) data 스키마 권한 (V32·V83) ───────────────────────────────────────────────────────
-- 런타임이 data 스키마에 CREATE TABLE·인덱스·_tmp 스왑 DDL 을 하므로 app_tenant 는 CREATE 까지 필요하다(V83).
GRANT USAGE, CREATE ON SCHEMA data TO app_tenant;
GRANT USAGE ON SCHEMA data TO pipeline_executor;

-- 기본 권한 — 실제로 데이터 테이블을 만드는 롤은 런타임 app_tenant 다(V83·V111 주석). 그래서 유효한 규칙은 FOR ROLE app_tenant 쪽이다.
ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant IN SCHEMA data
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_tenant;
ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant IN SCHEMA data
  GRANT USAGE, SELECT ON SEQUENCES TO app_tenant;
ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant IN SCHEMA data
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pipeline_executor;
ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant IN SCHEMA data
  GRANT USAGE ON SEQUENCES TO pipeline_executor;
-- V32 의 소유자(FOR ROLE app) 기본 권한도 같은 모양으로 둔다(FOR ROLE 생략 = 이 마이그레이션을 실행하는 소유자 롤).
-- 소유자가 data 에 테이블을 만들 일은 지금 없지만, PG 설치본과 같은 상태를 두어 대조 테스트가 차이 없이 비교하게 한다.
ALTER DEFAULT PRIVILEGES IN SCHEMA data
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pipeline_executor;
ALTER DEFAULT PRIVILEGES IN SCHEMA data
  GRANT USAGE ON SEQUENCES TO pipeline_executor;

-- public 스키마 접근 명시적 거부 (V32 — 파이프라인 실행 롤은 메타데이터를 읽으면 안 된다)
REVOKE ALL ON SCHEMA public FROM pipeline_executor;

-- search_path 는 IN DATABASE 로 한정한다(V111 주석: 한정하지 않으면 setdatabase=0 으로 서버의 모든 DB 에 걸린다).
DO $$
BEGIN
  EXECUTE format(
    'ALTER ROLE pipeline_executor IN DATABASE %I SET search_path TO %I',
    current_database(), 'data');
END
$$;

-- ── 5) 증분 처리 함수 (V123 — 본문 그대로) ───────────────────────────────────────────────
-- 데이터셋 행 변경 시각(_updated_at) 트리거 함수와 책갈피 후보값 함수. 둘 다 데이터 테이블과 같은 DB 에 있어야 한다.
-- ⚠ fh_incremental_cursor_candidate 는 SECURITY DEFINER 라 소유자(이 마이그레이션을 실행한 롤)가 superuser 이거나
--   pg_read_all_stats 멤버여야 다른 세션의 xact_start 를 본다 — 아니면 후보값이 조용히 clock_timestamp() 로 내려앉는다(V123 주석).
CREATE OR REPLACE FUNCTION public.fh_touch_updated_at() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog
AS $$
BEGIN
  NEW._updated_at := now();
  RETURN NEW;
END
$$;


CREATE OR REPLACE FUNCTION public.fh_incremental_cursor_candidate() RETURNS timestamptz
  LANGUAGE plpgsql VOLATILE SECURITY DEFINER
  SET search_path = pg_catalog
AS $$
DECLARE
  t0 timestamptz;
  oldest timestamptz;
BEGIN
  t0 := clock_timestamp();
  SELECT min(xact_start) INTO oldest
  FROM pg_stat_activity
  WHERE datname = current_database()
    AND pid <> pg_backend_pid()
    AND backend_type = 'client backend'
    AND xact_start IS NOT NULL;
  RETURN LEAST(t0, COALESCE(oldest, t0));
END
$$;
REVOKE ALL ON FUNCTION public.fh_incremental_cursor_candidate() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fh_incremental_cursor_candidate() TO app_tenant;
