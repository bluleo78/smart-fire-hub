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
 * 히스토그램이 집계할 수치 값을 읽는다 — X 컬럼을 Number() 로 읽고 NaN 은 버린다.
 * 결과가 비었는데 행이 있으면 뷰는 「X축은 숫자 컬럼이어야 합니다」 안내를 보여 준다.
 */
export function readHistogramValues(data: Record<string, unknown>[], xKey: string): number[] {
  return data.map((d) => Number(d[xKey])).filter((v) => !isNaN(v));
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
    const value = Number(d[yKey] ?? 0);
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
