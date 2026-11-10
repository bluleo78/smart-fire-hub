-- V158: 실행 주기(cron) 없이 「켜짐」으로 저장된 정기(SCHEDULE) 스마트 작업을 끄고, 빈 시간대로 저장된 작업을 기본 시간대로 되돌린다 (WD-607).
--
-- 1) 주기 없는 정기 작업 끄기
-- 왜 필요한가: 생성·수정 API 가 주기 없는 정기 작업을 201/200 으로 받아, 목록에는 「켜짐」인데 스케줄러는 cron 이 없어 등록하지 않는
-- 작업이 남았다. 이제 API 는 400 으로 막지만, 이미 저장된 행은 사용자가 영원히 실행된다고 믿는 상태로 남는다. 끄기만 하고 지우지 않는다 —
-- 사용자가 주기를 넣고 다시 켤 수 있다(켜기는 저장된 cron 을 쓰므로, 주기를 함께 저장해야 켜진다).
-- 대상: 트리거가 SCHEDULE 이거나 NULL(컬럼 기본값 SCHEDULE 과 같게 본다)이고 cron 이 NULL·빈 값·공백인 켜진 작업.
-- BOTH 는 건드리지 않는다 — 이상 탐지 트리거가 살아 있어 실제로 실행된다. ANOMALY 도 주기가 필요 없다.
--
-- 2) 빈 시간대 되돌리기
-- 예전 수정 경로는 빈 값·공백 시간대를 그대로 저장했다. 이제 API 는 빈 시간대를 400 으로 막아, 웹 상세 화면이 그 값을 다시 보내는 복제
-- (생성 규칙) 등이 막힌다. 컬럼은 NOT NULL DEFAULT 'Asia/Seoul'(V42)이라 NULL 은 없고, 빈 값·공백만 기본값으로 바꾼다.
--
-- RLS: proactive_job 은 ENABLE 만 하고 FORCE 하지 않는다(V104) — Flyway 계정(app, 테이블 소유자)에게 정책이 적용되지 않아 모든
-- 테넌트의 행이 대상이 된다. 다시 돌려도 결과가 같다(멱등). 감사 로그는 남기지 않는다(V152 와 같은 데이터 보정).
-- Tibero 짝: db/migration-tibero/V158 (MigrationParityTest).
SET LOCAL lock_timeout = '3s';

UPDATE proactive_job
SET enabled = false
WHERE enabled = true
  AND COALESCE(trigger_type, 'SCHEDULE') = 'SCHEDULE'
  AND (cron_expression IS NULL OR btrim(cron_expression) = '');

UPDATE proactive_job
SET timezone = 'Asia/Seoul'
WHERE btrim(timezone) = '';
