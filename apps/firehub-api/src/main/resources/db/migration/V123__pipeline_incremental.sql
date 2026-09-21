-- V123: 파이프라인 SQL 스텝 증분 처리 기반
-- 1) 데이터셋 행의 변경 시각(_updated_at)을 트리거로 기록한다 — 모든 쓰기 경로(임포트·UI·파이프라인)를
--    코드 수정 없이 한 번에 덮기 위해 트리거를 쓴다. now()=트랜잭션 시작 시각이며, 책갈피 규칙과 짝이다.
-- 2) 책갈피 후보값 계산용: 진행 중인 가장 오래된 트랜잭션의 시작 시각. 런타임 롤(app_tenant)은
--    다른 롤 세션의 xact_start 를 볼 수 없으므로(실측) 소유자 권한 SECURITY DEFINER 함수로 이 값만 노출한다.

CREATE OR REPLACE FUNCTION public.fh_touch_updated_at() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog
AS $$
BEGIN
  NEW._updated_at := now();
  RETURN NEW;
END
$$;

-- ── 소유자 권한 요구사항(반드시 지킬 것) ────────────────────────────────────────────────
-- 이 함수는 SECURITY DEFINER 라 "함수 소유자"(= 이 마이그레이션을 실행한 Flyway 롤, 로컬/운영 모두 app)
-- 권한으로 pg_stat_activity 를 읽는다. 그런데 PostgreSQL 은 **소유자가 superuser 이거나
-- pg_read_all_stats 멤버일 때만** 다른 롤 세션의 xact_start 를 보여준다. 그렇지 않으면 그 컬럼들이
-- 전부 NULL 로 읽히고, 아래 "xact_start IS NOT NULL" 필터가 모든 행을 떨어뜨려 후보값이 조용히
-- clock_timestamp() 로 내려앉는다 — 오류도 로그도 없이, 이 함수가 막으려던 "늦게 커밋한 행을 영원히
-- 놓치는" 사고가 그대로 재현된다.
-- 실측(2026-09-21, 로컬 test DB): app = superuser(+bypassrls) 이고 두 fh_* 함수의 소유자다.
-- app_tenant / pipeline_executor* 는 비특권이라 스스로는 다른 롤의 xact_start 를 볼 수 없다.
-- Flyway 롤을 비특권으로 낮추거나 함수 소유자를 바꾸려면 반드시 pg_read_all_stats 를 함께 부여할 것.
-- 회귀 방지: PipelineIncrementalSchemaTest 가 (1) 소유자의 stats 가시성 권한을 카탈로그로,
-- (2) 다른 롤의 열린 트랜잭션이 소유자에게 실제로 보이는지를 런타임으로 단언한다.
--
-- ── 알려진 한계(의도적으로 이번 커밋에서 바꾸지 않음) ─────────────────────────────────────
-- LEAST(t0, min(xact_start)) 는 이 데이터베이스의 **모든** client backend 를 대상으로 한다 —
-- 데이터셋 테이블을 쓰지 않는 백엔드도 포함이다. 그래서 idle in transaction 으로 멈춘 커넥션(풀이
-- 붙들고 있는 것 포함)이 하나라도 있으면 모든 증분 실행의 후보값이 그 트랜잭션 시작 시각에 고정되고,
-- last_run_at 이 전진하지 못해 매 실행이 전체 이력을 다시 읽는다. MERGE 가 흡수하므로 데이터 손실은
-- 아니지만 증분 처리의 이득이 조용히 사라진다.
-- 남은 선택지(다음 작업자가 의도적으로 고를 것):
--   (a) 나이로 제한: xact_start > now() - interval 'N min' 인 것만 본다 — 단순하지만, 진짜로 오래
--       걸리는 쓰기 트랜잭션을 무시하게 되어 이 함수가 막으려던 누락 사고를 되살린다(권장하지 않음).
--   (b) 쓰기 롤로 한정: usename 을 데이터셋에 쓰는 롤(app_tenant, pipeline_executor*)로 좁힌다 —
--       의미를 유지한 채 무관한 백엔드의 영향을 없앤다(권장).
--   (c) oldest 가 N분 이상 오래됐으면 경고 로그/메트릭을 남겨 운영자가 알아채게 한다(병행 권장).
--
-- 평가 순서를 고정하기 위해 plpgsql 로 쓴다: 시각(t0)을 "먼저" 잡고 그 뒤에 활성 트랜잭션을 본다.
-- 순서가 반대면 스냅샷과 clock 사이에 시작한 트랜잭션 T 가 둘 다에서 빠져 candidate > T.xact_start 가 되고,
-- T 가 나중에 커밋한 행(_updated_at = T.xact_start)을 다음 실행이 영원히 놓친다.
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

