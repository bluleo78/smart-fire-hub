/**
 * 차트 뷰가 원본 행을 「화면에 그리는 값」으로 바꾸는 변환 함수 모음(WD-826).
 *
 * 히스토그램(구간별 빈도)·폭포(누적 합계)·게이지(값 하나 + 범위)는 원본 행이 아니라 변환한 값을 그린다.
 * 스크린리더용 값 대체 표(chart-a11y)도 차트가 실제로 보여 주는 값을 전해야 하므로(WCAG 1.1.1 동등한 목적),
 * 뷰와 대체 표가 같은 함수를 쓰도록 뷰 안에 있던 변환을 여기로 옮겼다 — 한쪽만 바뀌어 표와 그림이 어긋나지 않게.
 * recharts 를 가져오지 않는 순수 함수만 둔다.
 */
import type { ChartConfig } from '../../types/analytics';
import { isNonNumericValue, toPlotNumber } from './numeric-columns';

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

/** 폭포 차트 변환 결과 — overflow 면 누적이 유한하지 않아 rows 는 비어 있다 */
export interface WaterfallResult {
  rows: WaterfallRow[];
  overflow: boolean;
}

/**
 * 단계별 증감을 누적해 폭포 막대 위치를 계산한다.
 * WD-844: 증감은 모두 유한해도 누적이 Infinity(또는 ±Infinity 가 섞여 NaN)가 될 수 있다.
 * 어느 단계의 누적이라도 유한하지 않으면 그릴 수 없으므로 행 없이 overflow 로 알려
 * 화면 안내와 대체 표가 같은 판정을 쓰게 한다(파이의 WD-843 과 같은 방식).
 */
export function transformWaterfallData(
  data: Record<string, unknown>[],
  xKey: string,
  yKey: string,
): WaterfallResult {
  let cumulative = 0;
  // Y축이 걸치는 범위 계산용 — 0 과 모든 누적 중 최대·최소(WD-844)
  let maxBound = 0;
  let minBound = 0;
  const rows: WaterfallRow[] = [];
  for (const d of data) {
    const name = String(d[xKey] ?? '');
    // 읽을 수 없는 증감은 0 — NaN 이 누적에 번져 뒤 단계가 모두 NaN 이 되지 않게 한다(WD-831)
    const value = toPlotNumber(d[yKey]) ?? 0;
    const prev = cumulative;
    cumulative += value;
    maxBound = Math.max(maxBound, cumulative);
    minBound = Math.min(minBound, cumulative);
    if (value >= 0) {
      rows.push({ name, base: prev, positive: value, negative: 0, value, cumulative });
    } else {
      rows.push({ name, base: cumulative, positive: 0, negative: Math.abs(value), value, cumulative });
    }
  }
  // 판정 하나로 두 경우를 다 잡는다 — 누적이 Infinity·NaN 이 되면 maxBound/minBound 도 그 값이 되어 아래 식이 유한하지 않다.
  // 누적이 모두 유한해도 recharts Y축은 min(0, 누적)..max(0, 누적) 을 걸치고 위쪽을 보기 좋은 눈금으로 올림한다.
  // 범위(최대-최소)가 Infinity 가 되거나 올림한 눈금이 넘치면 축·막대에 Infinity/NaN 이 새므로,
  // 눈금 올림 여유로 ×2 를 곱해도 유한한지 확인해 넘치면 그릴 수 없음으로 판정한다(WD-844)
  if (!Number.isFinite((maxBound - minBound) * 2)) return { rows: [], overflow: true };
  return { rows, overflow: false };
}

/** 게이지 범위가 그릴 수 없는 이유 — 'non-finite': 최솟값·최댓값이 NaN·±Infinity, 'not-increasing': 최댓값 ≤ 최솟값 */
export type GaugeInvalidRange = false | 'non-finite' | 'not-increasing';

