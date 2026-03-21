INSERT INTO system_settings (key, value, description, updated_at)
VALUES ('ai.cli_oauth_token', '', 'Claude Code CLI OAuth 토큰 (구독 인증용)', NOW())
ON CONFLICT (key) DO NOTHING;
