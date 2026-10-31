/**
 * 쿼리 결과에서 숫자 컬럼을 고른다 — 차트 기본 축 추천과 히트맵 가중치 컬럼 목록(WD-354)이 같은 기준을 쓴다.
 *
 * 앞 20행 중 70% 넘게 숫자로 읽히면 숫자 컬럼이다. 서버는 DECIMAL 을 문자열로 보내므로 Number() 로 읽는다.
 */
export function isNumericColumn(col: string, rows: Record<string, unknown>[]): boolean {
  const sample = rows.slice(0, 20).map((r) => r[col]);
  const nums = sample.filter((v) => v != null && !isNaN(Number(v)));
  return nums.length / sample.length > 0.7;
}

/**
 * 히트맵 가중치로 쓸 수 있는 컬럼인가(WD-354) — 비어 있지 않은 값이 하나 이상 있고 모두 숫자로 읽힌다.
 *
 * isNumericColumn 과 달리 NULL·빈 값은 세지 않는다 — 값이 드문드문 비어 있는 숫자 컬럼(측정값 등)도 가중치로
 * 고를 수 있어야 한다(비어 있는 행은 가중치 0 으로 그린다 — heatmap-weight.ts).
 */
export function isWeightColumn(col: string, rows: Record<string, unknown>[]): boolean {
  let seen = false;
  for (const row of rows) {
    const v = row[col];
    if (v == null || v === '') continue;
    if (typeof v === 'boolean' || typeof v === 'object' || !Number.isFinite(Number(v))) return false;
    seen = true;
  }
  return seen;
}

/**
 * 값을 숫자 축·가중치용 숫자로 읽는다 — 숫자이거나 숫자로만 된 문자열(서버는 DECIMAL 을 문자열로 보낸다)만, 나머지는 null.
 * 공백뿐인 문자열·불리언은 Number() 가 0·1 로 바꿔 가짜 점이 되므로 받지 않는다. 산점도(WD-308)와 지도 히트맵 가중치(WD-354)가 함께 쓴다.
 */
export function toPlotNumber(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'string' && value.trim() !== '') {
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}
