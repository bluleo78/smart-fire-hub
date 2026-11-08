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

/**
 * 조회에 쓸 수 있는 id 인가 — 양의 정수만 참이다 (WD-667).
 * 상세 조회 훅이 `id == null` 만 걸러 0·NaN 이 통과했다. 형식 없는 값을 Number() 로 바꾼 NaN 은
 * `/jobs/NaN/executions/NaN` 을 부르고, 쿼리 키 해시에서 null 로 바뀌어 꺼진 관찰자와 같은 캐시를 나눠 썼다.
 * 타입 가드라 `isValidId(id) ? () => api(id) : skipToken` 의 조회 함수 안에서 id 가 number 로 좁혀진다.
 */
export function isValidId(id: number | null | undefined): id is number {
  return typeof id === 'number' && Number.isInteger(id) && id > 0;
}

/**
 * 형식 없는 값(메시지 content 등)을 조회용 id 로 읽는다 (WD-667) — 양의 정수 숫자이거나 숫자만으로 된 문자열이면 그 값,
 * 아니면 null. `Number('abc')` 의 NaN·`Number('')` 의 0 이 조회로 새지 않게 parseRouteId 와 같은 규칙을 쓴다.
 */
export function toValidId(raw: unknown): number | null {
  if (typeof raw === 'number') return isValidId(raw) && Number.isSafeInteger(raw) ? raw : null;
  if (typeof raw === 'string') return parseRouteId(raw) ?? null;
  return null;
}
