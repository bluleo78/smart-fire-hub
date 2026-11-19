/**
 * 차트 뷰가 원본 행을 「화면에 그리는 값」으로 바꾸는 변환 함수 모음(WD-826).
 *
 * 히스토그램(구간별 빈도)·폭포(누적 합계)·게이지(값 하나 + 범위)는 원본 행이 아니라 변환한 값을 그린다.
 * 스크린리더용 값 대체 표(chart-a11y)도 차트가 실제로 보여 주는 값을 전해야 하므로(WCAG 1.1.1 동등한 목적),
 * 뷰와 대체 표가 같은 함수를 쓰도록 뷰 안에 있던 변환을 여기로 옮겼다 — 한쪽만 바뀌어 표와 그림이 어긋나지 않게.
 * recharts 를 가져오지 않는 순수 함수만 둔다.
 */
import type { ChartConfig } from '../../types/analytics';
import { toPlotNumber } from './numeric-columns';

/** 히스토그램 구간 하나 — 라벨은 구간 시작값(소수 1자리), count 는 그 구간에 든 값 수 */
export interface HistogramBin {
  label: string;
  count: number;
}

/**
 * 히스토그램이 집계할 수치 값을 읽는다 — X 컬럼을 toPlotNumber 로 읽고 읽을 수 없는 값(null·''·문자)은 버린다.
 * Number() 는 null·'' 를 0 으로 바꿔 가짜 값이 되므로 다른 차트와 같은 읽기(toPlotNumber)로 맞췄다(WD-831).
 * 결과가 비었는데 행이 있으면 뷰는 「X축은 숫자 컬럼이어야 합니다」 안내를 보여 준다.
 */
export function readHistogramValues(data: Record<string, unknown>[], xKey: string): number[] {
  return data.map((d) => toPlotNumber(d[xKey])).filter((v): v is number => v !== null);
}

/** 수치 배열을 N개 구간으로 집계한다(모든 값이 같으면 구간 하나) */
export function binHistogramValues(values: number[], bins: number): HistogramBin[] {
  if (values.length === 0) return [];
  const min = Math.min(...values);
  const max = Math.max(...values);
  if (min === max) return [{ label: String(min), count: values.length }];
  const size = (max - min) / bins;
  const result = Array.from({ length: bins }, (_, i) => ({
    label: (min + i * size).toFixed(1),
    count: 0,
  }));
  values.forEach((v) => {
    const idx = Math.min(Math.floor((v - min) / size), bins - 1);
    result[idx].count++;
  });
  return result;
}

/** 히스토그램 구간 수 — 설정이 없으면 20 */
export function getHistogramBinCount(config: ChartConfig): number {
  return config.bins ?? 20;
}

/** 폭포 차트 한 단계 — base(투명 막대)·positive/negative(색 막대)와 증감(value)·누적(cumulative) */
export interface WaterfallRow {
  name: string;
  base: number;
  positive: number;
  negative: number;
  value: number;
  cumulative: number;
}

/** 단계별 증감을 누적해 폭포 막대 위치를 계산한다 */
export function transformWaterfallData(
  data: Record<string, unknown>[],
  xKey: string,
  yKey: string,
): WaterfallRow[] {
  let cumulative = 0;
  return data.map((d) => {
    const name = String(d[xKey] ?? '');
    // 읽을 수 없는 증감은 0 — NaN 이 누적에 번져 뒤 단계가 모두 NaN 이 되지 않게 한다(WD-831)
    const value = toPlotNumber(d[yKey]) ?? 0;
    const prev = cumulative;
    cumulative += value;
    if (value >= 0) {
      return { name, base: prev, positive: value, negative: 0, value, cumulative };
    }
    return { name, base: cumulative, positive: 0, negative: Math.abs(value), value, cumulative };
  });
}

/** 게이지가 보여 주는 값 — 첫 행의 값과 눈금 범위(min ~ max) */
export interface GaugeReading {
  value: number;
  min: number;
  max: number;
}