/** 게이지가 보여 주는 값 — 첫 행의 값과 눈금 범위(min ~ max) */
export interface GaugeReading {
  value: number;
  min: number;
  max: number;
  /** 범위가 그릴 수 없는 값이면 그 이유, 아니면 false — 참·거짓 검사로도 쓸 수 있다(WD-848) */
  invalidRange: GaugeInvalidRange;
}

/** x 보다 위로 120% 만큼 나간 눈금 — 양수는 ×1.2 올림, 음수는 ×0.8 올림(0 쪽으로), 0 이면 100 */
function extendUp(x: number): number {
  return x > 0 ? Math.ceil(x * 1.2) : x < 0 ? Math.ceil(x * 0.8) : 100;
}

/**
 * 게이지가 그리는 값과 범위를 계산한다. 값은 첫 행(data[0])의 yAxis[0] 이며 빈 값(null·''·공백만)은 0 으로 본다.
 * 둘 다 미설정이면 min = 0, max = 값이 0 보다 크면 ceil(값×1.2), 아니면 100 (바늘이 최대값에 고착되지 않게).
 * 둘 다 설정하면 그대로 쓴다. 둘 다 설정한 역범위는 서버·normalizeChartConfig(WD-413)가 막으므로 여기서 고치지 않고 invalidRange 로 알린다.
 * 한쪽만 설정하면 빠진 끝을 설정된 끝과 값을 기준으로 120% 만큼 바깥으로 정한다(WD-848) — 이미 바르게 그리던 눈금은 그대로 둔다. 항상 max > min 이다:
 *  - 최솟값만 설정: max = extendUp(max(값, min))  예) min 99·값 83 → 119, min 200·값 100 → 240, min -1000·값 10 → 12, min -50·값 -60 → -40, min 0·값 0 → 100
 *  - 최댓값만 설정: max > 0 이면 min = 0(흔한 0~max 게이지, 값이 음수여도 0). max ≤ 0 이면 bottom = min(값, max) 로
 *    bottom < 0 → min = -extendUp(-bottom)(= floor(bottom×1.2)), bottom = 0 → min = -extendUp(값)(값이 0 이면 -100)
 *    예) max -10·값 5 → -12, max -10·값 -50 → -60, max 0·값 5 → -6, max 0·값 0 → -100, max 0·값 -1000 → -1200
 * 숫자 아닌 값 검사는 뷰가 먼저 한다(그때는 게이지 대신 안내를 보여 준다).
 * 비유한 설정값(NaN·±Infinity)은 위 규칙을 타지 않고 그대로 두어 invalidRange 'non-finite' 로 잡는다.
 * invalidRange 는 그래도 남는 경우(둘 다 설정한 역범위, 비유한 값, 뷰를 직접 부르는 경로)의 마지막 방어선이다.
 */
export function computeGaugeReading(data: Record<string, unknown>[], config: ChartConfig): GaugeReading {
  const valueKey = config.yAxis[0];
  const value = toPlotNumber(data[0]?.[valueKey]) ?? 0;
  // 한쪽만 설정했는지 한 번만 가린다 — null 도 미설정으로 본다
  const hasMin = config.min != null;
  const hasMax = config.max != null;
  let min = config.min ?? 0;
  let max = config.max ?? (value > 0 ? Math.ceil(value * 1.2) : 100);
  // 비유한 설정값은 아래 규칙을 건너뛴다 — 판정이 그대로 잡게 한다
  const setEndFinite = hasMin ? Number.isFinite(min) : Number.isFinite(max);
  if (hasMin !== hasMax && setEndFinite) {
    if (hasMin) {
      // 최솟값만 설정: 최솟값과 값 중 위쪽 기준으로 올린다
      max = extendUp(Math.max(value, min));
    } else if (max > 0) {
      // 최댓값만 설정(양수): 흔한 0~max 게이지 — 값이 음수여도 최솟값은 0
      min = 0;
    } else {
      // 최댓값만 설정(0 이하): 값과 최댓값 중 아래쪽 기준으로 내린다 — 아래로 내리기는 부호를 뒤집은 extendUp 이다.
      // 아래쪽이 0(최댓값 0·값 ≥ 0)이면 값 크기로 폭을 잡는다(값도 0 이면 extendUp(0) = 100 → -100)
      const bottom = Math.min(value, max);
      min = -extendUp(bottom < 0 ? -bottom : value);
    }
  }
  // 마지막 방어선 — 둘 다 설정한 역범위·NaN·Infinity 는 (value-min)/(max-min) 이 NaN·Infinity 라 그릴 수 없다.
  // 뷰·표가 같은 판정을 쓰게 여기서 낸다(WD-848). 이유는 비유한이 먼저다
  let invalidRange: GaugeInvalidRange = false;
  if (!Number.isFinite(min) || !Number.isFinite(max)) invalidRange = 'non-finite';
  else if (max <= min) invalidRange = 'not-increasing';
  return { value, min, max, invalidRange };
}

