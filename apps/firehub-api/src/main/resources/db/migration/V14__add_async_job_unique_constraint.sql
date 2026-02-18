-- Enforce one active job per (job_type, resource, resource_id) atomically
-- Prevents TOCTOU race condition on concurrent import checks
CREATE UNIQUE INDEX idx_async_job_active_unique ON async_job(job_type, resource, resource_id)
    WHERE stage NOT IN ('COMPLETED', 'FAILED');
