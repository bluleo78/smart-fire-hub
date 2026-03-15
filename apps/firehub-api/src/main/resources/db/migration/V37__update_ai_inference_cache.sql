-- ai_inference_cache: 고정 컬럼(label/confidence/reason) → 동적 JSONB로 변경
ALTER TABLE ai_inference_cache DROP COLUMN IF EXISTS label;
ALTER TABLE ai_inference_cache DROP COLUMN IF EXISTS confidence;
ALTER TABLE ai_inference_cache DROP COLUMN IF EXISTS reason;
ALTER TABLE ai_inference_cache ADD COLUMN IF NOT EXISTS result_json JSONB NOT NULL DEFAULT '{}';
