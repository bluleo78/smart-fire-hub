import type { ChartConfig, ChartType } from '../../types/analytics';
import { CHART_TYPE_LABELS } from '../../types/analytics';
import { findPresentBoxplotStats, toBoxStats } from './boxplot-stats';
import {
  binHistogramValues,
  computeGaugeReading,
  getHistogramBinCount,
  getScatterGroupLabel,
  getScatterUsedY,
  isScatterPoint,
  readHistogramValues,
  splitPieData,
  transformWaterfallData,
} from './chart-derived-data';
import { getChartUsedColumns } from './chart-missing-columns';
import { toPlotNumber } from './numeric-columns';

/**
 * 스크린리더용 값 대체 표에 넣는 최대 행 수(WD-826).
 * 수천 행을 DOM 에 그대로 넣으면 렌더링이 무거워지고 읽기도 어려워 앞부분만 싣고 캡션으로 알린다.
 */
const CHART_SR_TABLE_ROW_LIMIT = 100;

/** 표 셀 한 칸에 담는 값 문자열의 최대 길이(초과분은 …로 줄인다) */
const CELL_MAX_LENGTH = 80;

/**
 * 차트의 접근 가능한 이름을 만든다(WD-826).
 *
 * 제목이 있으면 `「제목」 타입`, 없으면 `타입: x별 y1, y2`, 축 정보도 없으면 타입 라벨만 돌려준다.
 * 스크린리더가 차트를 만났을 때 무엇을 보여 주는 차트인지 알 수 있게 figure 의 aria-label 로 쓴다.
 * 공백뿐인 제목은 없는 것으로 본다.
 */
export function buildChartAccessibleName(
  chartType: ChartType,
  config: ChartConfig,
  title?: string,
): string {
  const label = CHART_TYPE_LABELS[chartType];
  const trimmed = title?.trim();
  if (trimmed) return `「${trimmed}」 ${label}`;
  const yAxis = Array.isArray(config.yAxis) ? config.yAxis : [];
  if (config.xAxis && yAxis.length > 0) return `${label}: ${config.xAxis}별 ${yAxis.join(', ')}`;
  return label;
}

/** 값 대체 표의 데이터 — 캡션·머리글·행(모두 문자열) */
export interface ChartDataTable {
  caption: string;
  headers: string[];
  rows: string[][];
}

/**
 * 표 셀에 넣을 값을 문자열로 바꾼다(WD-826).
 *
 * null/undefined 는 빈 칸, 0·false 와 문자열은 값을 그대로 보존한다. 객체(GEOMETRY 등)만 JSON 으로 쓰되
 * 80자를 넘으면 잘라 …를 붙인다.
 */
export function formatChartCellValue(value: unknown): string {
  if (value === null || value === undefined) return '';
  // 문자열·숫자 등 원시 값은 자르지 않는다 — 긴 카테고리 라벨이 잘리면 값을 구분할 수 없다
  // 단, 소수는 유효숫자 15자리로 다듬어 부동소수 잡음(0.30000000000000004 → 0.3)을 없앤다(WD-831).
  // 정수는 다듬지 않는다 — 16~17자리 정수(타임스탬프·ID, 1731999999123456)를 toPrecision(15) 하면 끝자리가 바뀐다.
  // 천 단위 구분(toLocaleString)은 쓰지 않는다 — 연도·ID 같은 라벨 숫자가 축(2024)과 달리 2,024 로 읽히고 보는 사람 로캘에 따라 바뀐다.
  // 작은 소수(0.0004)도 그대로 남는다. NaN·Infinity 는 그대로 문자열화한다.
  if (typeof value === 'number') {
    if (!Number.isFinite(value) || Number.isInteger(value)) return String(value);
    return String(Number(value.toPrecision(15)));
  }
  if (typeof value !== 'object') return String(value);
  // 객체(GEOMETRY 등)만 JSON 으로 쓰고, 표가 비대해지지 않도록 80자를 넘으면 …로 줄인다
  let text: string;
  try {
    text = JSON.stringify(value) ?? '';
  } catch {
    text = String(value);
  }
  return text.length > CELL_MAX_LENGTH ? `${text.slice(0, CELL_MAX_LENGTH)}…` : text;
}

/**
 * 표 캡션 — 행이 제한을 넘어 잘렸으면 전체 행 수를 밝혀 정보가 빠졌음을 알린다.
 * tail(WD-838)은 파이가 뺀 행 수 같은 꼬리 문구 — 상한 문구와 한 괄호 안에 쉼표로 잇고, 없으면 캡션은 그대로다.
 */
function buildCaption(totalRows: number, accessibleName: string, tail = ''): string {
  const parts: string[] = [];
  if (totalRows > CHART_SR_TABLE_ROW_LIMIT) {
    parts.push(`전체 ${totalRows.toLocaleString('ko-KR')}행 중 처음 ${CHART_SR_TABLE_ROW_LIMIT}행`);
  }
  if (tail) parts.push(tail);
  return parts.length > 0 ? `${accessibleName} 데이터 (${parts.join(', ')})` : `${accessibleName} 데이터`;
}

