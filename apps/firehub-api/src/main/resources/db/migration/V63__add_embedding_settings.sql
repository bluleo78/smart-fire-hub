-- 임베딩 provider 설정 (기본: 호스트 Ollama + bge-m3, 차원 1024)
INSERT INTO system_settings (key, value, description) VALUES
  ('embedding.provider', 'OLLAMA', '임베딩 provider (OLLAMA, VOYAGE, OPENAI)'),
  ('embedding.model', 'bge-m3', '임베딩 모델 ID'),
  ('embedding.base_url', 'http://host.docker.internal:11434', '임베딩 서비스 base URL'),
  ('embedding.api_key', '', '임베딩 API 키 (Ollama 로컬은 불필요, 저장 시 암호화)')
ON CONFLICT (key) DO NOTHING;
