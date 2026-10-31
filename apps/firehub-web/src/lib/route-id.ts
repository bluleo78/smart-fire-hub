/**
 * 주소의 id 조각(`:id`·`:execId`)을 양의 정수로 읽는다 (WD-434).
 *
 * `Number('abc')` 는 NaN 이라 `!id` 로 「새로 만들기」가 되거나 `NaN && …` 이 「NaN」 글자로 그려지고,
 * `NaN !== NaN` 비교로 렌더 중 상태 조정이 끝없이 돈다. `parseInt('15abc')` 는 15 로 읽어 다른 항목을 연다.
 * 숫자만으로 된 조각만 id 로 받는다.
 * - 조각이 없으면 undefined (예: `/analytics/charts/new`)
 * - 숫자만이 아니거나 0·안전한 정수 범위 밖이면 null — 화면은 없는 id 와 같게 「찾을 수 없음」으로 다룬다
 */
export function parseRouteId(raw: string | undefined): number | null | undefined {
  if (raw === undefined) return undefined;
  if (!/^\d+$/.test(raw)) return null;
  const n = Number(raw);
  return Number.isSafeInteger(n) && n > 0 ? n : null;
}
