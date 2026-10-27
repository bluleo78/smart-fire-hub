-- WD-258: api 재시작 뒤 RUNNING 으로 남은 프로액티브 실행을 식별한다.
-- 기존 RLS 정책은 새 열에도 그대로 적용된다.
ALTER TABLE proactive_job_execution
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP NULL;
COMMENT ON COLUMN proactive_job_execution.heartbeat_at IS
    '마지막 하트비트 시각(UTC). 오래된 활성 실행을 api 재시작으로 중단된 실행으로 정리한다(WD-258)';

-- 정리기(ProactiveExecutionSweeper)가 매분 「활성 행이 있는 테넌트」만 돌고, 그 테넌트 안에서 활성 행만 찾도록 활성 행 부분 색인을 둔다.
-- 끝난 실행이 대부분이라 부분 색인이 작고, 아래 definer 함수의 DISTINCT tenant_id 스캔과 테넌트별 정리 조회가 함께 쓴다.
CREATE INDEX IF NOT EXISTS idx_proactive_execution_active_tenant
    ON proactive_job_execution (tenant_id)
    WHERE status IN ('PENDING', 'RUNNING');

-- 활성(PENDING·RUNNING) 실행이 있는 테넌트 id 목록 — V106 outbox_tenant_ids 와 같은 모양의 SECURITY DEFINER 해석기.
-- 정리기는 컨텍스트 없는 스케줄 스레드라 RLS 아래서는 어느 테넌트에 일감이 있는지 알 수 없다(닭과 달걀). 소유자 권한으로 정수 목록만 돌려주고,
-- 실제 조회·FAILED 기록은 호출자가 테넌트마다 컨텍스트를 열어 RLS 아래서 한다. 테넌트 표에서 유도하지 않고 실행 표에서 유도하므로 정지된
-- 워크스페이스의 고아 실행도 빠지지 않는다. 읽기 전용 + 정수 목록이라 노출면은 "어느 테넌트에 진행 중인 스마트 작업 실행이 있는가" 뿐이다.
CREATE OR REPLACE FUNCTION proactive_active_execution_tenant_ids()
RETURNS SETOF BIGINT
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
  SELECT DISTINCT e.tenant_id
    FROM proactive_job_execution e
   WHERE e.status IN ('PENDING', 'RUNNING');
$$;

-- EXECUTE 의 기본값이 PUBLIC 허용이므로 반드시 회수하고 런타임 롤에만 부여한다.
REVOKE EXECUTE ON FUNCTION proactive_active_execution_tenant_ids() FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION proactive_active_execution_tenant_ids() TO app_tenant;

COMMENT ON FUNCTION proactive_active_execution_tenant_ids() IS
  '활성(PENDING·RUNNING) 프로액티브 실행이 있는 테넌트 id 목록(RLS 우회, 읽기 전용). 호출자는 이 목록으로 테넌트별 컨텍스트를 열고 실제 작업은 RLS 하에서 한다(WD-258).';

-- 기존 활성 행도 한 주기의 유예를 받도록 UTC 현재 시각으로 채운다(이 백필은 파일 끝에 둔다 — 테스트가 이 UPDATE 부터 끝까지를 떼어 다시 실행한다).
UPDATE proactive_job_execution
   SET heartbeat_at = (NOW() AT TIME ZONE 'UTC')
 WHERE status IN ('PENDING', 'RUNNING')
   AND heartbeat_at IS NULL;
