-- 실행 시 실제 전달된 채널 목록을 저장하는 컬럼 추가
ALTER TABLE proactive_job_execution ADD COLUMN IF NOT EXISTS delivered_channels TEXT;
