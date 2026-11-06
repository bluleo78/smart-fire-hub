import type { ChartConfig, ChartType } from '../../types/analytics';
import { normalizeYAxis } from './chart-missing-columns';

/** 차트 타입 하나의 필수 축 규칙 — 빠진 축이 있으면 안내 문구, 모두 있으면 null */
type RequiredAxesRule = (hasX: boolean, hasFirstY: boolean, hasAnyY: boolean, config: ChartConfig) => string | null;

/** X축을 설정하세요 — 통계·OHLC 타입은 xAxis 만 공통 필수(나머지 컬럼은 각 뷰가 안내한다) */
const xOnlyRule: RequiredAxesRule = (hasX) => (hasX ? null : 'X축을 설정하세요.');

/** 값 하나(yAxis[0])를 읽는 타입 — 이름(X) + 값(Y) */
const singleValueRule: RequiredAxesRule = (hasX, hasFirstY) => {
  if (!hasX && !hasFirstY) return 'X축과 Y축을 설정하세요.';
  if (!hasFirstY) return '값 컬럼(Y축)을 선택하세요.';
  if (!hasX) return 'X축을 설정하세요.';
  return null;
};

/** Y축 여러 개를 계열로 그리는 타입 — X + Y 하나 이상 */
const seriesRule: RequiredAxesRule = (hasX, _hasFirstY, hasAnyY) => {
  if (!hasX && !hasAnyY) return 'X축과 Y축을 설정하세요.';
  if (!hasAnyY) return 'Y축 컬럼을 선택하세요.';
  if (!hasX) return 'X축을 설정하세요.';
  return null;
};

/**
 * 차트 타입별 필수 축 규칙(WD-505).
 * Record<ChartType, …> 라서 ChartType 에 새 타입이 추가되면 컴파일 오류가 나 규칙을 빠뜨릴 수 없다.
 */
const REQUIRED_AXES_RULES: Record<ChartType, RequiredAxesRule> = {
  BAR: seriesRule,
  LINE: seriesRule,
  AREA: seriesRule,
  RADAR: seriesRule,
  SCATTER: seriesRule,
  PIE: singleValueRule,
  DONUT: singleValueRule,
  TREEMAP: singleValueRule,
  FUNNEL: singleValueRule,
  WATERFALL: singleValueRule,
  // 게이지는 값 컬럼 하나만 읽고 xAxis 는 쓰지 않는다
  GAUGE: (_hasX, hasFirstY) => (hasFirstY ? null : '값 컬럼(Y축)을 선택하세요.'),
  // 히트맵: 행(xAxis) / 열(yAxis[0]) / 셀 값(valueColumn) 세 컬럼이 모두 필요하다
  HEATMAP: (hasX, hasFirstY, _hasAnyY, c) =>
    hasX && hasFirstY && c.valueColumn ? null : '행, 열, 값(색상 기준) 컬럼을 모두 선택하세요.',
  CANDLESTICK: xOnlyRule,
  BOXPLOT: xOnlyRule,
  HISTOGRAM: xOnlyRule,
  // 지도·테이블은 축 대신 전용 설정을 쓰거나(공간 컬럼은 저장 검증이 따로 본다) 설정이 없다
  MAP: () => null,
  TABLE: () => null,
};

/**
 * 차트 타입에 필요한 축이 설정되지 않았으면 안내 문구를, 모두 있으면 null 을 돌려준다(WD-505).
 *
 * 왜: 차트 빌더에서 Y축 체크를 모두 해제하면 렌더러가 비어 있는 yAxis 를 결과 컬럼 전체(문자 컬럼 포함)로
 * 자동 추론해, 게이지 NaN·퍼널 「5: NaN」·막대의 엉뚱한 문자 시리즈가 그려졌다. 빌더 미리보기(inferAxes=false)와
 * 저장 검증(handleSave)이 이 함수 하나로 같은 규칙·같은 문구를 쓴다.
 * yAxis 가 빠지거나 타입이 틀린 config 도 받을 수 있게 내부에서 normalizeYAxis 로 맞춘다. 빈 문자열은 미설정으로 본다.
 */
export function findMissingChartAxesMessage(chartType: ChartType, rawConfig: ChartConfig): string | null {
  const config = normalizeYAxis(rawConfig);
  const hasX = typeof config.xAxis === 'string' && config.xAxis !== '';
  const hasFirstY = typeof config.yAxis[0] === 'string' && config.yAxis[0] !== '';
  const hasAnyY = config.yAxis.some((y) => typeof y === 'string' && y !== '');
  return REQUIRED_AXES_RULES[chartType](hasX, hasFirstY, hasAnyY, config);
}
