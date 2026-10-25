-- V148: 파이프라인 실행 하트비트(WD-230).
-- api 가 재시작되면 실행 중이던 @Async 러너 스레드가 사라지는데, 실행 행은 RUNNING 으로 남아 고아 staging 스윕 게이트(hasRunningExecution)를
-- 영원히 막는다. 살아 있는 api 가 자기 실행 중인 실행의 heartbeat_at 을 주기적으로 갱신하고, 하트비트가 오래된 실행을 중단으로 닫는다.
-- 판정은 COALESCE(heartbeat_at, started_at, created_at) 이지만 created_at 은 DB 세션 시간대의 NOW() 라 UTC 로 만든 임계값과 비교하면 어긋날 수 있다.
-- 그래서 아래에서 살아 있는 기존 행의 하트비트를 UTC 현재 시각으로 채워, 옛 행도 정상 실행처럼 한 주기의 유예를 받고 created_at 폴백은 실제로 쓰이지 않게 한다.
-- RLS 정책은 바꾸지 않는다(기존 pipeline_execution 정책이 새 열에도 그대로 적용된다).
ALTER TABLE pipeline_execution
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP NULL;
COMMENT ON COLUMN pipeline_execution.heartbeat_at IS
    '마지막 하트비트 시각(UTC). 실행을 쥔 api 가 주기적으로 갱신하며, 제출 시점·RUNNING 전환 시점에도 채운다. 오래되면 api 재시작으로 고아가 된 실행으로 보고 중단 처리(WD-230)';
-- 백필: 마이그레이션 시점에 PENDING/RUNNING 인 기존 행. Flyway 는 소유자 롤(RLS 우회, V93 도 같은 방식)이라 전 테넌트 행을 갱신한다.
UPDATE pipeline_execution
   SET heartbeat_at = (NOW() AT TIME ZONE 'UTC')
 WHERE status IN ('PENDING', 'RUNNING')
   AND heartbeat_at IS NULL;
