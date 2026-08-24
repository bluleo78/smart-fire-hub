/**
 * 테넌트가 자기 값으로 재정의할 수 있는 키 12개.
 *
 * <b>이것은 Java `SettingsOverridePolicy.TENANT_OVERRIDABLE` 의 사본이다.</b>
 * 원본: apps/firehub-api/src/main/java/com/smartfirehub/settings/service/SettingsOverridePolicy.java
 *
 * 사본을 두는 이유: `GET /api/platform/settings` 가 돌려주는 `SettingResponse` 에는
 * `tenantOverridable` 도 `overridden` 도 **없다**(그건 테넌트 평면의 `ResolvedSettingResponse` 다).
 * 서버가 알려주지 않으므로 화면이 판정할 근거를 스스로 가져야 한다.
 *
 * <b>이것은 드리프트 위험이고, 정직하게 인정한다.</b> 완화는 세 가지다:
 * (a) 상수를 이 파일 한 곳에만 둔다, (b) 위에 Java 원본 경로를 박는다,
 * (c) 백로그로 `SettingResponse.tenantOverridable` 추가를 제안한다 — 그러면 이 파일이 사라진다.
 */
export const TENANT_OVERRIDABLE_KEYS = [
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

export type TenantOverridableKey = (typeof TENANT_OVERRIDABLE_KEYS)[number];

export function isTenantOverridable(key: string): key is TenantOverridableKey {
  return (TENANT_OVERRIDABLE_KEYS as readonly string[]).includes(key);
}