/** 머리글과 (이미 문자열로 바꾼) 전체 행으로 표를 만든다 — 변환 표도 원본 표와 같은 행 수 제한·캡션 규칙을 쓴다 */
function toTable(headers: string[], allRows: string[][], accessibleName: string): ChartDataTable {
  return toLimitedTable(headers, allRows.length, allRows.slice(0, CHART_SR_TABLE_ROW_LIMIT), accessibleName);
}

/**
 * 이미 제한(CHART_SR_TABLE_ROW_LIMIT)까지만 문자열로 바꾼 행으로 표를 만든다 — 전체 행 수(totalRows)는 캡션에 쓴다.
 * 행이 많을 수 있는 표(원본 행·파이·산점도)는 버릴 행까지 문자열로 바꾸지 않게 이것을 쓴다(WD-831).
 */
function toLimitedTable(
  headers: string[],
  totalRows: number,
  rows: string[][],
  accessibleName: string,
  captionTail = '',
): ChartDataTable {
  return { caption: buildCaption(totalRows, accessibleName, captionTail), headers, rows };
}

/**
 * 원본 행이 아니라 변환한 값을 그리는 차트의 표 — 차트가 실제로 보여 주는 값을 전한다(WCAG 1.1.1 동등한 목적).
 * 변환은 뷰와 같은 함수(chart-derived-data·boxplot-stats)를 써서 표와 그림이 어긋나지 않게 한다.
 * 해당하지 않는 타입이면 null(원본 행 표를 쓴다).
 */
