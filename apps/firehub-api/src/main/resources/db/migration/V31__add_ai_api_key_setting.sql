INSERT INTO system_settings (key, value, description, updated_at)
VALUES ('ai.api_key', '', 'Anthropic API Key (AES-256-GCM encrypted)', NOW())
ON CONFLICT (key) DO NOTHING;
