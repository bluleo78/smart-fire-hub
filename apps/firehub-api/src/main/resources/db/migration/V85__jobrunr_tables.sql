-- V85: JobRunr 테이블을 Flyway 관리로 옮긴다.
-- 왜: JobRunr 는 기본적으로 기동 시 런타임 데이터소스로 CREATE TABLE 을 실행한다. 런타임 롤이
-- 비특권 app_tenant 로 바뀌면 public 스키마 CREATE 권한이 없어 기동이 실패한다. 소유자 롤로 도는
-- Flyway 가 미리 만들고, application.yml 에서 org.jobrunr.database.skip-create=true 로 JobRunr 의
-- DDL 을 끈다. JobRunr 버전을 올릴 때는 스키마 변경 여부를 확인해 새 마이그레이션을 추가해야 한다.
-- 기존 DB 에는 이미 테이블이 있으므로 전 구문이 멱등(IF NOT EXISTS)이어야 한다.

-- pg_dump 가 PRIMARY KEY 를 별도의 ALTER TABLE ... ADD CONSTRAINT 로 내보내는데, PostgreSQL 16 은
-- ADD CONSTRAINT 에 IF NOT EXISTS 를 지원하지 않는다. 이미 존재하는 DB 에서 재실행하면 에러가 나므로
-- 여기서는 PRIMARY KEY 를 CREATE TABLE 본문에 인라인으로 접어 넣어(fold) 문제를 없앤다.

CREATE TABLE IF NOT EXISTS public.jobrunr_backgroundjobservers (
    id character(36) NOT NULL PRIMARY KEY,
    workerpoolsize integer NOT NULL,
    pollintervalinseconds integer NOT NULL,
    firstheartbeat timestamp(6) without time zone NOT NULL,
    lastheartbeat timestamp(6) without time zone NOT NULL,
    running integer NOT NULL,
    systemtotalmemory bigint NOT NULL,
    systemfreememory bigint NOT NULL,
    systemcpuload numeric(3,2) NOT NULL,
    processmaxmemory bigint NOT NULL,
    processfreememory bigint NOT NULL,
    processallocatedmemory bigint NOT NULL,
    processcpuload numeric(3,2) NOT NULL,
    deletesucceededjobsafter character varying(32),
    permanentlydeletejobsafter character varying(32),
    name character varying(128)
);

CREATE TABLE IF NOT EXISTS public.jobrunr_jobs (
    id character(36) NOT NULL PRIMARY KEY,
    version integer NOT NULL,
    jobasjson text NOT NULL,
    jobsignature character varying(512) NOT NULL,
    state character varying(36) NOT NULL,
    createdat timestamp without time zone NOT NULL,
    updatedat timestamp without time zone NOT NULL,
    scheduledat timestamp without time zone,
    recurringjobid character varying(128)
);

CREATE TABLE IF NOT EXISTS public.jobrunr_metadata (
    id character varying(156) NOT NULL PRIMARY KEY,
    name character varying(92) NOT NULL,
    owner character varying(64) NOT NULL,
    value text NOT NULL,
    createdat timestamp without time zone NOT NULL,
    updatedat timestamp without time zone NOT NULL
);

