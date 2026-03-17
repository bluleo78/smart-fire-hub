ALTER TABLE pipeline_step ADD COLUMN IF NOT EXISTS python_config JSONB;

COMMENT ON COLUMN pipeline_step.python_config IS 'Python 스텝 설정 (outputColumns 등)';
