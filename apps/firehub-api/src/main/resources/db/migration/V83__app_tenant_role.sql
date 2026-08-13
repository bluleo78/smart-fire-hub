-- V83: RLS 강제를 위한 비특권 런타임 롤. Flyway 는 소유자 롤(app)로 실행되므로 CREATE ROLE/GRANT 가 가능하다.
-- 왜 필요한가: RLS 정책은 테이블 소유자에게 적용되지 않는다. 런타임이 소유자(app)로 접속하는 한
-- 어떤 정책을 걸어도 전부 우회되므로, 비소유·NOBYPASSRLS 롤로 트래픽을 분리해야 한다.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_tenant') THEN
    CREATE ROLE app_tenant LOGIN PASSWORD 'app_tenant'
      NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
  END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO app_tenant', current_database());
END $$;

-- 소유권이 app_tenant 로 옮겨간 객체를 이후 마이그레이션(app)이 ALTER 할 수 있도록 멤버십을 먼저 부여한다.
-- 순서가 중요하다: 아래 "ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant" 는 실행 롤이 app_tenant 의
-- 멤버이거나 superuser 일 때만 허용된다. 로컬 컨테이너의 app 롤은 superuser 라 순서를 안 지켜도
-- 통과하지만, 매니지드 Postgres 등 app 이 일반 소유자 롤인 환경에서는 GRANT 가 뒤에 있으면
-- CREATE ROLE 직후에서 마이그레이션이 실패해 롤이 반쪽만 만들어진 채 남는다. 절대 뒤로 옮기지 말 것.
GRANT app_tenant TO app;

-- 1) public 스키마 — 도메인 메타데이터. DDL 권한은 주지 않는다(마이그레이션은 소유자 롤이 한다).
GRANT USAGE ON SCHEMA public TO app_tenant;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES    IN SCHEMA public TO app_tenant;
GRANT USAGE, SELECT                  ON ALL SEQUENCES IN SCHEMA public TO app_tenant;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_tenant;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO app_tenant;

-- 2) data 스키마 — 데이터셋 실제 테이블.
--    DataTableService 가 "런타임에" CREATE TABLE / 인덱스 DDL / _tmp 스테이징 스왑을 실행하므로
--    여기서는 USAGE 만으로 부족하고 CREATE 가 필요하다. (public 과 달리 DDL 주체가 런타임이다.)
GRANT USAGE, CREATE ON SCHEMA data TO app_tenant;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES    IN SCHEMA data TO app_tenant;
GRANT USAGE, SELECT                  ON ALL SEQUENCES IN SCHEMA data TO app_tenant;
ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant IN SCHEMA data
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_tenant;
ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant IN SCHEMA data
  GRANT USAGE, SELECT ON SEQUENCES TO app_tenant;

-- 3) 조용한 연쇄 차단 — V32 의 기본 권한은 FOR ROLE app 으로 걸려 있다.
--    런타임이 app_tenant 로 바뀌면 새 데이터셋 테이블의 소유자도 app_tenant 가 되므로
--    V32 의 기본 권한이 적용되지 않아 pipeline_executor 가 신규 테이블을 보지 못한다.
--    (P3 에서 테넌트별 롤로 대체되지만, 그때까지 파이프라인이 깨지지 않도록 지금 대응한다.)
ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant IN SCHEMA data
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pipeline_executor;
ALTER DEFAULT PRIVILEGES FOR ROLE app_tenant IN SCHEMA data
  GRANT USAGE ON SEQUENCES TO pipeline_executor;
