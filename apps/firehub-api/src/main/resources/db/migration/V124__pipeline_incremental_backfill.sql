-- V124: 기존 데이터셋 물리 테이블에 _updated_at 컬럼과 fh_touch_updated_at 트리거를 백필한다.
-- 스키마 매핑(테넌트1=data, 그 외 data_t{id})은 DataSchema.java·V123 와 동일해야 한다.
--
-- ── 왜 V123 에서 떼어냈나(되돌리지 말 것) ──────────────────────────────────────────────
-- 이 루프만 **트랜잭션 밖**이어야 한다. 두 가지 이유가 함께 걸려 있다.
--  1) 테이블마다 COMMIT 해야 한다. 한 트랜잭션 안이면 첫 테이블에서 잡은 ACCESS EXCLUSIVE 락이
--     마이그레이션 전체가 커밋될 때까지 풀리지 않는다 — 데이터셋 테이블이 89개인 dev DB 기준으로
--     89개 테이블이 동시에 배타 잠금 상태가 되고 그동안 모든 읽기·쓰기가 막힌다. plpgsql 의
--     COMMIT 은 DO 블록이 트랜잭션 블록 안에 있지 않을 때만 쓸 수 있으므로, 이 스크립트에는
--     executeInTransaction=false 사이드카(V124__pipeline_incremental_backfill.sql.conf)가
--     반드시 함께 있어야 한다.
--  2) 그런데 트랜잭션 밖 마이그레이션은 실패하면 flyway_schema_history 에 success=false 행을
--     남겨 **이후 모든 기동을 막는다**. 그래서 "실패할 수 있고 롤백되어야 하는 것"(함수 정의,
--     pipeline_step* ALTER, _updated_at 이름 충돌 검사)은 전부 트랜잭션 마이그레이션인 V123 에
--     남겼고, 이 파일에는 **테이블 단위로 건너뛰어도 되는 작업만** 둔다. 여기서 던질 수 있는 것은
--     락 실패가 아닌 예상 밖 오류뿐이다(그건 조용히 넘기면 안 되므로 의도적으로 던진다).
--
-- ── 건너뛴 테이블의 결과(의도된 fail-closed) ──────────────────────────────────────────
-- 컬럼 추가와 트리거 생성은 **같은 서브트랜잭션**이라 실패 시 둘 다 롤백된다 — "컬럼은 있는데
-- 트리거가 없는" 상태(=UPDATE 가 _updated_at 을 갱신하지 않아 증분이 **조용히 틀리는** 상태)는
-- 만들어지지 않는다. 건너뛴 테이블은 _updated_at 자체가 없으므로, 그 데이터셋을 쓰는 증분 스텝의
-- SQL(사용자가 직접 쓰는 `_updated_at >= {{last_run_at}}`)이 "column _updated_at does not exist"
-- 로 **즉시 실패**한다. 조용히 틀린 결과가 나오는 경로가 아니다.
-- 수리는 scripts/sql/complete_updated_at_backfill.sql 이다 — 성공 기록된 이 마이그레이션을
-- Flyway 가 다시 돌리지는 않기 때문이다.

-- 세션 수준 SET 이다 — 이 스크립트는 트랜잭션 밖에서 실행되므로 SET LOCAL 은 각 문장의 암묵
-- 트랜잭션에서만 살아 의미가 없다. 3초 안에 락을 못 잡으면 55P03 으로 그 테이블만 건너뛴다.
SET lock_timeout = '3s';

DO $$
DECLARE
  r record;
  sch text;
  conflicts text;
  skipped text[] := ARRAY[]::text[];
