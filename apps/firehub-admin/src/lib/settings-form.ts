import {
  ALL_SETTING_KEYS,
  SETTING_CATALOG,
  validateSettingValue,
} from '@/lib/settings-catalog';

/**
 * 저장 전 폼 전체 검증. 검증 실패한 키만 담긴 맵을 돌려준다(빈 맵 = 통과).
 * 컴포넌트가 아니라 순수 함수이므로 `.ts` lib 모듈에 둔다 — Task 10 의 저장 흐름이 소비한다.
 */
export function validateForm(
  form: Record<string, string>,
  original: Record<string, string>,
): Record<string, string> {
  const found: Record<string, string> = {};
  for (const key of ALL_SETTING_KEYS) {
    const spec = SETTING_CATALOG[key];
    const value = form[key] ?? '';
    // 비밀 키는 비워 두는 것이 "유지" 의미라 검사하지 않는다(Task 10 이 이 규칙을 쓴다).
    if (spec.secret && value === '') continue;
    if (!spec.secret && value === original[key]) continue;
    const message = validateSettingValue(key, value);
    if (message) found[key] = message;
  }
  return found;
}
