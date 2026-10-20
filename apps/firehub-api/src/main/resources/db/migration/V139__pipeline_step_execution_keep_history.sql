-- WD-108: 파이프라인을 고쳐 저장해도 과거 스텝 실행 기록이 사라지지 않게 한다.
--
-- 예전에는 pipeline_step_execution.step_id 가 ON DELETE CASCADE(V3) 였고, 파이프라인 저장이 스텝을
-- 전부 지우고 다시 만들었기 때문에 저장 한 번에 과거 실행의 스텝 상태·오류·적재 행 수가 통째로 지워졌다.
-- 저장 경로는 이제 같은 스텝을 제자리 갱신(id 유지)하지만, 정의에서 실제로 빠진 스텝의 과거 기록도
-- 남아야 한다 — 그래서:
--   1) step_name 스냅샷 컬럼을 추가하고 기존 행은 현재 스텝 이름으로 채운다(스텝이 지워진 뒤에도 이름이 남게).
--   2) step_id 를 NULL 허용으로 바꾸고 FK 를 ON DELETE SET NULL 로 바꾼다(스텝 삭제 = 기록 보존 + 연결만 끊음).
--
-- ── 트랜잭션 안에서 실행한다(사이드카 .conf 없음) ─────────────────────────────────────────
-- V123 과 같은 이유다. pipeline_step / pipeline_step_execution 은 파이프라인 실행마다 쓰이는 테이블이라
-- 운영 중에는 3초 락 실패가 충분히 일어난다. 트랜잭션 안이면 실패가 깨끗이 롤백되고
-- flyway_schema_history 에 아무 것도 남지 않아 다음 기동이 그냥 다시 시도한다. 트랜잭션 밖이면
-- success=false 행이 남아 이후 모든 기동이 "contains a failed migration" 으로 죽는다.
--
-- ── 락 상한(SET LOCAL) ───────────────────────────────────────────────────────────────
-- 아래 ALTER 들은 pipeline_step_execution 에 ACCESS EXCLUSIVE 를, FK 재생성은 pipeline_step 에
-- SHARE ROW EXCLUSIVE 를 잡는다. 상한이 없으면 대상 테이블을 붙든 긴 쿼리 하나에 ALTER 가 무한정 대기하고
-- 그 뒤 모든 읽기·쓰기가 줄을 선다. SET LOCAL 이라 마이그레이션이 끝나면 원래 값으로 돌아간다.
--
-- ── 잠금 유지 시간(배포 주의) ─────────────────────────────────────────────────────────
-- 락은 커밋까지 유지된다. 컬럼 추가·NOT NULL 해제·FK 교체는 메타데이터 변경이라 ms 단위지만, 백필 UPDATE 와
-- FK 검증은 pipeline_step_execution 전체를 한 번씩 훑는다 — 그 동안 이 테이블의 읽기·쓰기(실행 상세 조회,
-- 파이프라인 실행)가 대기한다. 배포 전 `SELECT count(*) FROM pipeline_step_execution` 으로 규모를 확인할 것.
--
-- ── RLS·tenant_id ─────────────────────────────────────────────────────────────────────
-- 새 컬럼은 행 단위 정책(V96 pipeline_step_execution_tenant_isolation)에 그대로 덮인다 — 정책 변경 없음.
-- Flyway 는 소유자 롤(RLS 우회, GUC 없음)로 돌므로 백필은 전 테넌트 행을 본다. 조인은 FK(step_id → id)를
-- 따라가므로 같은 테넌트의 스텝 이름만 들어간다. tenant_id 컬럼은 건드리지 않는다.
SET LOCAL lock_timeout = '3s';

-- NULL 허용 컬럼 추가 — 테이블 재작성 없음. NOT NULL 로 두지 않는다: 배포 중 잠깐 공존하는 옛 API 인스턴스는
-- 이 컬럼을 모르고 INSERT 하므로, NOT NULL 이면 그 사이 시작된 실행이 전부 실패한다.
-- 길이는 pipeline_step.name(VARCHAR(100), V3)과 같게 둔다.
ALTER TABLE pipeline_step_execution
  ADD COLUMN IF NOT EXISTS step_name VARCHAR(100) NULL;

-- 기존 행 백필 — 현재 스텝 이름을 스냅샷으로 떠 둔다. 이 시점까지 남은 행은 전부 step_id 가 살아 있다
-- (CASCADE 였으므로 스텝 없는 행은 이미 지워졌다). 재실행해도 결과가 같도록 step_name IS NULL 만 채운다.
-- 아래 표식은 PipelineStepExecutionHistoryMigrationTest 가 이 문장만 잘라 재실행하는 데 쓴다 — 지우지 말 것.
-- BACKFILL-BEGIN
UPDATE pipeline_step_execution se
   SET step_name = ps.name
  FROM pipeline_step ps
 WHERE se.step_id = ps.id
   AND se.step_name IS NULL
-- BACKFILL-END
;

-- 스텝이 지워지면 step_id 를 NULL 로 두고 기록은 남긴다.
ALTER TABLE pipeline_step_execution
  ALTER COLUMN step_id DROP NOT NULL;

-- FK 이름은 V3 의 인라인 REFERENCES 가 만든 PG 기본 이름이다(jOOQ Keys.PIPELINE_STEP_EXECUTION__
-- PIPELINE_STEP_EXECUTION_STEP_ID_FKEY 로 확인). 같은 이름으로 다시 만들어 이후 참조가 흔들리지 않게 한다.
-- IF EXISTS 를 쓰지 않는다 — 이름이 다르면 CASCADE FK 가 조용히 남아 이 마이그레이션의 목적이 무산되므로
-- 실패(롤백)시키는 쪽이 옳다.
ALTER TABLE pipeline_step_execution
  DROP CONSTRAINT pipeline_step_execution_step_id_fkey;
ALTER TABLE pipeline_step_execution
  ADD CONSTRAINT pipeline_step_execution_step_id_fkey
  FOREIGN KEY (step_id) REFERENCES pipeline_step(id) ON DELETE SET NULL;
