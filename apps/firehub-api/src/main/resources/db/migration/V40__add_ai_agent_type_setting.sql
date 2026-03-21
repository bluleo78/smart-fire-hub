INSERT INTO system_settings (key, value, description, updated_at)
VALUES ('ai.agent_type', 'sdk', 'AI 에이전트 유형 (sdk: Agent SDK, cli: Claude Code 구독, cli-api: Claude Code API)', NOW())
ON CONFLICT (key) DO NOTHING;