/**
 * 파이·도넛이 그릴 행과 뺀 음수·결측 행 수를 나눈다(WD-303 → WD-831 에서 뷰 밖으로 옮김, WD-835 에서 결측도 뺌).
 * 파이는 전체 대비 비율을 그려 음수를 표현할 수 없으므로 음수 행은 뺀다. 값이 NULL·숫자 아님인 행(결측)도 뺀다 —
 * 예전에는 0 으로 바꿔 남겼는데, 값이 없는 항목이 0 을 잰 것처럼 읽히고 범례·표에 0.0% 로 실렸다(WD-835).
 * 실제 0 은 결측이 아니므로 남긴다. 뷰(그림·안내)와 값 대체 표가 같은 행을 쓰게 한 곳에 둔다.
 * 값은 toPlotNumber 로 읽는다(다른 차트와 같은 숫자 규칙). 남은 행의 값은 숫자로 바꾼 새 행으로 준다(항상 유한 숫자 ≥ 0) —
 * recharts Pie 는 숫자 타입만 값으로 세고 문자열('30', DB NUMERIC)은 0 으로 그려, 표의 비율과 그림이 어긋났다(WD-831).
 * nonNumericCount 는 빠진 결측 중 값이 비어 있지 않은데 숫자가 아닌 행('서울' 등, WD-838) — missingCount 의 부분집합이다.
 * 뷰가 「값 컬럼 전체가 숫자 아님」(그릴 행·음수 없이 nonNumericCount > 0)을 가려 컬럼 선택 안내를 내는 데 쓴다.
 * total 은 그릴 행(pieData) 값의 합이다(WD-841) — 뷰의 「그릴 것 없음」 판정과 대체 표의 비율·빈 표 판정이 같은 합을 쓰게 여기서 한 번 낸다.
 * emptyReason(WD-841)은 그릴 것이 없는 이유다 — 뷰(안내)와 스크린리더 표(캡션)가 둘 다 이 값을 읽으므로 판정이 서로 어긋나지 않는다.
 * 'overflow'(WD-843)는 각 값은 유한한데 합이 Infinity 로 넘친 경우다 — 비율을 낼 수 없어 'nothing' 과 따로 알린다.
 * 행마다 값을 한 번만 읽는다. 늘 새 배열을 돌려주므로 뷰는 useMemo 로 감싼다.
 */
