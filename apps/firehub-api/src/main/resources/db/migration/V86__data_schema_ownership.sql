-- V86: data 스키마 기존 테이블의 소유권을 런타임 롤(app_tenant)로 이전한다.
--
-- 왜 필요한가: DataTableService/DataTableRowService 는 "런타임"에 data 스키마의 DDL을 실행한다
-- (CREATE TABLE, DROP TABLE, ALTER TABLE … RENAME/ADD/DROP COLUMN, ALTER SEQUENCE … RENAME,
-- 인덱스 재생성, TRUNCATE TABLE). PostgreSQL 에서 이런 작업은 테이블 "소유자"만 할 수 있고,
-- TRUNCATE 권한도 소유자에게 암묵적으로 포함된다(별도 GRANT 대상이 아니다).
--
-- V83 은 app_tenant 에게 data 스키마에 대한 USAGE/CREATE 와 DML(SELECT/INSERT/UPDATE/DELETE)만
-- 부여했다. 이는 "새로 생성되는" 테이블에는 충분하다 — app_tenant 가 CREATE TABLE 을 실행하면
-- 소유자가 곧 app_tenant 이기 때문이다. 그러나 이 마이그레이션이 적용되기 이전부터 존재하던
-- data 스키마 테이블은 여전히 소유자 롤(app) 소유로 남아 있어, app_tenant 로 전환된 런타임이
-- 그 테이블에 DROP/ALTER/RENAME/TRUNCATE 를 시도하면 "must be owner of table" 로 실패한다.
-- 이 결함은 신선한(=data 스키마가 비어 있는) 환경의 스모크 테스트는 통과시키면서, 이미 데이터셋이
-- 존재하는 개발/운영 환경에서만 터진다 — 바로 그 환경에서 리네임/컬럼편집/삭제/REPLACE 임포트가
-- 전부 깨진다.
--
-- 소유권 이전 범위는 data 스키마로만 한정한다. `REASSIGN OWNED BY app TO app_tenant` 는
-- 데이터베이스 전체에 적용되어 public 스키마 테이블까지 app_tenant 소유로 바뀌는데, 테이블
-- 소유자는 RLS 를 우회하므로 그 한 문장이 P1/P2 가 구축한 RLS 강제 전제를 조용히 무너뜨린다.
-- 그래서 아래는 반드시 pg_tables 를 data 스키마로 필터링해 루프를 돈다.
--
-- ALTER TABLE … OWNER TO 는 해당 테이블의 인덱스와, 그 테이블 컬럼에 종속된(column-owned,
-- pg_depend.deptype='a') 시퀀스에 자동으로 전파된다. 확인 결과 data 스키마의 시퀀스는 전부
-- column-owned 이고 뷰/구체화 뷰는 없으므로 별도 루프가 필요 없다.
--
-- data 스키마가 비어 있는 환경(예: 테스트 DB)에서도 루프가 0회 반복되므로 안전하다(멱등).
DO $$
DECLARE
  t record;
BEGIN
  FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = 'data' LOOP
    EXECUTE format('ALTER TABLE data.%I OWNER TO app_tenant', t.tablename);
  END LOOP;
END $$;
