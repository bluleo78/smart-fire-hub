-- V137: PYTHON 스텝 등급별 읽기 슬롯 롤(WD-29, 스펙 2026-10-09 §4.1·§4.4).
--
-- 테넌트마다 pipeline_py_t{id}_s{k}(k=1..10) LOGIN 롤을 만든다. 슬롯 k 는 "등급 rank 오름차순 k 번째까지"의
-- 데이터셋을 읽는 롤이다. 테이블 SELECT 는 여기서 주지 않는다 — 앱의 PythonReadGrantSync(런타임 app_tenant,
-- 테이블 소유자)가 기동 시 맞춘다. 여기서는 소유자(app)만 할 수 있는 것(롤 생성·CONNECT·search_path·스키마
-- USAGE·public 직접 권한 회수)만 한다. ALTER DEFAULT PRIVILEGES 대상이 아니다(모든 테이블 권한은 명시 GRANT).
-- 기존 롤(app_tenant·pipeline_executor_t*·app)과 클러스터 수준 설정은 건드리지 않는다.
--
-- 비밀번호: HMAC secret 은 앱 설정에만 있어 SQL 에서 계산할 수 없다. 추측 불가한 임의값으로 만들고,
-- FlywayCallbackConfig 의 AFTER_MIGRATE 콜백이 같은 기동에서 TenantPipelineRole.pythonReadPassword 로 덮어쓴다.
-- (V111 의 공개 리터럴 선례는 따르지 않는다 — 콜백 전 짧은 창에도 알려진 비밀번호가 없게.)
--
-- 스키마명 규약은 DataSchema 와 같다: 테넌트 1 = data, 그 외 = data_t{id}.
-- 멱등: 이미 있는 롤은 다시 만들지 않고(비밀번호도 건드리지 않음) 나머지 문장은 반복해도 같은 상태가 된다.
DO $$
DECLARE
  t RECORD;
  k INT;
  r TEXT;
  s TEXT;
BEGIN
  FOR t IN SELECT id FROM tenant WHERE status = 'ACTIVE' ORDER BY id LOOP
    s := CASE WHEN t.id = 1 THEN 'data' ELSE 'data_t' || t.id END;
    FOR k IN 1..10 LOOP
      r := format('pipeline_py_t%s_s%s', t.id, k);
      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
        EXECUTE format(
          'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT',
          r, md5(random()::text || clock_timestamp()::text));
      END IF;
      EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), r);
      -- IN DATABASE 필수: 빠뜨리면 setdatabase=0(클러스터 전 DB)으로 기록된다(V111 주석·런북 §1-5).
      EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path TO %I', r, current_database(), s);
      -- 이 롤에 '직접' 부여된 public 스키마 권한만 걷는다(멱등·방어적). PUBLIC 의사 롤 경유 USAGE 는 걷지 못한다 —
      -- 스키마 안 이름 조회는 될 수 있다. 앱 테이블(public)을 못 읽게 하는 실제 방어선은 테이블 권한이다:
      -- public 테이블에는 이 롤(또는 PUBLIC)에 대한 SELECT GRANT 가 없다.
      EXECUTE format('REVOKE ALL ON SCHEMA public FROM %I', r);
      -- 스키마가 아직 없는 테넌트(데이터셋 미생성)는 TenantSchemaProvisioner 가 생성 트랜잭션에서 USAGE 를 건다.
      IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = s) THEN
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', s, r);
      END IF;
    END LOOP;
  END LOOP;
END
$$;
