-- DB 사용자 'app'의 SUPERUSER 권한 제거 (#87 보안)
-- POSTGRES_USER로 생성된 'app' 사용자는 SUPERUSER로 시작하나, 앱 실행에 필요 없음.
-- pg_read_file(), pg_shadow 접근 등 SUPERUSER 전용 기능을 차단하기 위해 권한 제거.
-- DB 소유자(owner) 권한은 유지되므로 스키마/테이블 DDL, DML은 계속 가능.
-- 부트스트랩 유저(initdb가 생성한 첫 번째 SUPERUSER)는 ALTER USER 실패할 수 있으므로 예외 무시.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'app' AND rolsuper = true) THEN
    BEGIN
      ALTER USER app NOSUPERUSER NOCREATEROLE NOCREATEDB;
    EXCEPTION WHEN OTHERS THEN
      -- 부트스트랩 유저는 SUPERUSER 제거 불가 — 무시하고 계속 진행
      NULL;
    END;
  END IF;
END
$$;
