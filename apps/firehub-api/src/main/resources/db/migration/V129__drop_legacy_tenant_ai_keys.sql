-- V129: 워크스페이스 평면에 남은 옛 AI 평면 3키를 지운다(#699, #693 후속, 2026-09-23).
--
-- V122 가 이 3키(API 키·OAuth 토큰 암호문, 에이전트 유형 평문)를 워크스페이스별 JSON 문서
-- ai.credential 로 옮겼지만, 롤백 여지를 남기려고 원본 행은 한 릴리스 미뤄 그대로 뒀다.
-- 그 뒤로 이 행을 읽는 코드는 없다(자격증명은 ai.credential 만 읽고, 범용 설정 경로는
-- SettingsOverridePolicy 화이트리스트가 거른다). 동작 변화 없이, 쓰이지 않는 비밀값 사본만 없앤다.
-- 플랫폼 평면의 같은 키는 V126 이 이미 지웠다. 다른 ai.* 행은 건드리지 않는다.
--
-- 테이블 소유자로 실행되고 tenant_settings 는 FORCE RLS 가 아니므로 모든 워크스페이스 행이 대상이다
-- (V122 가 같은 전제로 전 테넌트를 변환했다).

DELETE FROM tenant_settings
 WHERE key IN ('ai.api_key', 'ai.cli_oauth_token', 'ai.agent_type');