CREATE TABLE IF NOT EXISTS public.jobrunr_recurring_jobs (
    id character(128) NOT NULL PRIMARY KEY,
    version integer NOT NULL,
    jobasjson text NOT NULL,
    createdat bigint DEFAULT '0'::bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS public.jobrunr_migrations (
    id character(36) NOT NULL PRIMARY KEY,
    script character varying(64) NOT NULL,
    installedon character varying(29) NOT NULL
);

-- 뷰는 참조하는 테이블(jobrunr_jobs, jobrunr_metadata, jobrunr_backgroundjobservers, jobrunr_recurring_jobs)
-- 이 먼저 만들어진 뒤에 생성해야 한다.
CREATE OR REPLACE VIEW public.jobrunr_jobs_stats AS
 WITH job_stat_results AS (
         SELECT jobrunr_jobs.state,
            count(*) AS count
           FROM public.jobrunr_jobs
          GROUP BY ROLLUP(jobrunr_jobs.state)
        )
 SELECT COALESCE(( SELECT job_stat_results.count
           FROM job_stat_results
          WHERE (job_stat_results.state IS NULL)), (0)::bigint) AS total,
    COALESCE(( SELECT job_stat_results.count
           FROM job_stat_results
          WHERE ((job_stat_results.state)::text = 'SCHEDULED'::text)), (0)::bigint) AS scheduled,
    COALESCE(( SELECT job_stat_results.count
           FROM job_stat_results
          WHERE ((job_stat_results.state)::text = 'ENQUEUED'::text)), (0)::bigint) AS enqueued,
    COALESCE(( SELECT job_stat_results.count
           FROM job_stat_results
          WHERE ((job_stat_results.state)::text = 'PROCESSING'::text)), (0)::bigint) AS processing,
    COALESCE(( SELECT job_stat_results.count
           FROM job_stat_results
          WHERE ((job_stat_results.state)::text = 'FAILED'::text)), (0)::bigint) AS failed,
    COALESCE(( SELECT job_stat_results.count
           FROM job_stat_results
          WHERE ((job_stat_results.state)::text = 'SUCCEEDED'::text)), (0)::bigint) AS succeeded,
    COALESCE(( SELECT ((jm.value)::character(10))::numeric(10,0) AS value
           FROM public.jobrunr_metadata jm
          WHERE ((jm.id)::text = 'succeeded-jobs-counter-cluster'::text)), (0)::numeric) AS alltimesucceeded,
    COALESCE(( SELECT job_stat_results.count
           FROM job_stat_results
          WHERE ((job_stat_results.state)::text = 'DELETED'::text)), (0)::bigint) AS deleted,
    ( SELECT count(*) AS count
           FROM public.jobrunr_backgroundjobservers) AS nbrofbackgroundjobservers,
    ( SELECT count(*) AS count
           FROM public.jobrunr_recurring_jobs) AS nbrofrecurringjobs;

CREATE INDEX IF NOT EXISTS jobrunr_bgjobsrvrs_fsthb_idx ON public.jobrunr_backgroundjobservers USING btree (firstheartbeat);
CREATE INDEX IF NOT EXISTS jobrunr_bgjobsrvrs_lsthb_idx ON public.jobrunr_backgroundjobservers USING btree (lastheartbeat);
CREATE INDEX IF NOT EXISTS jobrunr_job_created_at_idx ON public.jobrunr_jobs USING btree (createdat);
CREATE INDEX IF NOT EXISTS jobrunr_job_rci_idx ON public.jobrunr_jobs USING btree (recurringjobid);
CREATE INDEX IF NOT EXISTS jobrunr_job_scheduled_at_idx ON public.jobrunr_jobs USING btree (scheduledat);
CREATE INDEX IF NOT EXISTS jobrunr_job_signature_idx ON public.jobrunr_jobs USING btree (jobsignature);
CREATE INDEX IF NOT EXISTS jobrunr_jobs_state_updated_idx ON public.jobrunr_jobs USING btree (state, updatedat);
CREATE INDEX IF NOT EXISTS jobrunr_recurring_job_created_at_idx ON public.jobrunr_recurring_jobs USING btree (createdat);
CREATE INDEX IF NOT EXISTS jobrunr_state_idx ON public.jobrunr_jobs USING btree (state);

-- V83 이 public 스키마의 향후 테이블에 대해 app_tenant 에게 기본 권한(SELECT/INSERT/UPDATE/DELETE)을
-- 이미 부여했으므로(ALTER DEFAULT PRIVILEGES FOR ROLE app), 아래 GRANT 는 원칙적으로 자동 적용되지만
-- pg_dump 원본과 동일하게 명시적으로 남겨 의도를 분명히 한다. GRANT 는 재실행해도 에러가 나지 않는다.
GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE public.jobrunr_backgroundjobservers TO app_tenant;
GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE public.jobrunr_jobs TO app_tenant;
GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE public.jobrunr_metadata TO app_tenant;
GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE public.jobrunr_recurring_jobs TO app_tenant;
GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE public.jobrunr_jobs_stats TO app_tenant;
GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE public.jobrunr_migrations TO app_tenant;
