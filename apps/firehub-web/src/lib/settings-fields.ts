import type { ResolvedSettingResponse } from '../types/settings';

/**
 * 테넌트가 자기 값으로 덮어쓸 수 있는 설정 키.
 * 백엔드 `SettingsOverridePolicy` 화이트리스트와 동일하며, `PUT /settings` 는 이 12키 외의 키가
 * 오면 키 이름을 명시해 400 으로 거부한다.
 *
 * <b>이 사본은 P7-c1 에서 6키 → 12키로 두 배가 됐다</b>(`smtp.*` 6키 재분류). 코드젠도 계약
 * 테스트도 없다는 사실은 아래에 그대로이고, 사본이 커진 만큼 어긋날 표면도 커졌다는 뜻이다.
 *
 * <b>AI 전용이 아니다</b>: P7-c1 이 `smtp.*` 6키를 테넌트 오버라이드 허용으로 재분류하면서
 * 목록이 AI 6 + SMTP 6 이 됐다. 예전 이름(`TENANT_EDITABLE_AI_KEYS`)을 그대로 두면 SMTP 키를
 * 넣는 것이 이름과 어긋나고, 넣지 않으면 아래 폴백이 SMTP 키를 `locked` 로 떨어뜨린다.
 *
 * 화면 표시(배지·disabled)는 서버가 내려주는 `tenantEditable` 플래그를 따른다. 이 상수는
 * <b>응답에 아예 없는 키의 판정 폴백</b> 한 자리에만 쓴다 — 플랫폼 시드 행도 오버라이드도 없는 키
 * (`ai.session_max_tokens`)는 목록에 나타나지 않아 서버가 플래그를 줄 기회가 없다.
 * SMTP 6키는 V42 가 전부 시드하므로 지금은 이 폴백에 도달하지 않지만 그래도 담는다 —
 * 도달 불가에 기대는 정합은 정합이 아니고, 다음 사람이 SMTP 키를 하나 추가하면서 시드를 잊으면
 * 그 필드가 <b>조용히 잠긴 것으로</b> 보인다(`ai.session_max_tokens` 가 정확히 그 모양이었다).
 *
 * <b>이것은 백엔드 화이트리스트의 두 번째 사본이 맞다</b> — 다른 언어에 있고, `SettingsOverridePolicy`
 * 와 이어 주는 코드젠도 계약 테스트도 없다(같은 파일 안 리터럴과 대조하는 테스트가 있을 뿐이고,
 * 그 테스트는 자기 한계를 주석에 밝혀 두었다). 예전 문구는 "사본이 아니라 재사용"이라고 적어
 * 존재하지 않는 보호가 있는 것처럼 보이게 했다 — 이 프로젝트의 "주석으로 동기화하는 중복은 이미
 * 어긋난 중복" 교훈이 정작 이 파일에 적용돼 있지 않았다.
 *
 * 실제 방어는 이 상수가 아니라 서버가 내리는 `tenantEditable` 플래그다.
 */
export const TENANT_EDITABLE_KEYS = [
  'ai.system_prompt',
  'ai.model',
  'ai.temperature',
  'ai.max_turns',
  'ai.max_tokens',
  'ai.session_max_tokens',
  'smtp.host',
  'smtp.port',
  'smtp.username',
  'smtp.password',
  'smtp.starttls',
  'smtp.from_address',
] as const;

export type TenantEditableKey = (typeof TENANT_EDITABLE_KEYS)[number];

/** 이 키가 테넌트 편집 허용 키인지 — 서버 응답에 해당 키가 없을 때의 폴백 판정에 쓴다. */
export function isTenantEditableKey(key: string): key is TenantEditableKey {
  return (TENANT_EDITABLE_KEYS as readonly string[]).includes(key);
}

/**
 * DB 행이 없어서 <b>화면이 대신 보여줘야 하는</b> 코드 수준 기본값.
 *
 * <b>왜 이 한 키뿐인가</b>: `ai.session_max_tokens` 는 <b>어떤 마이그레이션도 시드하지 않는다</b>
 * (`db/migration` 전체에서 이 키가 등장하는 파일이 없다). 그래서 프리픽스 조회 응답에서 유일하게
 * 빠지는 AI 키이고, 이 맵이 실제로 읽히는 유일한 키다. 나머지 AI 키는 전부 `system_settings` 에
 * non-null 값으로 시드돼 있어(V15/V31/V40/V41/V68) 항상 서버 값이 내려온다.
 * 그런데도 값은 적용된다 — 백엔드가 이 키에 코드 폴백(50000)을 쓰기 때문이다. 화면을 비워 두면
 * "아무 값도 적용되지 않는다"고 읽히므로 값과 "내장 기본값" 배지를 함께 보여준다.
 *
 * <b>백엔드 기본값 목록을 여기 복사하지 않는 이유</b>: 백엔드가 기본값을 바꿔도 이쪽은 아무것도
 * 깨지지 않는다 — 주석으로 동기화하는 중복은 이미 어긋난 중복이고, 그 결과는 "적용되지도 않는
 * 숫자를 화면이 자신 있게 보여주는 것"이다. 도달하지도 않는 키까지 들고 있으면 그 위험만 커지고
 * 얻는 것은 없다. 이 한 줄은 시드 행이 없다는 사실 때문에 불가피한 예외다.
 */
export const BUILTIN_AI_DEFAULTS: Record<string, string> = {
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
 * 안 된다</b>. 응답에서 빠지는 키(`ai.session_max_tokens`)는 하필 편집 허용 키 중 하나라서,
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
  const editable = setting ? setting.tenantEditable : isTenantEditableKey(key);
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
