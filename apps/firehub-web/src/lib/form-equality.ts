/**
 * 폼 값 비교 유틸 (WD-642)
 * - 미저장 입력 이탈 가드가 「지금 값이 기본값과 다른가」를 직접 판단할 때 쓴다.
 * - RHF formState.isDirty 는 setValue 에 shouldDirty 를 줘야 갱신돼(RHF 7.71 에서 확인), 한 곳이라도 빠뜨리면
 *   가드가 조용히 꺼진다. 값 자체를 비교하면 그런 누락과 무관하게 판정할 수 있다.
 */

/** 빈 값으로 같이 취급할 값 — 미입력(undefined)·지운 값(null)·빈 문자열·비운 숫자 입력(valueAsNumber 의 NaN) */
function normalizeEmpty(value: unknown): unknown {
  if (value === undefined || value === null || value === '') return null;
  if (typeof value === 'number' && Number.isNaN(value)) return null;
  return value;
}

/** 키를 하나씩 비교해도 되는 평범한 객체인가 — Date·File·Map 등은 자기 키가 없어 키 비교로는 늘 같아 보인다 */
function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object') return false;
  const proto = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}

/**
 * 폼 값 두 개가 사용자 입력 관점에서 같은가.
 * 키 순서·없는 키·빈 값(undefined/null/''/NaN) 차이는 무시하고, 배열·평범한 객체는 재귀로 비교한다.
 * Date 는 시각으로, 그 밖의 평범하지 않은 객체(File·Map 등)는 같은 참조일 때만 같다고 본다.
 */
export function isSameFormValue(a: unknown, b: unknown): boolean {
  const x = normalizeEmpty(a);
  const y = normalizeEmpty(b);
  if (Array.isArray(x) || Array.isArray(y)) {
    if (!Array.isArray(x) || !Array.isArray(y) || x.length !== y.length) return false;
    return x.every((item, i) => isSameFormValue(item, y[i]));
  }
  if (x instanceof Date && y instanceof Date) return x.getTime() === y.getTime();
  if (!isPlainObject(x) || !isPlainObject(y)) return Object.is(x, y);
  const keys = new Set([...Object.keys(x), ...Object.keys(y)]);
  for (const key of keys) {
    if (!isSameFormValue(x[key], y[key])) return false;
  }
  return true;
}