function buildDerivedTable(
  chartType: ChartType,
  config: ChartConfig,
  data: Record<string, unknown>[],
  accessibleName: string,
): ChartDataTable | null {
  const cell = formatChartCellValue;
  switch (chartType) {
    case 'HISTOGRAM': {
      // 히스토그램은 구간별 빈도를 그린다 — 구간 라벨(구간 시작값, 뷰의 X축 라벨과 같음)과 빈도
      const bins = binHistogramValues(readHistogramValues(data, config.xAxis), getHistogramBinCount(config));
      return toTable(['구간', '빈도'], bins.map((b) => [b.label, cell(b.count)]), accessibleName);
    }
    case 'WATERFALL': {
      // 폭포 차트는 단계별 증감을 누적 합계 위에 띄워 그린다 — 단계·증감·누적을 함께 준다
      const yKey = config.yAxis[0] ?? '';
      const steps = transformWaterfallData(data, config.xAxis, yKey);
      return toTable(
        [config.xAxis, yKey, '누적'],
        steps.map((r) => [r.name, cell(r.value), cell(r.cumulative)]),
        accessibleName,
      );
    }
    case 'GAUGE': {
      // 게이지는 첫 행 값 하나와 눈금 범위(min ~ max)만 보여 준다 — 그 세 값을 한 행으로 준다.
      // config.target 은 뷰가 그리지 않으므로 표에도 넣지 않는다.
      const { value, min, max } = computeGaugeReading(data, config);
      return toTable([config.yAxis[0] ?? '값', '최솟값', '최댓값'], [[cell(value), cell(min), cell(max)]], accessibleName);
    }
    case 'BOXPLOT': {
      // 박스 플롯은 X 카테고리별 통계(min·q1·median·q3·max)를 그린다 — 결과에 있는 통계 항목을 뷰가 읽는 값으로 준다
      const statLabels = data.length > 0 ? findPresentBoxplotStats(Object.keys(data[0])) : [];
      const stats = toBoxStats(data, config.xAxis);
      return toTable(
        [config.xAxis, ...statLabels],
        stats.map((s) => [s.category, ...statLabels.map((label) => cell(s[label]))]),
        accessibleName,
      );
    }
    case 'PIE':
    case 'DONUT': {
      // 파이는 음수·결측을 빼고(splitPieData, 뷰와 같은 함수) 남은 값의 비율로 조각 크기를 정한다 — 값과 비율을 함께 준다(WD-831)
      const yKey = config.yAxis[0] ?? '';
      const { pieData, negativeCount, missingCount, total, emptyReason } = splitPieData(data, yKey);
      const tableHeaders = [config.xAxis, yKey, '비율'];
      // WD-841: 그릴 것이 없는 이유는 뷰와 같은 emptyReason 으로 한 번만 가른다 — 값 컬럼 전체가 숫자 아님이면 뺀 행 수 없이 그 이유만 적는다
      if (emptyReason === 'nonNumeric') return toLimitedTable(tableHeaders, 0, [], accessibleName, '숫자 값 컬럼 아님');
      // 뺀 행 수를 캡션 꼬리에 적는다(WD-838) — 항목마다 「제외」를 붙여 쉼표로 이어도 어느 행이 빠졌는지 읽힌다. 0 인 항목은 생략, 둘 다 0 이면 꼬리 없음
      // 문구는 화면 안내(「값이 없거나 숫자가 아닌 항목」)와 같은 뜻이 되게 맞춘다
      const omitted: string[] = [];
      if (negativeCount > 0) omitted.push(`음수 ${negativeCount.toLocaleString('ko-KR')}행 제외`);
      if (missingCount > 0) omitted.push(`값 없음·숫자 아님 ${missingCount.toLocaleString('ko-KR')}행 제외`);
      // 그릴 것이 없으면(합계 0 이하 'nothing', 합이 무한대로 넘침 'overflow' — WD-843) 화면은 안내만 보이고 조각이 없다
      // 표도 행을 싣지 않고 그 이유를 꼬리 맨 앞에 적는다
      if (emptyReason !== null) {
        const reason = emptyReason === 'overflow' ? '값 합계가 너무 큼' : '그릴 값 없음';
        return toLimitedTable(tableHeaders, 0, [], accessibleName, [reason, ...omitted].join(', '));
      }
      const captionTail = omitted.join(', ');
      // pieData 의 값은 splitPieData 가 이미 숫자로 바꿔 두었다(그림이 그리는 값 그대로)
      const valueOf = (r: Record<string, unknown>) => r[yKey] as number;
      return toLimitedTable(
        tableHeaders,
        pieData.length,
        // 표에 실을 행(CHART_SR_TABLE_ROW_LIMIT)만 문자열로 바꾼다 — 합계는 전체로 구한다
        pieData.slice(0, CHART_SR_TABLE_ROW_LIMIT).map((r) => [
          cell(r[config.xAxis]),
          cell(r[yKey]),
          `${((valueOf(r) / total) * 100).toFixed(1)}%`,
        ]),
        accessibleName,
        captionTail,
      );
    }
    case 'SCATTER': {
      // 산점도는 x 와 사용 Y 가 숫자인 점만 그린다 — 그리지 않은 행은 표에서도 빼고, Y 칸의 숫자 아닌 값은 빈 칸으로 둔다(WD-831)
      const headers = getChartUsedColumns('SCATTER', config);
      // 사용 Y 와 점 판정은 뷰와 같은 함수(getScatterUsedY·isScatterPoint)를 쓴다(WD-831)
      const usedY = new Set(getScatterUsedY(config));
      const xKey = config.xAxis;
      // 칸 하나 — 사용 Y 는 숫자일 때만 값(그리지 않은 값은 빈 칸), 그룹 칸은 범례와 같은 이름(값이 없으면 'unknown'), 그 밖은 그대로
      const scatterCell = (row: Record<string, unknown>, h: string): string => {
        if (h !== xKey && usedY.has(h)) return toPlotNumber(row[h]) !== null ? cell(row[h]) : '';
        if (h !== xKey && h === config.groupBy) return getScatterGroupLabel(row[h]);
        return cell(row[h]);
      };
      const drawn = data.filter((row) => [...usedY].some((y) => isScatterPoint(row[xKey], row[y])));
      const rows = drawn.slice(0, CHART_SR_TABLE_ROW_LIMIT).map((row) => headers.map((h) => scatterCell(row, h)));
      return toLimitedTable(headers, drawn.length, rows, accessibleName);
    }
    default:
      return null;
  }
}

/**
 * 차트 값 대체 표 데이터를 만든다(WD-826).
 *
 * 히스토그램·폭포·게이지·박스 플롯·파이·산점도는 차트가 그리는 변환 값(구간 빈도·누적·값과 범위·통계)으로 표를 만든다.
 * 그 외 타입의 열은 차트가 실제로 읽는 컬럼(getChartUsedColumns)이고, 설정이 비어 있으면(자동 추론 차트·TABLE) 결과 컬럼 전체를 쓴다.
 * 행은 CHART_SR_TABLE_ROW_LIMIT 까지만 싣고, 잘렸으면 캡션에 전체 행 수를 밝혀 정보가 빠졌음을 알린다.
 */
export function buildChartDataTable(
  chartType: ChartType,
  config: ChartConfig,
  columns: string[],
  data: Record<string, unknown>[],
  accessibleName: string,
): ChartDataTable {
  const derived = buildDerivedTable(chartType, config, data, accessibleName);
  if (derived) return derived;
  const used = getChartUsedColumns(chartType, config);
  const headers = used.length > 0 ? used : columns;
  // 원본 표는 실을 행만 문자열로 바꾼다(수천 행 결과에서 버릴 행까지 바꾸지 않게)
  const rows = data
    .slice(0, CHART_SR_TABLE_ROW_LIMIT)
    .map((row) => headers.map((h) => formatChartCellValue(row[h])));
  return toLimitedTable(headers, data.length, rows, accessibleName);
}
