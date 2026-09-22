-- V127: 플랫폼 설정에서 AI 설정을 완전히 없앤다(#706 후속, 2026-09-22).
--
-- AI 설정(ai.*)은 이제 전부 테넌트 전용이다.
--   - 자격증명(ai.credential)        : AiCredentialService 가 tenant_settings 에서만 읽는다(V126).
--   - 동작 6키(ai.model/max_turns/system_prompt/temperature/max_tokens/session_max_tokens)
--                                    : SettingsService 가 "테넌트 값 → 코드 기본값
--                                      (AiBehaviorDefaults)"으로 해석한다. system_settings 는 보지 않는다.
-- 플랫폼 설정 API 도 ai.* 를 쓰지도 내보내지도 않으므로 system_settings 의 ai.* 행은 읽는 곳이 없다.
--
-- 코드 기본값은 옛 플랫폼 시드와 같다(V15/V68/V69) — 시드를 그대로 둔 환경에서는 동작이 바뀌지
-- 않는다. 운영자가 플랫폼 값을 바꿔 둔 환경에서는, 그 값 대신 코드 기본값이 적용된다(의도 —
-- 필요한 워크스페이스는 자기 설정에서 값을 저장한다). 테넌트에 복사하지 않는 이유도 같다.
--
-- tenant_settings 의 ai.* 행(각 워크스페이스가 저장한 값)은 건드리지 않는다.
-- V126 이 지운 키와 겹쳐도 무해하다(멱등: 행이 없으면 0행 삭제).

DELETE FROM system_settings WHERE key LIKE 'ai.%';
