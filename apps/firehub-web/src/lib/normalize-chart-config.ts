import type { ChartConfig } from '../types/analytics';

/** 컬럼 이름·축 제목처럼 문자열이어야 하는 키 — 서버 ChartConfigValidator.STRING_KEYS 와 같은 목록 */
const STRING_KEYS = [
  'groupBy',
  'xAxisLabel',
  'yAxisLabel',
  'spatialColumn',
  'colorByColumn',
  'weightColumn',
  'valueColumn',
  'open',
  'high',
  'low',
  'close',
] as const;
const BOOLEAN_KEYS = ['showLegend', 'showGrid', 'stacked'] as const;
const NUMBER_KEYS = ['min', 'max', 'target', 'bins'] as const;
/** 히스토그램 구간 수 상한 — 서버(ChartConfigValidator.MAX_BINS)와 같은 값 */
export const MAX_HISTOGRAM_BINS = 1000;

const isString = (v: unknown): v is string => typeof v === 'string';

/**
 * 저장된 차트 config 를 화면이 가정하는 모양으로 맞춘 사본을 돌려준다 (WD-413).
 *
 * 서버가 config 값을 검사하지 않던 시절(그리고 REST 직접 호출·옛 데이터)에 `yAxis` 없음·문자열·숫자 섞임, `xAxis` 숫자·배열 같은
 * config 가 저장됐다. 그대로 쓰면 축 설정 패널이 `config.yAxis.includes` 에서 터져 편집 화면 전체가 오류 화면이 되거나, 조용히 빈 차트가
 * 된다. 받는 즉시 맞춰 두면 축 설정 패널이 그려지고 사용자가 축을 다시 골라 저장해 살릴 수 있다.
 * - xAxis: 문자열이 아니면 '' (선택 안 함)
 * - yAxis: 문자열 하나면 [그 문자열], 배열이면 문자열만 남기고, 그 밖은 []
 * - 문자열·켜고 끄기·숫자 키: 타입이 틀린 값은 버린다(기본값으로 그린다)
 * - min ≥ max 인 게이지 범위는 둘 다 버린다(거꾸로 된 바늘 대신 기본 범위)
 * - bins 가 1~MAX_HISTOGRAM_BINS 정수가 아니면 버린다(0·음수·소수면 구간 배열을 만들다 히스토그램이 터진다)
 * 그 밖의 키는 그대로 둔다. 원본은 건드리지 않는다.
 */
export function normalizeChartConfig(raw: unknown): ChartConfig {
  const src = raw !== null && typeof raw === 'object' && !Array.isArray(raw) ? (raw as Record<string, unknown>) : {};
  const out: Record<string, unknown> = { ...src };

  out.xAxis = isString(src.xAxis) ? src.xAxis : '';
  out.yAxis = isString(src.yAxis)
    ? src.yAxis
      ? [src.yAxis]
      : []
    : Array.isArray(src.yAxis)
      ? src.yAxis.filter(isString)
      : [];
  if (src.colors !== undefined) {
    if (Array.isArray(src.colors)) out.colors = src.colors.filter(isString);
    else delete out.colors;
  }
  for (const key of STRING_KEYS) {
    if (src[key] !== undefined && !isString(src[key])) delete out[key];
  }
  for (const key of BOOLEAN_KEYS) {
    if (src[key] !== undefined && typeof src[key] !== 'boolean') delete out[key];
  }
  for (const key of NUMBER_KEYS) {
    if (src[key] !== undefined && !(typeof src[key] === 'number' && Number.isFinite(src[key]))) delete out[key];
  }
  if (out.mapDisplayMode !== undefined && out.mapDisplayMode !== 'points' && out.mapDisplayMode !== 'heatmap') {
    delete out.mapDisplayMode;
  }
  if (typeof out.min === 'number' && typeof out.max === 'number' && out.min >= out.max) {
    delete out.min;
    delete out.max;
  }
  if (typeof out.bins === 'number' && !(Number.isInteger(out.bins) && out.bins >= 1 && out.bins <= MAX_HISTOGRAM_BINS)) {
    delete out.bins;
  }
  return out as unknown as ChartConfig;
}
