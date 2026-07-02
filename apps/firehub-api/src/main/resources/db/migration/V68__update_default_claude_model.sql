-- Claude 모델 세대 교체(sonnet-4-6 → sonnet-5)에 따라 ai.model 기본값 갱신
-- 사용자가 이미 다른 값으로 저장한 설정은 건드리지 않고, 구버전 기본값만 전환한다
UPDATE system_settings
SET value = 'claude-sonnet-5'
WHERE key = 'ai.model' AND value = 'claude-sonnet-4-6';