export function splitPieData(
  data: Record<string, unknown>[],
  valueKey: string,
): {
  pieData: Record<string, unknown>[];
  negativeCount: number;
  missingCount: number;
  nonNumericCount: number;
  total: number;
  emptyReason: 'nonNumeric' | 'nothing' | 'overflow' | null;
} {
  const pieData: Record<string, unknown>[] = [];
  let negativeCount = 0;
  let missingCount = 0;
  let nonNumericCount = 0;
  let total = 0;
  for (const row of data) {
    const n = toPlotNumber(row[valueKey]);
    // 결측은 0 으로 바꾸지 않고 뺀다(WD-835) — 음수도 뺀다, 나머지만 그린다
    if (n === null) {
      missingCount++;
      // WD-838: 비어 있진 않은데 숫자가 아닌 값(문자 등)은 따로 센다
      if (isNonNumericValue(row[valueKey])) nonNumericCount++;
    } else if (n < 0) negativeCount++;
    else {
      pieData.push({ ...row, [valueKey]: n });
      total += n;
    }
  }
  // 그릴 것이 없는 이유 — 값 컬럼 전체가 숫자 아님이면 'nonNumeric', 합이 유한하지 않으면 'overflow'(WD-843), 합이 0 이하면 'nothing'
  // 순서가 중요하다: 합이 Infinity 면 `total <= 0` 이 거짓이라 그릴 수 있는 것처럼 지나가 비율이 NaN 이 되므로 유한성을 먼저 본다
  const emptyReason =
    pieData.length === 0 && negativeCount === 0 && nonNumericCount > 0
      ? 'nonNumeric'
      : !Number.isFinite(total)
        ? 'overflow'
        : total <= 0
          ? 'nothing'
          : null;
  return { pieData, negativeCount, missingCount, nonNumericCount, total, emptyReason };
}

/** 이 각도(°)보다 작은 파이 조각은 눈에 보이지 않아 「기타」 한 조각으로 묶는다(WD-852) */
export const PIE_SMALL_SLICE_DEGREES = 0.5;
/** 파이 범례에 싣는 최대 항목 수(큰 순) — 천 개 범례가 DOM 을 부풀리지 않게 한다(WD-852) */
export const PIE_LEGEND_LIMIT = 50;

/** 파이가 그릴 조각 하나 — colorIndex 는 pieData 안 원래 index(기타는 -1), isOther 는 묶음 조각 여부 */
export interface PieSlice {
  row: Record<string, unknown>;
  colorIndex: number;
  isOther: boolean;
}

/**
 * 파이 범례 항목 하나 — colorIndex 는 조각의 색 index 와 같다(기타·외는 -1).
 * kind: 'slice' 큰 조각, 'other' 회색 「기타 (n개)」 조각과 1:1 인 항목, 'rest' 범례에서 잘린 큰 조각 개수(스와치 없음).
 */
export interface PieLegendItem {
  name: string;
  colorIndex: number;
  kind: 'slice' | 'other' | 'rest';
}

/**
 * 부동소수 잡음(0.1+0.2 = 0.30000000000000004)을 유효숫자 15자리로 다듬는다(WD-852).
 * 정수는 그대로 둔다 — 16~17자리 정수(타임스탬프·ID)는 toPrecision(15) 하면 끝자리가 바뀐다.
 * NaN·Infinity 도 그대로 돌려준다. 값 대체 표(formatChartCellValue)와 파이 기타 합이 같은 규칙을 쓴다.
 */
export function roundFloatNoise(n: number): number {
  return Number.isInteger(n) ? n : Number(n.toPrecision(15));
}

/** 「기타 (n개)」 라벨 — 기타 조각 행과 범례 항목이 같은 문구를 쓰도록 한 곳에서 만든다(WD-852) */
function pieOtherLabel(count: number): string {
  return `기타 (${count.toLocaleString('ko-KR')}개)`;
}

