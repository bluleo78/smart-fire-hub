/**
 * 박스 플롯 통계 컬럼 규칙 (WD-305).
 *
 * 박스 플롯 뷰는 Y축 선택을 쓰지 않고, 쿼리 결과 행의 통계 컬럼(min·q1·median·q3·max — 별칭 q0·q2·q4)을 그대로 읽어 그린다.
 * 이 컬럼이 없으면 예전에는 모든 값을 0 으로 읽어 납작한 선만 그리고 그대로 저장돼, 사용자가 데이터가 모두 0 인 것으로 오해했다.
 * 뷰(안내)·빌더(저장 차단)가 같은 규칙을 쓰도록 여기 한 곳에 둔다.
 */

import { toPlotNumber } from './numeric-columns';

/** 통계 항목별로 받아들이는 컬럼 이름 — 뷰가 읽는 순서와 같다(앞 이름이 우선). */
const BOXPLOT_STAT_KEYS: ReadonlyArray<{ label: BoxplotStatLabel; keys: readonly string[] }> = [
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

/** 박스 플롯이 그리는 카테고리 하나의 통계(WD-826 — 뷰와 값 대체 표가 같은 값을 쓰도록 여기로 옮김) */
export interface BoxStat {
  category: string;
  min: number;
  q1: number;
  median: number;
  q3: number;
  max: number;
}

/** 통계 항목 이름(BOXPLOT_STAT_KEYS 의 label) — BoxStat 의 키와 같다 */
export type BoxplotStatLabel = 'min' | 'q1' | 'median' | 'q3' | 'max';

/**
 * 행마다 카테고리(xKey)와 통계 값을 읽는다 — 앞 이름이 우선(min → q0 등)이고, 없거나 숫자로 읽을 수 없으면 0 으로 본다(toPlotNumber — WD-831).
 * 통계 컬럼이 빠진 결과는 뷰가 그리지 않고 안내하므로(findMissingBoxplotStats) 0 은 실제로 그려지지 않는다.
 */
export function toBoxStats(data: Record<string, unknown>[], xKey: string): BoxStat[] {
  return data.map((d) => ({
    category: String(d[xKey] ?? ''),
    min: toPlotNumber(d.min ?? d.q0) ?? 0,
    q1: toPlotNumber(d.q1) ?? 0,
    median: toPlotNumber(d.median ?? d.q2) ?? 0,
    q3: toPlotNumber(d.q3) ?? 0,
    max: toPlotNumber(d.max ?? d.q4) ?? 0,
  }));
}

/**
 * 결과 컬럼에 있는 통계 항목 이름을 뷰가 읽는 순서대로 돌려준다(WD-826 — 값 대체 표의 열).
 * findMissingBoxplotStats 의 반대 — 별칭(q0·q2·q4)으로 있어도 항목 이름(min·median·max)으로 센다.
 */
export function findPresentBoxplotStats(columns: readonly string[]): BoxplotStatLabel[] {
  const present = new Set(columns);
  return BOXPLOT_STAT_KEYS.filter(({ keys }) => keys.some((k) => present.has(k))).map(({ label }) => label);
}
