-- 파이프라인 증분 처리 백필 마무리 스크립트 (V124 백필의 후속 수리용)
--
-- 언제 쓰나: 배포 로그에 "V124: _updated_at 백필이 미완인 테이블: ..." 경고가 남았을 때.
--   ↑ grep 대상은 정확히 이 문자열이다. 이 경고를 내는 것은 **V124**(백필 마이그레이션)이지
--     V123(함수·pipeline_step ALTER)나 V125(인덱스)가 아니다 — 버전 접두사를 잘못 찾으면
--     "아무 것도 안 나왔으니 다 됐다"는 정반대 결론이 난다.
-- V124 은 데이터셋 테이블마다 3초 lock_timeout 으로 DDL 을 시도하고, 그 시간 안에 락을 잡지 못한
-- 테이블은 건너뛴다(그래야 대기 중인 ALTER 뒤로 서비스 트래픽이 줄 서는 사고가 나지 않는다).
-- Flyway 는 이미 성공 기록된 V124 을 다시 돌리지 않으므로, 남은 테이블은 이 스크립트로 마무리한다.
--
-- 성질:
--   * 멱등 — 이미 끝난 테이블은 건드리지 않는다(컬럼·트리거가 모두 있으면 건너뛴다).
--   * 기존 행을 바꾸지 않는다 — DELETE/UPDATE/TRUNCATE/DROP TABLE/DROP COLUMN 이 없다.
--     ADD COLUMN IF NOT EXISTS + 자기 트리거의 DROP TRIGGER IF EXISTS + CREATE TRIGGER 뿐이다.
--   * 테이블마다 커밋해 락을 즉시 푼다. 락을 못 잡으면 그 테이블만 건너뛰고 계속한다.
--   * 트랜잭션 밖에서 실행해야 한다(plpgsql COMMIT 제약). psql 로 그냥 실행하면 된다:
--       psql "postgresql://<owner>@<host>:<port>/<db>" -f scripts/sql/complete_updated_at_backfill.sql
--     (-1/--single-transaction 옵션을 붙이면 안 된다.)
--   * 소유자 롤(Flyway 가 쓰는 롤, 로컬/운영 모두 app)로 실행해야 한다 — dataset 테이블의 RLS 를
--     우회해야 모든 테넌트의 데이터셋이 보인다.
--
-- 인덱스(ix_<table>_upd)는 여기서 만들지 않는다 — 성능 전용이고 V125 가 CREATE INDEX CONCURRENTLY 로
-- 담당한다. 필요하면 다음 한 줄을 테이블별로 직접 실행하면 된다(역시 트랜잭션 밖):
--   CREATE INDEX CONCURRENTLY IF NOT EXISTS "ix_<table>_upd" ON <schema>."<table>" (_updated_at);

SET lock_timeout = '3s';

DO $$
DECLARE
  r record;
  sch text;
  remaining text;
BEGIN
  FOR r IN
    SELECT d.tenant_id, d.table_name
    FROM dataset d
    WHERE d.storage_type = 'TABLE' AND d.table_name IS NOT NULL
  LOOP
    sch := CASE WHEN r.tenant_id = 1 THEN 'data' ELSE 'data_t' || r.tenant_id END;
    -- 물리 테이블이 없거나(고아 데이터셋) 같은 이름의 다른 객체(뷰·시퀀스 등)면 건너뛴다.
    CONTINUE WHEN NOT EXISTS (
      SELECT 1 FROM pg_class c
      WHERE c.oid = to_regclass(format('%I.%I', sch, r.table_name))
        AND c.relkind IN ('r', 'p'));
    -- 이미 컬럼·트리거가 둘 다 있으면 락조차 잡지 않는다(완료된 테이블은 건드리지 않는다).
    CONTINUE WHEN EXISTS (SELECT 1 FROM information_schema.columns c
                           WHERE c.table_schema = sch AND c.table_name = r.table_name
                             AND c.column_name = '_updated_at')
             AND EXISTS (SELECT 1 FROM pg_trigger t
                           WHERE t.tgrelid = to_regclass(format('%I.%I', sch, r.table_name))
                             AND t.tgname = 'fh_touch_updated_at');
    BEGIN
      -- 컬럼과 트리거는 같은 (서브)트랜잭션에서 함께 적용된다 — "컬럼은 있는데 트리거가 없는"
      -- 상태는 증분 결과가 조용히 틀어지는 최악의 상태라 절대 만들면 안 된다.
      EXECUTE format('ALTER TABLE %I.%I ADD COLUMN IF NOT EXISTS _updated_at TIMESTAMPTZ NOT NULL DEFAULT now()',
                     sch, r.table_name);
      EXECUTE format('DROP TRIGGER IF EXISTS fh_touch_updated_at ON %I.%I', sch, r.table_name);
      EXECUTE format('CREATE TRIGGER fh_touch_updated_at BEFORE INSERT OR UPDATE ON %I.%I '
                     'FOR EACH ROW EXECUTE FUNCTION public.fh_touch_updated_at()', sch, r.table_name);
      RAISE NOTICE '완료: %.%', sch, r.table_name;
    EXCEPTION WHEN lock_not_available OR query_canceled
                OR deadlock_detected OR undefined_table THEN
      -- V124 과 같은 집합만 잡는다(락 실패·취소·교착·테이블 소멸). 그 외 오류는 그대로 올려보낸다.
      RAISE WARNING '건너뜀(락 실패·교착·테이블 소멸): %.% — 한가할 때 다시 실행하라.', sch, r.table_name;
    END;
    COMMIT;
  END LOOP;

  SELECT string_agg(x.sch || '.' || x.table_name, ', ' ORDER BY x.sch, x.table_name)
    INTO remaining
  FROM (
    SELECT CASE WHEN d.tenant_id = 1 THEN 'data' ELSE 'data_t' || d.tenant_id END AS sch,
           d.table_name
    FROM dataset d
    WHERE d.storage_type = 'TABLE' AND d.table_name IS NOT NULL
  ) x
  -- 대상 판정은 루프와 같은 규칙이어야 한다(relkind 'r'/'p') — 다르면 루프가 건드리지 않는 객체가
  -- "영원히 미완"으로 보고되어 운영자가 고칠 수 없는 경고를 계속 보게 된다.
  WHERE EXISTS (SELECT 1 FROM pg_class pc
                 WHERE pc.oid = to_regclass(format('%I.%I', x.sch, x.table_name))
                   AND pc.relkind IN ('r', 'p'))
    AND (NOT EXISTS (SELECT 1 FROM information_schema.columns c
                      WHERE c.table_schema = x.sch AND c.table_name = x.table_name
                        AND c.column_name = '_updated_at')
      OR NOT EXISTS (SELECT 1 FROM pg_trigger t
                      WHERE t.tgrelid = to_regclass(format('%I.%I', x.sch, x.table_name))
                        AND t.tgname = 'fh_touch_updated_at'));
  IF remaining IS NOT NULL THEN
    RAISE WARNING '아직 미완인 테이블: %', remaining;
  ELSE
    RAISE NOTICE '모든 TABLE 데이터셋이 완료됐다.';
  END IF;
END
$$;

RESET lock_timeout;
