import { PLAIN_DECIMAL_RE } from '../../lib/plain-decimal';
import type { ChartConfig, ChartType } from '../../types/analytics';
import { resolveChartAxes } from '../analytics/chart-missing-columns';

/**
 * AI 챗 인라인 차트(show_chart) rows 의 숫자 문자열 정규화 (WD-104)
 *
 * 왜: WD-101·WD-104 이후 데이터셋 행 조회·SQL 탭 응답은 소수를 값과 상관없이 문자열("1.5")로, 2^53 밖 정수를
 * 문자열로 보낸다(JS double 로 깎이지 않게). LLM 이 그 값을 show_chart.rows 에 그대로 옮기면 recharts 가 문자열을 받는데,
 * 파이 차트는 숫자가 아닌 값을 0 으로 보아 조각을 하나도 그리지 못한다(WD-104 E2E 실측). 차트는 시각화라 double 정밀도로
 * 충분하므로, 위젯이 recharts 로 넘기기 직전에 "값으로 쓰이는 칸"의 숫자 문자열만 Number 로 바꾼다.
 *
 * 값 칸만 바꾸는 이유: x축(이름·카테고리)·열 카테고리·groupBy 칸에는 연도 "2024"·코드 "007" 같은 숫자처럼 생긴 라벨이 온다.
 * 이를 바꾸면 "007" 이 7 로 보이고 카테고리 축이 숫자 정렬·병합될 수 있으므로, 차트 타입별로 값 축인 칸만 대상으로 한다.
 *
 * 분석(analytics) 차트 페이지·대시보드는 AnalyticsQueryResponse 가 숫자로 오고, 같은 ChartRenderer 를 쓰더라도 이 문제가
 * 없으므로 공유 지점(ChartRenderer)이 아니라 AI 챗 위젯에서만 적용한다 — 분석 경로 동작을 바꾸지 않기 위해서다.
 */

/** 차트 타입 하나에서 값(수치)으로 쓰이는 칸 목록 — 축 자동 추론을 마친 설정을 받는다 */
type ValueColumnFields = (config: ChartConfig) => (string | undefined)[];

const allY: ValueColumnFields = (c) => c.yAxis;
const firstY: ValueColumnFields = (c) => [c.yAxis[0]];
const none: ValueColumnFields = () => [];

/**
 * 차트 타입별 값 칸(WD-104). Record<ChartType, …> 라 새 타입이 생기면 컴파일 오류로 빠뜨리지 않는다.
 *
 * - TABLE: 정규화하지 않는다. TableView 는 String(val) 로 그리므로, 바꾸면 "123456789012.123456" 이 123456789012.12346 으로,
 *   "007" 이 7 로 보여 WD-101 이 막은 손실이 다시 생긴다.
 * - MAP: 정규화하지 않는다. 자동 추론이 colorByColumn·팝업 속성 칸까지 값 칸으로 잡아 원문 표시를 바꾸고, 유일한 수치
 *   칸인 weightColumn 은 HeatmapLayer 가 maplibre ['to-number', …] 식으로 이미 문자열을 숫자로 읽는다.
 * - HEATMAP: yAxis[0] 은 열 카테고리라 제외하고 셀 값(valueColumn)만 바꾼다.
 * - SCATTER·HISTOGRAM: x축도 수치 축이다(산점도 number 축, 히스토그램은 x 칸 값을 구간으로 나눈다).
 * - BOXPLOT: 고정 키(min·q1·median…)를 뷰가 Number() 로 읽어 바꿀 칸이 없다.
 */
const VALUE_COLUMN_FIELDS: Record<ChartType, ValueColumnFields> = {
  BAR: allY,
  LINE: allY,
  AREA: allY,
  RADAR: allY,
  PIE: firstY,
  DONUT: firstY,
  TREEMAP: firstY,
  FUNNEL: firstY,
  WATERFALL: firstY,
  GAUGE: firstY,
  HEATMAP: (c) => [c.valueColumn],
  SCATTER: (c) => [c.xAxis, c.yAxis[0]],
  HISTOGRAM: (c) => [c.xAxis],
  CANDLESTICK: (c) => [c.open, c.high, c.low, c.close],
  BOXPLOT: none,
  MAP: none,
  TABLE: none,
};

/** x축 자체가 수치 축인 차트 — 이 밖의 차트에서는 x축 칸을 값 칸에서 뺀다 */
const NUMERIC_X_AXIS_TYPES: ReadonlySet<ChartType> = new Set<ChartType>(['SCATTER', 'HISTOGRAM']);

/**
 * 이 차트 설정에서 값(수치)으로 쓰이는 칸 이름 집합을 구한다.
 * 축은 ChartRenderer 와 같은 resolveChartAxes 로 자동 추론한 뒤 타입별 표(VALUE_COLUMN_FIELDS)로 고른다.
 * 카테고리 축(x축, 수치 x축 타입 제외)과 groupBy 는 yAxis 에 같은 이름이 있어도 바꾸지 않는다.
 */
export function chartValueColumns(chartType: ChartType, config: ChartConfig, columns: string[]): Set<string> {
  const resolved = resolveChartAxes(config, columns);
  const fields = VALUE_COLUMN_FIELDS[chartType] ?? none;
  const result = new Set<string>(fields(resolved).filter((c): c is string => typeof c === 'string' && c !== ''));
  if (resolved.xAxis && !NUMERIC_X_AXIS_TYPES.has(chartType)) result.delete(resolved.xAxis);
  if (resolved.groupBy) result.delete(resolved.groupBy);
  return result;
}

/**
 * 값 칸의 숫자 문자열을 Number 로 바꾼 rows 를 돌려준다. 바꿀 값이 하나도 없으면 원본 배열을 그대로 돌려줘
 * (숫자로 온 기존 응답은 참조가 바뀌지 않는다) 메모·리렌더 비용을 늘리지 않는다. 원본 행 객체는 바꾸지 않는다.
 */
export function normalizeChartRows(
  chartType: ChartType,
  config: ChartConfig,
  columns: string[],
  rows: Record<string, unknown>[],
): Record<string, unknown>[] {
  const valueColumns = chartValueColumns(chartType, config, columns);
  if (valueColumns.size === 0) return rows;

  let changed = false;
  const normalized = rows.map((row) => {
    let copy: Record<string, unknown> | null = null;
    for (const col of valueColumns) {
      const value = row[col];
      if (typeof value === 'string' && PLAIN_DECIMAL_RE.test(value)) {
        if (!copy) copy = { ...row };
        copy[col] = Number(value);
      }
    }
    if (copy) {
      changed = true;
      return copy;
    }
    return row;
  });
  return changed ? normalized : rows;
}