/**
 * 0.5° 미만의 작은 조각을 「기타 (n개)」 한 조각으로 묶고, 범례를 고른다(WD-852).
 * 범례는 큰 순 상위 50개 조각 + (실제로 묶었다면) 회색 「기타 (n개)」 + (큰 조각이 잘렸다면) 「외 k개」 이다.
 * 회색 범례 항목은 정확히 회색 조각만 뜻해야 하므로 기타의 개수는 묶인 조각 수와 같고, 잘린 큰 조각은 색 없는 「외 k개」 로 따로 센다.
 * 범주가 천 개에 가까우면 대부분 눈에 보이지 않는 조각이라 SVG 노드·범례 DOM 만 늘려 느려지므로 줄인다.
 * 입력은 splitPieData 의 pieData(값은 유한 숫자 ≥ 0)다. 묶을지(조각 수가 많은지) 판단은 호출자 몫이다.
 * 작은 조각이 1개뿐이면 묶어도 이득이 없으므로 묶지 않고(otherCount 0), total ≤ 0 이면 각도를 못 구하므로 그대로 돌려준다.
 * 큰 조각은 원래 순서와 원래 colorIndex 를 유지하고 기타는 맨 끝에 둔다. 정렬(O(n log n))은 범례 후보(큰 조각)에만 한다.
 * 기타 여부는 이름이 아니라 isOther 로 구분한다 — 실제 행 이름이 「기타」여도 일반 조각이다.
 */
export function groupSmallPieSlices(
  pieData: Record<string, unknown>[],
  nameKey: string,
  valueKey: string,
  total: number,
): { slices: PieSlice[]; legend: PieLegendItem[]; otherCount: number } {
  // 한 번 훑으며 전체 목록(all)·큰 조각(big)·작은 조각 수/합을 함께 모은다
  const all: PieSlice[] = [];
  const big: PieSlice[] = [];
  let smallCount = 0;
  let smallSum = 0;
  pieData.forEach((row, index) => {
    const value = row[valueKey] as number;
    const slice: PieSlice = { row, colorIndex: index, isOther: false };
    all.push(slice);
    // value/total*360 < 0.5 를 곱셈으로 비교해 경계(정확히 0.5°)가 부동소수 오차로 흔들리지 않게 한다
    if (total > 0 && value * 360 < PIE_SMALL_SLICE_DEGREES * total) {
      smallCount++;
      smallSum += value;
    } else {
      big.push(slice);
    }
  });

  // 작은 조각이 1개 이하면 묶지 않고 원본 그대로
  if (smallCount < 2) {
    return { slices: all, legend: buildLegend(all, nameKey, valueKey, 0), otherCount: 0 };
  }

  // 소수 값을 여러 개 더하면 붙는 부동소수 오차(12.600000000000001)를 툴팁에 보이지 않게 다듬는다
  const other: PieSlice = {
    row: { [nameKey]: pieOtherLabel(smallCount), [valueKey]: roundFloatNoise(smallSum) },
    colorIndex: -1,
    isOther: true,
  };
  return {
    slices: [...big, other],
    legend: buildLegend(big, nameKey, valueKey, smallCount),
    otherCount: smallCount,
  };
}

/** 범례 고르기 — 값 내림차순(같으면 원래 순서) 큰 조각 상위 50개, 묶었으면 기타(묶인 수), 큰 조각이 잘렸으면 「외 k개」 */
function buildLegend(
  candidates: PieSlice[],
  nameKey: string,
  valueKey: string,
  otherCount: number,
): PieLegendItem[] {
  // Array.prototype.sort 는 안정 정렬이라 같은 값은 원래 순서를 유지한다
  const sorted = [...candidates].sort((a, b) => (b.row[valueKey] as number) - (a.row[valueKey] as number));
  const shown = sorted.slice(0, PIE_LEGEND_LIMIT);
  const legend: PieLegendItem[] = shown.map((s) => ({
    name: String(s.row[nameKey] ?? ''),
    colorIndex: s.colorIndex,
    kind: 'slice',
  }));
  if (otherCount > 0) {
    legend.push({ name: pieOtherLabel(otherCount), colorIndex: -1, kind: 'other' });
  }
  const cut = sorted.length - shown.length;
  if (cut > 0) {
    legend.push({ name: `외 ${cut.toLocaleString('ko-KR')}개`, colorIndex: -1, kind: 'rest' });
  }
  return legend;
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
