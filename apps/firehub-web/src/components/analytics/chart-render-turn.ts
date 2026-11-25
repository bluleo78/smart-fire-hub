import type { ChartConfig, ChartType } from '../../types/analytics';
import { chartSupportsValueTable } from './chart-a11y';
import { findMissingChartColumns, isKnownChartType, resolveChartAxes } from './chart-missing-columns';

/**
 * 이 차트가 실제로 그릴 SVG 가 있어 위젯 그리기 차례(줄)를 서야 하는지 판단한다 (WD-1113).
 *
 * 왜: 안내 문구(지원하지 않는 타입·컬럼 누락·축 미설정)나 표·지도는 무거운 차트를 그리지 않는데도
 * 줄을 서면 앞 차트가 끝날 때까지 스피너로 기다리게 된다. 그래서 ChartRenderer 가 안내로 일찍 반환하는
 * 경우를 같은 순서로 걸러 낸다.
 *
 * 주의: 렌더러 규칙(ChartRenderer 의 조기 반환)을 바꾸면 여기도 같이 바꾼다.
 * 뷰 수준의 안내(박스 플롯 통계 컬럼 누락 등)는 직접 그린 가벼운 SVG 라 차례를 막을 만큼 무겁지 않아 뺐다.
 * 대시보드는 렌더러의 inferAxes 기본값(true)을 쓰므로 축 추론도 같은 조건으로 따른다.
 */
export function chartNeedsRenderTurn(
  chartType: ChartType,
  rawConfig: ChartConfig,
  columns: string[],
  rows: Record<string, unknown>[],
): boolean {
  if (!isKnownChartType(chartType)) return false;
  // TABLE·MAP 은 recharts 등 무거운 차트가 아니다
  if (!chartSupportsValueTable(chartType)) return false;
  if (findMissingChartColumns(chartType, rawConfig, columns, rows).length > 0) return false;
  const config = resolveChartAxes(rawConfig, columns);
  if (!config.xAxis || config.yAxis.length === 0) return false;
  return rows.length > 0;
}
