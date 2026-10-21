-- WD-153: 실행 중인 파이프라인 실행을 사용자가 취소할 수 있게 한다(협조적 취소).
--
-- 취소 요청은 메모리가 아니라 DB 에 남긴다 — API 인스턴스가 여럿이면 취소 요청을 받은 인스턴스와 실행 스레드가
-- 도는 인스턴스가 다를 수 있다. 러너는 체크포인트(스텝 사이·API 페이지 사이·재시도 대기 중·AI 배치 사이 등)마다
-- 이 컬럼을 PK 로 한 번 읽어(싼 조회) 취소 요청을 알아챈다.
--   1) pipeline_execution.cancel_requested_at / cancel_requested_by — 누가 언제 취소를 요청했는지. 첫 요청만 남는다
--      (재요청은 COALESCE 로 덮지 않는다). 대기 중(PENDING) 실행은 요청 즉시 CANCELLED 로 마감되고, 실행 중(RUNNING)
--      실행은 러너가 다음 체크포인트에서 CANCELLED 로 마감한다. 마지막 체크포인트 뒤에 도착한 요청은 효과 없이 남는다
--      (실행은 정상 COMPLETED/FAILED — 이 컬럼만 채워진 채).
--   2) pipeline_step_execution.status 에 'CANCELLED' 를 허용한다 — 취소가 닿았을 때 진행 중이던 스텝의 상태다.
--      아직 시작하지 않은 스텝은 기존 관례대로 SKIPPED(사유 문구로 취소임을 남긴다). pipeline_execution.status 는
--      V3 부터 이미 'CANCELLED' 를 허용한다.
--
-- ── 트랜잭션 안에서 실행한다(사이드카 .conf 없음) ─────────────────────────────────────────
-- V139 와 같은 이유다. 두 테이블은 파이프라인 실행마다 쓰여 운영 중에는 락 획득 실패가 일어날 수 있다. 트랜잭션
-- 안이면 실패가 깨끗이 롤백되고 flyway_schema_history 에 아무 것도 남지 않아 다음 기동이 다시 시도한다.
--
-- ── 락 상한(SET LOCAL) ───────────────────────────────────────────────────────────────
-- 컬럼 추가·CHECK 교체는 ACCESS EXCLUSIVE 를 잡는다. 상한이 없으면 긴 쿼리 하나에 ALTER 가 무한정 대기하고 그 뒤
-- 모든 읽기·쓰기가 줄을 선다. CHECK 재생성은 pipeline_step_execution 전체를 한 번 훑어 검증한다(배포 전
-- `SELECT count(*) FROM pipeline_step_execution` 로 규모 확인).
--
-- ── RLS·tenant_id ─────────────────────────────────────────────────────────────────────
-- 새 컬럼은 기존 행 단위 정책(V96 pipeline_execution_tenant_isolation)에 그대로 덮인다 — 정책 변경 없음(FORCE RLS 도
-- 쓰지 않는다). tenant_id 는 건드리지 않는다.
SET LOCAL lock_timeout = '3s';

-- NULL 허용 — 배포 중 잠깐 공존하는 옛 API 인스턴스는 이 컬럼을 모르고 INSERT 한다. 시각 타입은 같은 테이블의
-- started_at / completed_at(TIMESTAMP, UTC 값)과 맞춘다.
ALTER TABLE pipeline_execution
  ADD COLUMN IF NOT EXISTS cancel_requested_at TIMESTAMP NULL;

-- 취소 요청자. executed_by 와 달리 ON DELETE SET NULL — 실행자가 아닌 사용자가 취소한 경우 그 사용자 삭제를 새로 막지
-- 않게 한다(요청 시각은 남는다).
ALTER TABLE pipeline_execution
  ADD COLUMN IF NOT EXISTS cancel_requested_by BIGINT NULL REFERENCES "user"(id) ON DELETE SET NULL;

-- 스텝 상태 CHECK 에 CANCELLED 추가. 이름은 V3 인라인 CHECK 가 만든 PG 기본 이름이다(jOOQ 생성 소스
-- PipelineStepExecution 의 pipeline_step_execution_status_check 로 확인). IF EXISTS 를 쓰지 않는다 — 이름이 다르면
-- 옛 CHECK 가 조용히 남아 CANCELLED 쓰기가 실패하므로, 마이그레이션을 실패(롤백)시키는 쪽이 옳다.
ALTER TABLE pipeline_step_execution
  DROP CONSTRAINT pipeline_step_execution_status_check;
ALTER TABLE pipeline_step_execution
  ADD CONSTRAINT pipeline_step_execution_status_check
  CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED', 'CANCELLED'));