BEGIN
  FOR r IN
    SELECT d.tenant_id, d.table_name
    FROM dataset d
    WHERE d.storage_type = 'TABLE' AND d.table_name IS NOT NULL
  LOOP
    sch := CASE WHEN r.tenant_id = 1 THEN 'data' ELSE 'data_t' || r.tenant_id END;
    -- 물리 테이블이 없으면(또는 같은 이름의 다른 객체면) 건너뛴다. relkind 까지 보는 이유:
    -- to_regclass 는 뷰·시퀀스·인덱스에도 OID 를 돌려주므로 이름만 맞는 다른 객체에 ALTER TABLE 을
    -- 걸 수 있다. 'r'(일반 테이블)·'p'(파티션 부모)만 백필 대상이며, 이 NOT EXISTS 는
    -- to_regclass 가 NULL 인 경우(카탈로그 행만 있는 고아 데이터셋)도 함께 걸러 낸다.
    CONTINUE WHEN NOT EXISTS (
      SELECT 1 FROM pg_class c
      WHERE c.oid = to_regclass(format('%I.%I', sch, r.table_name))
        AND c.relkind IN ('r', 'p'));
    -- 이 내부 BEGIN...EXCEPTION 은 서브트랜잭션이다 — 락 대기로 실패하면 이 테이블의 ALTER·트리거가
    -- 함께 롤백되고(부분 적용 없음) 다음 테이블로 넘어간다. COMMIT 은 반드시 이 블록 **밖**이어야
    -- 한다(서브트랜잭션이 열린 상태에서는 COMMIT 할 수 없다).
    BEGIN
      EXECUTE format('ALTER TABLE %I.%I ADD COLUMN IF NOT EXISTS _updated_at TIMESTAMPTZ NOT NULL DEFAULT now()',
                     sch, r.table_name);
      EXECUTE format('DROP TRIGGER IF EXISTS fh_touch_updated_at ON %I.%I', sch, r.table_name);
      EXECUTE format('CREATE TRIGGER fh_touch_updated_at BEFORE INSERT OR UPDATE ON %I.%I '
                     'FOR EACH ROW EXECUTE FUNCTION public.fh_touch_updated_at()', sch, r.table_name);
    EXCEPTION WHEN lock_not_available OR query_canceled
                OR deadlock_detected OR undefined_table THEN
      -- "이 테이블을 지금 잠글 수 없다"는 상황만 건너뛴다:
      --   lock_not_available(55P03)/query_canceled(57014) — lock_timeout·취소
      --   deadlock_detected(40P01) — 교착 희생자로 뽑힌 경우. 다음 실행이 다시 시도하면 된다.
      --   undefined_table(42P01) — 위 relkind 검사와 ALTER 사이에 데이터셋이 삭제된 경합.
      -- 셋 다 확률은 낮지만, 이 마이그레이션은 트랜잭션 밖이라 **던지는 순간 success=false 행이
      -- 남아 이후 모든 기동을 막는다**. 그 대가가 "인덱스 한 테이블이 늦는다"보다 훨씬 크므로
      -- 잡아서 건너뛴다. 그 외 오류(권한·정의 오류 등)는 잡지 않고 그대로 올려보내 실패시킨다 —
      -- 그런 오류는 재시도로 낫지 않고 조용히 넘기면 안 된다.
      skipped := skipped || (sch || '.' || r.table_name);
      RAISE WARNING 'V124: %.% 의 _updated_at 컬럼·트리거를 적용하지 못해 건너뛴다(락 실패·교착·테이블 소멸) — 이 테이블을 쓰는 증분 스텝은 실행 시 즉시 실패한다(조용히 틀리지 않는다).',
                    sch, r.table_name;
    END;
    -- 테이블 하나가 끝날 때마다 커밋해 락을 즉시 푼다(위 "테이블마다 커밋한다" 주석 참고).
    COMMIT;
    -- 인덱스(ix_<table>_upd)는 여기서 만들지 않는다 — V125 가 CREATE INDEX CONCURRENTLY 로 만든다.
    -- 이유(되돌리지 말 것): 평범한 CREATE INDEX 는 인덱스 빌드가 끝날 때까지 SHARE 락을 잡아 그
    -- 테이블의 쓰기를 막는다(큰 테이블이면 수 초~수 분). 이 루프의 DDL 은 ms 단위라 테이블마다
    -- 바로 커밋되는데, 둘을 한자리에 섞으면 값싼 작업이 비싼 작업의 시간을 함께 뒤집어쓴다.
    -- 여기 남긴 컬럼 추가·트리거 생성은 값싸다(now() 는 STABLE 이라 PG 의 fast-default 경로 —
    -- 테이블 재작성 없음). 자세한 근거는 V125 클래스 주석 참고.
  END LOOP;


  -- ── 완료 여부를 카탈로그로 다시 확인한다(의도) ──────────────────────────────────────
  -- 위 skipped 배열은 "이번 실행이 건너뛴 것"만 안다. 운영자가 알아야 하는 것은 "지금 이 DB 에
  -- 아직 미완인 테이블이 무엇인가"다 — 예전 실행에서 빠진 것, 사람이 나중에 만든 것까지 포함해서.
  -- 그래서 배열이 아니라 카탈로그를 다시 조회해 **이름으로** 남긴다. 배포 로그만 보고도
  -- "이 N개 테이블이 미완"을 알 수 있어야 하고, 아무 것도 조회하지 않아도 되어야 한다.
  SELECT string_agg(x.sch || '.' || x.table_name, ', ' ORDER BY x.sch, x.table_name)
    INTO conflicts
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
  IF conflicts IS NOT NULL THEN
    RAISE WARNING 'V124: _updated_at 백필이 미완인 테이블: % — 이 테이블들을 쓰는 증분 스텝은 실행 시 즉시 실패한다(조용히 틀리지 않는다). 완료하려면 scripts/sql/complete_updated_at_backfill.sql 을 소유자 롤로 실행하라(멱등, 재실행 안전).', conflicts;
  ELSE
    RAISE NOTICE 'V124: _updated_at 백필 완료 — 미완 테이블 없음.';
  END IF;
END
$$;

RESET lock_timeout;
