import { ALL_SETTING_KEYS, SETTING_CATALOG } from '@/lib/settings-catalog';

export interface SettingDiff {
  key: string;
  label: string;
  /** 비밀 키는 항상 빈 문자열이다 — 이전 값을 화면에 절대 싣지 않는다. */
  before: string;
  after: string;
  secret: boolean;
}

/**
 * 서버 `SettingsService.isMaskSentinel` 의 사본: `****` 로 시작하고 길이가 정확히 4 또는 8.
 *
 * `buildForm` 이 비밀 키를 항상 빈 문자열로 시작시키므로 지금 화면에서는 이 값이 폼에 들어올
 * 경로가 없다. 그래도 막아 두는 이유: 나중에 누가 "마스크를 입력창에 미리 채워 주자"고 고치는
 * 순간 조용한 결함 두 개가 동시에 열린다 — 길이가 4/8 이면 서버가 키를 드롭해 "204 성공 +
 * 아무 일도 없음"이 되고, 길이가 다르면 마스크 문자열이 진짜 비밀번호로 저장된다.
 */
function isMaskSentinel(value: string): boolean {
  return value.startsWith('****') && (value.length === 4 || value.length === 8);
}

/**
 * 저장 페이로드와 확인 다이얼로그용 diff 를 함께 만든다.
 *
 * <b>변경된 키만 보내는 이유</b>: `PUT /api/platform/settings` 는 부분 갱신을 허용하는데,
 * 미편집 키까지 보내면 그 키에 플랫폼 행이 새로 써져 상속 관계가 끊긴다.
 *
 * <b>비밀 키 3규칙</b>: 빈 채 = 키 없음(유지) / 새 값 = 그 값(교체) / cleared = 빈 문자열(삭제).
 * 마스크 문자열은 **만들지 않는다** — 서버 `isMaskSentinel` 이 그것을 "안 고쳤다"로 읽고 드롭해
 * "204 성공 + 아무 일도 없음"이 된다.
 */
export function buildSettingsPayload(
  form: Record<string, string>,
  original: Record<string, string>,
  cleared: Set<string>,
): { payload: Record<string, string>; diff: SettingDiff[] } {
  const payload: Record<string, string> = {};
  const diff: SettingDiff[] = [];

  for (const key of ALL_SETTING_KEYS) {
    const spec = SETTING_CATALOG[key];
    const value = form[key] ?? '';

    if (spec.secret) {
      if (cleared.has(key)) {
        payload[key] = '';
        diff.push({ key, label: spec.label, before: '', after: '값 삭제됨', secret: true });
      } else if (value !== '' && !isMaskSentinel(value)) {
        payload[key] = value;
        diff.push({ key, label: spec.label, before: '', after: '값 변경됨', secret: true });
      }
      continue;
    }

    const before = original[key] ?? '';
    if (value === before) continue;
    payload[key] = value;
    diff.push({ key, label: spec.label, before, after: value, secret: false });
  }

  return { payload, diff };
}