/**
 * 게이지가 그리는 값과 범위를 계산한다. 값은 첫 행(data[0])의 yAxis[0] 이며 빈 값(null·'')은 0 으로 본다.
 * max 미설정 시 실제 값의 120%(올림)를 써서 바늘이 최대값에 고착되지 않게 하고, 값이 0 이하이면 100 을 쓴다.
 * 숫자 아닌 값 검사는 뷰가 먼저 한다(그때는 게이지 대신 안내를 보여 준다).
 */
export function computeGaugeReading(data: Record<string, unknown>[], config: ChartConfig): GaugeReading {
  const valueKey = config.yAxis[0];
  const value = toPlotNumber(data[0]?.[valueKey]) ?? 0;
  const min = config.min ?? 0;
  const max = config.max ?? (value > 0 ? Math.ceil(value * 1.2) : 100);
  return { value, min, max };
}

/**
 * 파이·도넛이 그릴 행과 뺀 음수·결측 행 수를 나눈다(WD-303 → WD-831 에서 뷰 밖으로 옮김, WD-835 에서 결측도 뺌).
 * 파이는 전체 대비 비율을 그려 음수를 표현할 수 없으므로 음수 행은 뺀다. 값이 NULL·숫자 아님인 행(결측)도 뺀다 —
 * 예전에는 0 으로 바꿔 남겼는데, 값이 없는 항목이 0 을 잰 것처럼 읽히고 범례·표에 0.0% 로 실렸다(WD-835).
 * 실제 0 은 결측이 아니므로 남긴다. 뷰(그림·안내)와 값 대체 표가 같은 행을 쓰게 한 곳에 둔다.
 * 값은 toPlotNumber 로 읽는다(다른 차트와 같은 숫자 규칙). 남은 행의 값은 숫자로 바꾼 새 행으로 준다(항상 유한 숫자 ≥ 0) —
 * recharts Pie 는 숫자 타입만 값으로 세고 문자열('30', DB NUMERIC)은 0 으로 그려, 표의 비율과 그림이 어긋났다(WD-831).
 * 행마다 값을 한 번만 읽는다. 늘 새 배열을 돌려주므로 뷰는 useMemo 로 감싼다.
 */
export function splitPieData(
  data: Record<string, unknown>[],
  valueKey: string,
): { pieData: Record<string, unknown>[]; negativeCount: number; missingCount: number } {
  const pieData: Record<string, unknown>[] = [];
  let negativeCount = 0;
  let missingCount = 0;
  for (const row of data) {
    const n = toPlotNumber(row[valueKey]);
    // 결측은 0 으로 바꾸지 않고 뺀다(WD-835) — 음수도 뺀다, 나머지만 그린다
    if (n === null) missingCount++;
    else if (n < 0) negativeCount++;
    else pieData.push({ ...row, [valueKey]: n });
  }
  return { pieData, negativeCount, missingCount };
}

/**
 * 산점도가 그리는 Y 컬럼 — groupBy 가 있으면 첫 Y 하나만, 없으면 yAxis 전체(normalize 된 yAxis 가정).
 * 뷰(ScatterChartView)와 스크린리더 표(chart-a11y)가 같은 규칙을 쓰도록 한 곳에 둔다(WD-831).
 */
export function getScatterUsedY(config: ChartConfig): string[] {
  return config.groupBy ? config.yAxis.slice(0, 1) : config.yAxis;
}

/** 산점도 그룹(계열) 이름 — 그룹 값이 없으면 'unknown' 계열로 묶는다(뷰 범례와 스크린리더 표가 같은 이름을 쓰게, WD-831) */
export function getScatterGroupLabel(value: unknown): string {
  return String(value ?? 'unknown');
}

/** 산점도의 한 점이 되는지 — x 와 y 가 모두 숫자로 읽힐 때만 그린다(뷰와 표 공용, WD-831) */
export function isScatterPoint(x: unknown, y: unknown): boolean {
  return toPlotNumber(x) !== null && toPlotNumber(y) !== null;
}
