-- V158: 실행 주기(cron) 없이 「켜짐」으로 저장된 정기(SCHEDULE) 스마트 작업을 끄고, 공백 시간대로 저장된 작업을 기본 시간대로 되돌린다
-- (WD-607) — PG V158 과 짝(MigrationParityTest).
--
-- 대상·이유는 PG V158 과 같다. Tibero 에서는 빈 문자열이 NULL 이고 TRIM('   ') 도 NULL 이라 「NULL·빈 값·공백」을
-- TRIM(...) IS NULL 하나로 판정한다. enabled 는 NUMBER(1)(1=켜짐) 이다. timezone 은 NOT NULL DEFAULT 'Asia/Seoul'(V155)이라 빈 값은
-- 저장될 수 없고 공백만 남을 수 있다. RLS 가 없어 모든 행이 대상이다. DML 이라 실패하면 롤백되고, 다시 돌려도 결과가 같다(멱등).
UPDATE proactive_job
SET enabled = 0
WHERE enabled = 1
  AND NVL(trigger_type, 'SCHEDULE') = 'SCHEDULE'
  AND TRIM(cron_expression) IS NULL;

UPDATE proactive_job
SET timezone = 'Asia/Seoul'
WHERE TRIM(timezone) IS NULL;
