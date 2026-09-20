/**
 * 테넌트가 자기 값으로 재정의할 수 있는 키 11개.
 *
 * <b>이것은 Java `SettingsOverridePolicy.TENANT_OVERRIDABLE` 의 부분 사본이다.</b>
 * 원본: apps/firehub-api/src/main/java/com/smartfirehub/settings/service/SettingsOverridePolicy.java
 * 원본은 12개(이 목록 + `ai.model`)를 갖는다 — `ai.model` 이 여기 없는 이유는 아래 참고.
 *
 * 사본을 두는 이유: `GET /api/platform/settings` 가 돌려주는 `SettingResponse` 에는
 * `tenantOverridable` 도 `overridden` 도 **없다**(그건 테넌트 평면의 `ResolvedSettingResponse` 다).
 * 서버가 알려주지 않으므로 화면이 판정할 근거를 스스로 가져야 한다.
 *
 * <b>`ai.model` 을 뺐다(fix round 2, 리뷰 지적 — round 1 은 "서버 사본이니 전부 옮긴다"며 넣어
 * 두고 정확성만 챙겼는데, 그 판단을 뒤집는다).</b> 이 배열의 유일한 소비자는 아래
 * `badgeKindOf()` 이고, 그 함수를 부르는 곳은 `SettingsPage.tsx` 의 범용 렌더러(`SETTING_CATALOG`
 * 의 키에 대해서만 배지를 그린다) 하나뿐이다. `ai.model` 은 Ruling #48 로 `ALL_SETTING_KEYS`
 * (=`SETTING_CATALOG` 의 정의역)에서 완전히 빠졌으므로, `badgeKindOf('ai.model')` 은 이
 * 앱에서 **호출될 수조차 없다** — 배열에 남겨 둬도 실행되는 코드가 없는 죽은 데이터이고,
 * "이 목록이 서버 화이트리스트 전체를 그대로 옮긴 것"이라는 인상만 줘 오히려 오해를 만든다.
 * (`ai.model` 자체는 서버에서 지금도 진짜 테넌트 재정의 가능 키다 — 다만 그 사실은
 * `AiCredentialSection` 의 책임 영역이고, 이 파일의 책임 영역이 아니다.)
 *
 * <b>이것은 드리프트 위험이고, 정직하게 인정한다.</b> 완화는 세 가지다:
 * (a) 상수를 이 파일 한 곳에만 둔다, (b) 위에 Java 원본 경로를 박는다,
 * (c) 백로그로 `SettingResponse.tenantOverridable` 추가를 제안한다 — 그러면 이 파일이 사라진다.
 */
export const TENANT_OVERRIDABLE_KEYS = [
  'ai.system_prompt',
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
