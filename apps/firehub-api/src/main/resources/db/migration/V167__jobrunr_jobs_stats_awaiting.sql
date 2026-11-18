-- V167: JobRunr 8 저장소 스키마 맞춤 — jobrunr_jobs_stats 뷰에 awaiting 열을 더한다 (WD-792).
--
-- 왜: Spring Boot 4 로 올리며 JobRunr 를 7.5.3 → 8.8.2(jobrunr-spring-boot-4-starter)로 올렸다. 8.x 는 jar 의
-- org/jobrunr/storage/sql/common/migrations/v016__alter_jobs_stats_add_awaiting_jobs.sql 로 통계 뷰에 AWAITING 상태 집계 열을
-- 더하고, 통계 조회(StorageProvider.getJobStats — staging 정리 게이트가 쓴다)가 그 열을 읽는다. 우리는 테이블을 Flyway 가 관리하고
-- JobRunr 의 DDL 을 끄므로(jobrunr.database.skip-create=true — JobRunr 7 때 이름은 org.jobrunr.*, V85) JobRunr 대신 여기서 같은 변경을 한다. 테이블 열은 바뀌지 않는다
-- (7.x 의 v015 까지와 같다).
--
-- 모양: JobRunr v016(common) 과 같은 집계 — 상태별 COUNT 뒤 합. 열 순서도 JobRunr 와 같게 둔다(total 다음 awaiting).
-- PG 의 CREATE OR REPLACE VIEW 는 기존 열 사이에 새 열을 끼울 수 없어 DROP 뒤 다시 만든다. 이 뷰에 기대는 다른 객체는 없고,
-- PG DDL 은 트랜잭션 안이라 중간에 실패하면 통째로 되돌려진다. 다시 만든 뷰에는 V85 와 같은 권한을 다시 준다(DROP 이 권한도 지운다 —
-- V83 의 기본 권한은 새 객체에 붙지만 V85 처럼 명시해 의도를 분명히 한다).
-- Tibero 짝: db/migration-tibero/V167 (MigrationParityTest).

DROP VIEW IF EXISTS public.jobrunr_jobs_stats;

CREATE VIEW public.jobrunr_jobs_stats AS
WITH job_stat_results AS (
    SELECT state, count(*) AS count
    FROM public.jobrunr_jobs
    GROUP BY state
)
SELECT coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results), 0)                            AS total,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'AWAITING'), 0)   AS awaiting,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'SCHEDULED'), 0)  AS scheduled,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'ENQUEUED'), 0)   AS enqueued,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'PROCESSING'), 0) AS processing,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'PROCESSED'), 0)  AS processed,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'FAILED'), 0)     AS failed,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'SUCCEEDED'), 0)  AS succeeded,
       coalesce((SELECT cast(cast(jm.value AS char(10)) AS decimal(10, 0))
                 FROM public.jobrunr_metadata jm
                 WHERE jm.id = 'succeeded-jobs-counter-cluster'),
                0)                                                                                        AS alltimesucceeded,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'DELETED'), 0)    AS deleted,
       (SELECT count(*) FROM public.jobrunr_backgroundjobservers)                                         AS nbrofbackgroundjobservers,
       (SELECT count(*) FROM public.jobrunr_recurring_jobs)                                               AS nbrofrecurringjobs;

GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE public.jobrunr_jobs_stats TO app_tenant;
