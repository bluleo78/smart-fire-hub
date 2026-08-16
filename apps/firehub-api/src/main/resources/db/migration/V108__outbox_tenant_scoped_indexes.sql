-- P2-g Task 1: outbox 테넌트 선행 인덱스.
--
-- 문제: idx_outbox_pending_due 는 (next_attempt_at) WHERE status='PENDING' 뿐이라 tenant_id 가
-- 인덱스에 없다. countPendingByChannel·claimDue 두 쿼리 모두 tenant_id 를 Filter(사후 필터)로
-- 처리해 PENDING 전체(535행, test DB 기준)를 훑은 뒤에야 테넌트 61행으로 줄인다.
-- outbox_tenant_ids 는 SECURITY DEFINER 라 RLS 술어조차 붙지 않는데 status 만 걸린 인덱스가 없어
-- 아예 Seq Scan(3885행)으로 떨어진다 — 보존기간이 늘수록 SENT/PERMANENT_FAILURE 누적에 비례해
-- 계속 나빠지는 구조.
--
-- 측정(test DB, 2026-08-17, EXPLAIN (ANALYZE, BUFFERS), tenant_id=1):
--   ① countPendingByChannel: Bitmap Heap Scan, Buffers: shared hit=89 (535→61행 사후 필터)
--   ② claimDue:              Index Scan on idx_outbox_pending_due, Buffers: shared hit=56
--   ③ outbox_tenant_ids:     Seq Scan 3885행, Buffers: shared hit=599
--
-- 신규 인덱스 적용 + VACUUM 후 재측정:
--   ① Index Only Scan, Heap Fetches: 0, Buffers: shared hit=5   (89 → 5)
--   ③ Index Only Scan, Heap Fetches: 0, Buffers: shared hit=11  (599 → 11)
-- (VACUUM 전에는 방금 쓴 행이라 visibility map 이 아직 갱신되지 않아 Heap Fetches 가 0 이 아니었다 —
--  Q1=14, Q3=461. 실서비스에서는 autovacuum 이 이 갭을 메운다.)

-- ① claimDue + countPendingByChannel 공용: 부분 인덱스(PENDING) + tenant 선행.
--    claimDue 는 tenant_id 로 걸러진 뒤 next_attempt_at 순으로 스캔해야 하므로 tenant_id 가
--    앞서야 한다(둘 다 등치 조건이면 순서 무관하지만, tenant_id 는 등치·next_attempt_at 은
--    범위/정렬이라 tenant_id 를 앞에 둬야 정렬을 인덱스가 그대로 제공한다).
--    channel_type 을 INCLUDE 로 실어 countPendingByChannel 이 GROUP BY 만으로 끝나게 한다
--    (WHERE 절엔 안 쓰지만 SELECT 절엔 필요 → 힙을 안 보게 하려면 INCLUDE 가 유일한 방법).
CREATE INDEX idx_outbox_pending_tenant_due
  ON notification_outbox (tenant_id, next_attempt_at)
  INCLUDE (channel_type)
  WHERE status = 'PENDING';

-- ② outbox_tenant_ids(status[]) 전용: status 로 먼저 좁힌 뒤 tenant_id 로 DISTINCT.
--    쿼리가 WHERE status = ANY(...) 뿐이라 tenant_id 를 앞에 두면 조건에 안 걸려 무의미하다.
--    status 를 선행 컬럼으로 둬야 Index Cond 로 좁혀지고, tenant_id 는 SELECT 절뿐이라
--    두 번째 컬럼에 실어 Index Only Scan(DISTINCT 도 인덱스에서 해결)이 되게 한다.
CREATE INDEX idx_outbox_status_tenant
  ON notification_outbox (status, tenant_id);

-- ③ idx_outbox_pending_due 제거 — 안전한 이유: ①이 같은 부분 조건(WHERE status='PENDING')에
--    tenant_id 선행 컬럼만 추가한 상위호환이라, claimDue·countPendingByChannel 이 쓰던 스캔은
--    전부 ①이 대신한다.
--
--    outbox_tenant_ids 의 세 호출처는 구분해서 봐야 한다:
--      - outbox_tenant_ids('{SENT,PERMANENT_FAILURE}')(NotificationRetentionJob) — 이 인자는
--        idx_outbox_pending_due 의 부분 조건(status='PENDING')과 안 맞아 애초에 이 인덱스를 타지
--        못하고 Seq Scan 이었다(적용 전 실측, 3885행/hit=599). V106 주석(192줄)이 "outbox_tenant_ids
--        의 DISTINCT 스캔이 이 인덱스를 타야 한다"고 적은 것은 이 호출처 기준으로는 틀렸다.
--      - outbox_tenant_ids('{PENDING}')(NotificationDispatchWorker·NotificationMetrics, 이 함수의
--        핫패스 호출처) 는 반대다 — V106 저자가 맞았다. idx_outbox_pending_due 의 부분 조건이
--        정확히 일치해 이 호출은 Seq Scan 을 피하고 있었다(다만 tenant_id 가 인덱스에 없어
--        index-only 는 못 되고, PENDING 행마다 힙을 한 번씩 봐야 했다).
--    ②(idx_outbox_status_tenant)는 이 PENDING 경로까지 포함해 세 호출처 전부를 Index Only Scan
--    (Heap Fetches: 0, VACUUM 후 실측)으로 덮는다 — idx_outbox_pending_due 가 하던 일의 상위호환이라
--    삭제해도 안전하다.
DROP INDEX IF EXISTS idx_outbox_pending_due;