-- ── 락 상한(SET LOCAL) ────────────────────────────────────────────────────────────────
-- 아래 두 ALTER 는 ACCESS EXCLUSIVE 를 잡는다. 상한이 없으면 대상 테이블을 붙들고 있는 긴 쿼리가
-- 하나만 있어도 ALTER 가 무한정 대기하고, **그 뒤에 오는 모든 읽기·쓰기가 대기 중인 ALTER 뒤에
-- 줄을 선다** — "빠른 마이그레이션"이 서비스를 통째로 멈추는 전형적 패턴이다.
-- 이 마이그레이션은 **트랜잭션 안에서** 실행되므로(사이드카 .conf 없음) SET LOCAL 이 맞다 —
-- 마이그레이션이 끝나면 자동으로 원래 값으로 돌아간다.
--
-- ── 이 파일을 다시 트랜잭션 밖으로 빼지 말 것 ────────────────────────────────────────
-- pipeline_step / pipeline_step_execution 은 파이프라인 실행마다 쓰이는 테이블이라 운영 중에는
-- 3초 락 실패가 충분히 일어난다. 트랜잭션 안이면 그 실패가 깨끗이 롤백되고 flyway_schema_history
-- 에 아무 것도 남지 않아 다음 기동이 그냥 다시 시도한다. 트랜잭션 밖이면 success=false 행이 남고
-- **이후 모든 기동이 "Schema public contains a failed migration to version 122" 로 죽는다**
-- (restart: unless-stopped 와 만나면 502 크래시 루프). 그래서 "실패할 수 있고 롤백되어야 하는 것"은
-- 전부 이 파일에, "테이블 단위로 건너뛰어도 되는 것"만 V124(트랜잭션 밖)에 둔다.
SET LOCAL lock_timeout = '3s';

-- 둘 다 메타데이터 테이블이고 NULL/fast-default 컬럼 추가라 테이블 재작성이 없다 — 락 유지 시간은
-- ms 단위다. 이 둘이 실패하면 마이그레이션이 실패한다(롤백) — 스키마의 핵심이라 건너뛸 수 없다.
ALTER TABLE pipeline_step
  ADD COLUMN IF NOT EXISTS last_run_at TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS full_rebuild_pending BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE pipeline_step_execution
  ADD COLUMN IF NOT EXISTS injected_last_run_at TIMESTAMPTZ NULL;

-- 사용자 컬럼이 이미 _updated_at 이면 조용히 건너뛰지 않고 실패시킨다(건너뛰면 그 데이터셋 증분이 틀어진다).
-- storage_type = 'TABLE' 로 한정한다 — 백필(V124)도 TABLE 데이터셋만 건드리므로, VIEW/QUERY 등
-- 물리 테이블이 없는(또는 백필 대상이 아닌) 데이터셋이 우연히 _updated_at 이라는 이름의 컬럼
-- 메타데이터를 갖고 있어도(물리 테이블에 실존 여부와 무관) 마이그레이션 전체를 중단시키면 안 된다.
-- 이 검사는 여기(트랜잭션 안)에 둔다 — RAISE EXCEPTION 으로 실패해도 깨끗이 롤백되어야 하고,
-- 백필이 한 테이블이라도 건드리기 **전에** 걸러야 하기 때문이다.
DO $$
DECLARE
  conflicts text;
BEGIN
  SELECT string_agg(d.tenant_id || ':' || d.table_name, ', ')
    INTO conflicts
  FROM dataset d
  JOIN dataset_column c ON c.dataset_id = d.id
  WHERE c.column_name = '_updated_at'
    AND d.storage_type = 'TABLE';
  IF conflicts IS NOT NULL THEN
    RAISE EXCEPTION 'V123: 사용자 컬럼 _updated_at 과 충돌하는 데이터셋: %', conflicts;
  END IF;
END
$$;
