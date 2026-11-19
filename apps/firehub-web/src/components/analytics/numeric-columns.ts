/**
 * 쿼리 결과에서 숫자 컬럼을 고른다 — 차트 기본 축 추천과 히트맵 가중치 컬럼 목록(WD-354)이 같은 기준을 쓴다.
 *
 * 앞 20행 중 빈 값(isEmptyValue)을 뺀 값의 70% 넘게 숫자로 읽히면 숫자 컬럼이다. 서버는 DECIMAL 을 문자열로 보내므로 toPlotNumber 로 읽는다.
 * WD-841: 차트가 값을 읽는 규칙(toPlotNumber)과 같게 맞췄다 — 불리언은 숫자로 세지 않는다. 빈 값(null·''·공백)은
 * 분자·분모 모두에서 뺀다(isWeightColumn 과 같다) — 빈 칸이 섞인 DECIMAL 컬럼이 숫자 컬럼에서 밀려나지 않게. 값이 전부 비어 있으면 거짓.
 */
export function isNumericColumn(col: string, rows: Record<string, unknown>[]): boolean {
  const sample = rows.slice(0, 20).map((r) => r[col]).filter((v) => !isEmptyValue(v));
  if (sample.length === 0) return false;
  const nums = sample.filter((v) => toPlotNumber(v) !== null);
  return nums.length / sample.length > 0.7;
}

/**
 * 히트맵 가중치로 쓸 수 있는 컬럼인가(WD-354) — 비어 있지 않은 값이 하나 이상 있고 모두 숫자로 읽힌다.
 *
 * isNumericColumn 과 달리 NULL·빈 값은 세지 않는다 — 값이 드문드문 비어 있는 숫자 컬럼(측정값 등)도 가중치로
 * 고를 수 있어야 한다(비어 있는 행은 가중치 0 으로 그린다 — heatmap-weight.ts).
 * WD-841: 빈 값 판정은 isEmptyValue(공백뿐인 값 포함), 숫자 판정은 toPlotNumber 로 차트와 같은 규칙을 쓴다.
 */
export function isWeightColumn(col: string, rows: Record<string, unknown>[]): boolean {
  let seen = false;
  for (const row of rows) {
    const v = row[col];
    if (isEmptyValue(v)) continue;
    if (toPlotNumber(v) === null) return false;
    seen = true;
  }
  return seen;
}

/**
 * 빈 값인가 — null·undefined·''·공백뿐인 문자열(WD-841).
 * 열 고르기(isNumericColumn·isWeightColumn)와 차트 값 읽기(toPlotNumber·isNonNumericValue)가 같은 판정을 쓰도록 한 곳에 모았다.
 * 0·'0'·false 는 값이 있는 것이라 빈 값이 아니다.
 */
export function isEmptyValue(value: unknown): boolean {
  return value == null || (typeof value === 'string' && value.trim() === '');
}

/**
 * 값을 숫자 축·가중치용 숫자로 읽는다 — 숫자이거나 숫자로만 된 문자열(서버는 DECIMAL 을 문자열로 보낸다)만, 나머지는 null.
 * 공백뿐인 문자열·불리언은 Number() 가 0·1 로 바꿔 가짜 점이 되므로 받지 않는다(빈 값 판정은 isEmptyValue — WD-841). 산점도(WD-308)와 지도 히트맵 가중치(WD-354)가 함께 쓴다.
 */
export function toPlotNumber(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'string' && !isEmptyValue(value)) {
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

/**
 * 값이 「비어 있지 않은데 숫자로 읽히지 않는가」(WD-505).
 * 빈 값(isEmptyValue 가 참 — WD-841)은 false 이고, 비어 있지 않은데 숫자로 읽을 수 없는 값(문자 '서울'·불리언 등)만 true 다.
 */
export function isNonNumericValue(value: unknown): boolean {
  if (isEmptyValue(value)) return false;
  return toPlotNumber(value) === null;
}
