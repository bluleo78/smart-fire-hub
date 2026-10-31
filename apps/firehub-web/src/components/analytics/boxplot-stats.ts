/**
 * 박스 플롯 통계 컬럼 규칙 (WD-305).
 *
 * 박스 플롯 뷰는 Y축 선택을 쓰지 않고, 쿼리 결과 행의 통계 컬럼(min·q1·median·q3·max — 별칭 q0·q2·q4)을 그대로 읽어 그린다.
 * 이 컬럼이 없으면 예전에는 모든 값을 0 으로 읽어 납작한 선만 그리고 그대로 저장돼, 사용자가 데이터가 모두 0 인 것으로 오해했다.
 * 뷰(안내)·빌더(저장 차단)가 같은 규칙을 쓰도록 여기 한 곳에 둔다.
 */

/** 통계 항목별로 받아들이는 컬럼 이름 — 뷰가 읽는 순서와 같다(앞 이름이 우선). */
const BOXPLOT_STAT_KEYS: ReadonlyArray<{ label: string; keys: readonly string[] }> = [
  { label: 'min', keys: ['min', 'q0'] },
  { label: 'q1', keys: ['q1'] },
  { label: 'median', keys: ['median', 'q2'] },
  { label: 'q3', keys: ['q3'] },
  { label: 'max', keys: ['max', 'q4'] },
];

/** 박스 플롯 안내 문구에 쓰는 필요 컬럼 목록 */
export const BOXPLOT_REQUIRED_COLUMNS_LABEL = 'min·q1·median·q3·max';

/**
 * 결과 컬럼 중 빠진 통계 항목 이름을 돌려준다(없으면 빈 배열).
 * 뷰는 행 키를 정확한 이름으로 읽으므로(대소문자 구분) 여기서도 정확히 비교한다.
 */
export function findMissingBoxplotStats(columns: readonly string[]): string[] {
  const present = new Set(columns);
  return BOXPLOT_STAT_KEYS.filter(({ keys }) => !keys.some((k) => present.has(k))).map(({ label }) => label);
}
