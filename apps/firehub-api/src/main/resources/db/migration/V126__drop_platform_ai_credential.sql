-- V126: AI 자격증명의 플랫폼 평면을 없앤다(#706, 2026-09-22 결정 B안).
--
-- AI 자격증명은 이제 테넌트 전용이다 — AiCredentialService.resolve()/read() 는 tenant_settings 의
-- ai.credential 행만 읽고, 행이 없으면 "미설정"(빈 sdk 문서 → isComplete()==false → 호출부의
-- 한국어 오류)으로 멈춘다. 그래서 system_settings 의 플랫폼 기본값은 더 이상 아무도 읽지 않는다.
--
-- 지우는 키:
--   - ai.credential                    : V122 가 만든 플랫폼 문서(이제 읽는 코드가 없다)
--   - ai.api_key / ai.cli_oauth_token  : V31/V41 시드 + V122 이전의 옛 플랫폼 자격증명(암호문)
--   - ai.agent_type                    : V40 시드(옛 평면 키)
-- 이 넷을 읽거나 쓰는 코드(SettingsService 화이트리스트·검증·암호화, 플랫폼 컨트롤러)는 같은
-- 변경에서 함께 제거됐다 — 코드가 남아 있는데 행만 지우면 플랫폼 설정 화면이 빈 값을 다시 저장한다.
--
-- 복사 마이그레이션은 하지 않는다(의도). 플랫폼 값을 각 테넌트로 복사하면 "테넌트가 모르는 사이
-- 플랫폼 계정으로 과금"이 테넌트 행의 이름으로 계속된다 — 그것이 이 결정이 없애려는 상태다.
-- 자기 행이 없는 테넌트는 이 마이그레이션 적용 순간부터 AI 가 명확한 오류로 멈추고, 테넌트
-- 관리자가 직접 설정한다.
--
-- tenant_settings 에 남은 옛 3키(V122 가 지우지 않은 값)는 여기서 건드리지 않는다 — 읽기 경로가
-- 테넌트 오버라이드 화이트리스트(SettingsOverridePolicy)로 이미 걸러내 무해하다.
--
-- 멱등: 행이 없으면 0행 삭제로 끝난다.

DELETE FROM system_settings
 WHERE key IN ('ai.credential', 'ai.api_key', 'ai.cli_oauth_token', 'ai.agent_type');
