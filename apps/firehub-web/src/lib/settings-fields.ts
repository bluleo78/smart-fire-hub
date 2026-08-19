import type { ResolvedSettingResponse } from '../types/settings';

/**
 * 테넌트가 자기 값으로 덮어쓸 수 있는 AI 설정 키.
 * 백엔드 `SettingsOverridePolicy` 화이트리스트와 동일하며, `PUT /settings` 는 이 6키 외의 키가
 * 오면 키 이름을 명시해 400 으로 거부한다.
 *
 * 화면 표시(배지·disabled)는 서버가 내려주는 `tenantEditable` 플래그를 따른다. 이 상수는
 * 두 자리에서만 쓴다:
 *  1. 저장 페이로드 필터 — "거부될 필드는 저장 시도 자체를 하지 않는다"는 두 번째 관문.
 *  2. 응답에 아예 없는 키의 판정 폴백 — 플랫폼 시드 행도 오버라이드도 없는 키
 *     (`ai.session_max_tokens`)는 목록에 나타나지 않아 서버가 플래그를 줄 기회가 없다.
 * 화이트리스트 사본을 하나 더 만드는 게 아니라 같은 상수를 재사용하는 것이므로, 정책이 바뀌면
 * 이 배열 한 곳만 고치면 된다.
 */
export const TENANT_EDITABLE_AI_KEYS = [
  'ai.system_prompt',
  'ai.model',
  'ai.temperature',
  'ai.max_turns',
  'ai.max_tokens',
  'ai.session_max_tokens',
] as const;

export type TenantEditableAiKey = (typeof TENANT_EDITABLE_AI_KEYS)[number];

/** 이 키가 테넌트 편집 허용 키인지 — 서버 응답에 해당 키가 없을 때의 폴백 판정에 쓴다. */
export function isTenantEditableAiKey(key: string): key is TenantEditableAiKey {
  return (TENANT_EDITABLE_AI_KEYS as readonly string[]).includes(key);
}

/**
 * 서버 응답에 키가 없을 때 <b>실제로 적용되는</b> 코드 수준 기본값.
 * 출처는 백엔드 `AiAgentProxyService`(L226~232) 의 `getOrDefault`/`parseIntSafe` 3인자다 —
 * `system_settings` 에 행이 없고 오버라이드도 없으면 이 값으로 AI 호출이 나간다.
 *
 * 이 맵이 필요한 이유: `ai.session_max_tokens` 는 어떤 마이그레이션도 시드하지 않아 프리픽스
 * 조회 결과에 <b>아예 나타나지 않는다</b>(플랫폼 행도 오버라이드 행도 없으므로 합집합에서 빠진다).
 * 그때 화면을 비워 두면 "아무 값도 적용되지 않는다"고 읽히는데 실제로는 50000 이 적용되고 있어
 * 사실과 다르다. 그래서 값도 이 기본값으로 보여주고 배지도 "내장 기본값"으로 구분한다.
 *
 * `ai.system_prompt` 은 여기 없다 — 백엔드가 null 을 그대로 넘겨 코드 기본값이 존재하지 않는다.
 * 비밀 키(api_key/cli_oauth_token)도 없다.
 */
export const BUILTIN_AI_DEFAULTS: Record<string, string> = {
  'ai.agent_type': 'sdk',
  'ai.model': 'claude-sonnet-5',
  'ai.max_turns': '10',
  'ai.temperature': '1.0',
  'ai.max_tokens': '16384',
  'ai.session_max_tokens': '50000',
};

/**
 * 설정 필드 하나가 화면에서 취하는 다섯 가지 상태.
 * - `inherited`: 편집 가능 + 플랫폼(DB) 기본값을 그대로 쓰는 중
 * - `overridden`: 편집 가능 + 테넌트 재정의가 적용된 중
 * - `locked`: 플랫폼 전용(테넌트 편집 불가)
 * - `builtin-default`: DB 행이 없고 코드 수준 기본값이 적용되는 중(값은 실재한다)
 * - `no-default`: DB 행도 코드 기본값도 없음(적용되는 값이 정말 없다)
 */
export type SettingFieldState =
  | 'inherited'
  | 'overridden'
  | 'locked'
  | 'builtin-default'
  | 'no-default';

/**
 * 서버 응답 1건으로 필드 상태를 판정한다. 배지·disabled·저장 대상 판단이 모두 이 한 함수를 거쳐
 * 서로 어긋나지 않게 한다.
 *
 * `setting` 이 undefined 인 경우(=응답 목록에 그 키가 없음)는 "플랫폼 행도 오버라이드도 없음"을
 * 뜻한다. 이때만 편집 가능 여부를 화이트리스트 상수로 폴백한다 — <b>없는 키를 잠금으로 떨어뜨리면
 * 안 된다</b>. 응답에서 빠지는 키(`ai.session_max_tokens`)는 하필 편집 허용 6키 중 하나라서,
 * 조회 실패를 falsy 로 흘리면 편집 가능한 항목이 잠긴 것으로 뒤집힌다.
 * `value === null` 도 같은 의미(행은 있으나 값이 없음)로 취급한다. 반면 `value === ''` 는
 * "빈 문자열이 실제 기본값"인 정상 상태이므로 `inherited` 다 — 시스템 프롬프트처럼 빈 값이
 * 합법인 키가 있어 둘을 섞으면 안 된다.
 *
 * DB 값이 없을 때 코드 기본값이 있으면 `builtin-default`(값이 적용되고 있음), 없으면
 * `no-default`(정말 적용되는 값이 없음)로 갈라 놓는다.
 */
export function resolveSettingFieldState(
  key: string,
  setting: ResolvedSettingResponse | undefined,
): SettingFieldState {
  const editable = setting ? setting.tenantEditable : isTenantEditableAiKey(key);
  if (!editable) return 'locked';
  if (!setting || setting.value === null) {
    return BUILTIN_AI_DEFAULTS[key] !== undefined ? 'builtin-default' : 'no-default';
  }
  return setting.overridden ? 'overridden' : 'inherited';
}

/** 응답 배열을 키로 인덱싱한다 — 필드 단위 조회(배지 상태·description 폴백)에 쓴다. */
export function indexSettingsByKey(
  settings: ResolvedSettingResponse[],
): Record<string, ResolvedSettingResponse> {
  return Object.fromEntries(settings.map((s) => [s.key, s]));
}
