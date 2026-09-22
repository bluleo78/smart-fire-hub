import type { ResolvedSettingResponse } from '../types/settings';

/**
 * 응답 목록에 <b>아예 없는 키</b>의 편집 가능 여부를 판정하는 폴백 목록 — 지금은 SMTP 6키뿐이다.
 *
 * 화면 표시(배지·disabled)와 저장 대상 판정의 권위는 서버가 내려주는 `tenantEditable` 플래그다.
 * 이 상수는 서버가 플래그를 줄 기회가 없는 경우(응답에서 키가 빠진 경우) 한 자리에만 쓴다.
 * SMTP 6키는 V42 가 전부 시드하므로 평소에는 이 폴백에 도달하지 않지만, 누군가 SMTP 키를 추가하면서
 * 시드를 잊으면 그 필드가 <b>조용히 잠긴 것으로</b> 보이는 것을 막기 위해 담아 둔다.
 *
 * AI 동작 설정(`ai.*`)은 여기 없다 — 테넌트 전용 설정이라 서버가 6키를 항상 내려주고(저장값이
 * 없으면 코드 기본값), AI 탭은 상태 배지를 그리지 않는다. `ai.credential` 도 전용 엔드포인트
 * (`GET/PUT /settings/ai-credential`)만 쓰므로 이 목록과 무관하다.
 *
 * 백엔드 `SettingsOverridePolicy` 화이트리스트의 부분 사본이며, 둘을 잇는 코드젠·계약 테스트는 없다.
 */
export const TENANT_EDITABLE_KEYS = [
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
 * 설정 필드 하나가 화면에서 취하는 세 가지 상태(SMTP·임베딩 탭 전용).
 * - `inherited`: 편집 가능 + 플랫폼(DB) 기본값을 그대로 쓰는 중
 * - `overridden`: 편집 가능 + 테넌트 재정의가 적용된 중
 * - `locked`: 플랫폼 전용(테넌트 편집 불가)
 */
export type SettingFieldState = 'inherited' | 'overridden' | 'locked';

/**
 * 서버 응답 1건으로 필드 상태를 판정한다. 배지·disabled·저장 대상 판단이 모두 이 한 함수를 거쳐
 * 서로 어긋나지 않게 한다.
 *
 * `setting` 이 undefined 인 경우(=응답 목록에 그 키가 없음)만 편집 가능 여부를 화이트리스트 상수로
 * 폴백한다 — <b>없는 키를 잠금으로 떨어뜨리면 안 된다</b>. 조회 miss 를 falsy 로 흘리면 편집 가능한
 * 항목이 잠긴 것으로 뒤집힌다. 값이 없거나 빈 문자열이어도(오버라이드가 없으면) `inherited` 다.
 */
export function resolveSettingFieldState(
  key: string,
  setting: ResolvedSettingResponse | undefined,
): SettingFieldState {
  const editable = setting ? setting.tenantEditable : isTenantEditableKey(key);
  if (!editable) return 'locked';
  return setting?.overridden ? 'overridden' : 'inherited';
}

/** 응답 배열을 키로 인덱싱한다 — 필드 단위 조회(배지 상태·description 폴백)에 쓴다. */
export function indexSettingsByKey(
  settings: ResolvedSettingResponse[],
): Record<string, ResolvedSettingResponse> {
  return Object.fromEntries(settings.map((s) => [s.key, s]));
}
