-- V167: JobRunr 8 저장소 스키마 맞춤 — jobrunr_jobs_stats 뷰에 awaiting 열을 더한다 (WD-792) — PG V167 과 짝(MigrationParityTest).
--
-- 대상·이유는 PG V167 과 같다. Tibero 는 JobRunr Oracle 방언(OracleStorageProvider)을 쓰므로 모양은 jar 의
-- org/jobrunr/storage/sql/oracle/migrations/v016__alter_jobs_stats_add_awaiting_jobs.sql 을 그대로 따른다(FROM DUAL).
--
-- PG 와 다른 표기만:
--   - DROP 없이 CREATE OR REPLACE VIEW 한 문장으로 바꾼다. Oracle 계열은 OR REPLACE 로 열 목록이 달라져도 뷰를 바꿀 수 있고, 뷰에 준
--     권한을 그대로 둔다. DDL 하나라 암묵 커밋 중간 실패가 없고, 다시 돌려도 결과가 같다(멱등).
--   - 런타임 계정의 SELECT 권한은 TiberoRuntimeGrantCallback(AFTER_MIGRATE)이 빠진 것만 채우므로 여기서 GRANT 하지 않는다.
--   - 열 이름은 따옴표 없는 식별자라 대문자로 저장된다(V155 기준선 뷰와 같다).
CREATE OR REPLACE VIEW jobrunr_jobs_stats
AS
WITH job_stat_results AS (SELECT state, count(*) AS count
                          FROM jobrunr_jobs
                          GROUP BY state)
SELECT coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results), 0)                            AS total,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'AWAITING'), 0)   AS awaiting,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'SCHEDULED'), 0)  AS scheduled,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'ENQUEUED'), 0)   AS enqueued,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'PROCESSING'), 0) AS processing,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'PROCESSED'), 0)  AS processed,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'FAILED'), 0)     AS failed,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'SUCCEEDED'), 0)  AS succeeded,
       coalesce((SELECT cast(cast(value AS char(10)) AS decimal(10, 0))
                 FROM jobrunr_metadata jm
                 WHERE jm.id = 'succeeded-jobs-counter-cluster'),
                0)                                                                                        AS allTimeSucceeded,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'DELETED'), 0)    AS deleted,
       (SELECT count(*) FROM jobrunr_backgroundjobservers)                                                AS nbrOfBackgroundJobServers,
       (SELECT count(*) FROM jobrunr_recurring_jobs)                                                      AS nbrOfRecurringJobs
FROM DUAL;
