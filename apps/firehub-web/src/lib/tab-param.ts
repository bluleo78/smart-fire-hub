/**
 * URL 쿼리(?tab=) 값을 허용된 탭 값으로 보정한다.
 *
 * 왜: `searchParams.get('tab') ?? '기본값'` 은 null 만 걸러서, 빈 값(`?tab=`)이나 존재하지 않는 탭 값이
 * 그대로 활성 탭이 되어 어떤 탭도 선택되지 않고 본문이 비던 문제가 있었다 (WD-871).
 * 허용 목록(대소문자 구분)에 없으면 기본 탭으로 되돌린다.
 */
export function resolveTabParam(
  value: string | null,
  validTabs: readonly string[],
  fallback: string,
): string {
  return value !== null && validTabs.includes(value) ? value : fallback;
}
