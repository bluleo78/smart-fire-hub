import type { ChartConfig, ChartType } from '../../types/analytics';
import { chartSupportsValueTable } from './chart-a11y';
import { findMissingChartColumns, isKnownChartType, normalizeYAxis, resolveChartAxes } from './chart-missing-columns';
import { findMissingChartAxesMessage } from './chart-required-axes';
import { isScatterXNotNumeric } from './numeric-columns';

/**
 * 이 차트가 실제로 그릴 SVG 가 있어 위젯 그리기 차례(줄)를 서야 하는지 판단한다 (WD-1113).
 *
 * 왜: 안내 문구(지원하지 않는 타입·컬럼 누락·축 미설정)나 표·지도는 무거운 차트를 그리지 않는데도
 * 줄을 서면 앞 차트가 끝날 때까지 스피너로 기다리게 된다. 그래서 ChartRenderer 가 안내로 일찍 반환하는
 * 경우를 같은 순서로 걸러 낸다.
 *
 * 주의: 렌더러 규칙(ChartRenderer 의 조기 반환)을 바꾸면 여기도 같이 바꾼다.
 * 뷰 수준의 안내(박스 플롯 통계 컬럼 누락 등)는 직접 그린 가벼운 SVG 라 차례를 막을 만큼 무겁지 않아 뺐다.
 *
 * @param inferAxes 렌더러의 inferAxes 와 같은 뜻(기본 true) — true 면 빈 축을 컬럼에서 추론하고 공통 축 검사
 *   (xAxis 와 yAxis 모두 필요)를 하며, false 면 설정 그대로 보고 타입별 필수 축 규칙(findMissingChartAxesMessage)으로 가른다.
 *   false 일 때는 게이지(xAxis 불필요)·히스토그램(yAxis 불필요)처럼 공통 검사로는 오탐하는 타입도 렌더러가 그리므로 차례를 선다.
 *   산점도 X 가 숫자로 읽히지 않으면 렌더러는 가벼운 안내만 그리므로 차례를 서지 않는다.
 *   ChartRenderer 와 같은 판정을 써야 렌더러가 안내를 그리는데 줄만 서는 어긋남이 없다.
 */
export function chartNeedsRenderTurn(
  chartType: ChartType,
  rawConfig: ChartConfig,
  columns: string[],
  rows: Record<string, unknown>[],
  inferAxes: boolean = true,
): boolean {
  if (!isKnownChartType(chartType)) return false;
  // TABLE 은 SVG 차트가 아니라 제외한다. MAP 도 제외하는데, maplibre 초기화(WebGL·타일·GeoJSON)는
  // 렌더 단계가 아니라 effect·비동기에서 일어나 커밋에서 차례를 반납하는 이 줄로는 나눠지지 않기 때문이다(WD-1146).
  if (!chartSupportsValueTable(chartType)) return false;
  if (findMissingChartColumns(chartType, rawConfig, columns, rows).length > 0) return false;
  if (rows.length === 0) return false;
  if (inferAxes) {
    // 추론하면 렌더러는 공통 축 검사(X·Y 모두 필요)만 한다
    const config = resolveChartAxes(rawConfig, columns);
    return !!config.xAxis && config.yAxis.length > 0;
  }
  // 추론하지 않으면 렌더러와 같은 순서: 산점도 X 가 숫자가 아니면 뷰의 가벼운 안내(WD-308)만 그리므로 차례가 필요 없고,
  // 아니면 타입별 필수 축이 빠졌을 때 렌더러가 안내 문구를 그린다
  const config = normalizeYAxis(rawConfig);
  if (chartType === 'SCATTER' && isScatterXNotNumeric(config.xAxis, rows)) return false;
  return findMissingChartAxesMessage(chartType, config) === null;
}
