import type { ChartConfig, ChartType } from '../../types/analytics';

/**
 * yAxis 를 항상 배열로 맞춘 설정 사본을 돌려준다(WD-88).
 *
 * AI 챗 인라인 차트는 LLM 이 조립한 config 를 받으므로 yAxis 가 빠져 있을 수 있다. 그대로 두면 렌더러의
 * `config.yAxis.length` 에서 TypeError 가 난다. ChartRenderer 와 누락 판정이 같은 보정을 쓰도록 한곳에 둔다.
 * 렌더러가 사본의 xAxis/yAxis 를 자동 추론 값으로 덮어쓰므로 원본을 건드리지 않게 항상 새 객체를 돌려준다.
 */
export function normalizeYAxis(config: ChartConfig): ChartConfig {
  return { ...config, yAxis: Array.isArray(config.yAxis) ? config.yAxis : [] };
}

/**
 * 렌더러가 실제로 쓰는 축 설정을 돌려준다 — yAxis 를 배열로 맞추고, 비어 있는 xAxis/yAxis 를 결과 컬럼으로 자동 추론한다.
 *
 * 규칙(컬럼이 2개 이상일 때만): xAxis 가 없으면 columns[0], yAxis 가 없으면 xAxis 를 뺀 나머지 컬럼(없으면 [columns[1]]).
 * ChartRenderer 와 AI 챗 차트 rows 정규화(chart-numeric-rows, WD-104)가 같은 축을 보도록 한곳에 둔다.
 * 원본은 건드리지 않고 항상 새 객체를 돌려준다(normalizeYAxis 사본 위에서 덮어쓴다).
 */
export function resolveChartAxes(rawConfig: ChartConfig, columns: string[]): ChartConfig {
  const config = normalizeYAxis(rawConfig);
  if ((!config.xAxis || config.yAxis.length === 0) && columns.length >= 2) {
    if (!config.xAxis) {
      config.xAxis = columns[0];
    }
    if (config.yAxis.length === 0) {
      // For numeric columns, use remaining columns as yAxis
      const remaining = columns.filter((c) => c !== config.xAxis);
      config.yAxis = remaining.length > 0 ? remaining : [columns[1]];
    }
  }
  return config;
}

/** 차트 타입 하나가 config 에서 읽는 컬럼 필드 목록(비어 있을 수 있음) */
type UsedColumnFields = (config: ChartConfig) => (string | undefined)[];

/** X축 + Y축 전체 — 여러 시리즈를 그리는 타입 */
const xAndAllY: UsedColumnFields = (c) => [c.xAxis, ...c.yAxis];
/** 이름(xAxis) + 값 하나(yAxis[0]) — yAxis[1..] 은 렌더러가 무시한다 */
const xAndFirstY: UsedColumnFields = (c) => [c.xAxis, c.yAxis[0]];
/** xAxis 만 — 히스토그램(수치 컬럼)·박스플롯(카테고리 컬럼) */
const xOnly: UsedColumnFields = (c) => [c.xAxis];

/**
 * 차트 타입별로 렌더러가 실제로 읽는 컬럼 설정값(WD-88).
 *
 * 왜 타입별로 나누나: 저장된 config 에는 이전 차트 타입에서 남은 값(예: 히스토그램으로 바꾼 뒤 남은 yAxis)이
 * 그대로 있을 수 있다. 렌더러가 읽지 않는 필드까지 검사하면, 멀쩡히 그려지는 차트에도 안내가 떠 오탐이 된다.
 *
 * Record<ChartType, …> 라서 ChartType 에 새 타입이 추가되면 여기서 컴파일 오류가 난다 — 빠뜨릴 수 없다.
 *
 * ⚠ 유지보수 규칙: 이 표는 각 *ChartView(BarChartView, recharts/*, nivo/*, MapChartView 등)가 config 에서
 * 실제로 읽는 컬럼 필드를 손으로 옮겨 적은 것이다. View 가 새 컬럼 필드를 읽거나 더 이상 읽지 않게 바뀌면
 * 반드시 이 표도 같은 변경에서 함께 고쳐야 한다. 빠뜨리면 누락 안내가 안 뜨거나(미탐) 정상 차트에 뜬다(오탐).
 * 단위 테스트(chart-missing-columns.test.ts)의 타입별 케이스도 같이 갱신한다.
 */
const USED_COLUMN_FIELDS: Record<ChartType, UsedColumnFields> = {
  BAR: xAndAllY,
  LINE: xAndAllY,
  AREA: xAndAllY,
  RADAR: xAndAllY,
  PIE: xAndFirstY,
  DONUT: xAndFirstY,
  TREEMAP: xAndFirstY,
  FUNNEL: xAndFirstY,
  WATERFALL: xAndFirstY,
  // 행(xAxis) / 열(yAxis[0]) / 셀 값(valueColumn)
  HEATMAP: (c) => [c.xAxis, c.yAxis[0], c.valueColumn],
  // 그룹이 있으면 X·Y 첫 번째 + 그룹 컬럼(그룹이 계열), 없으면 X + Y축 전부(Y마다 계열 — WD-308)
  SCATTER: (c) => (c.groupBy ? [c.xAxis, c.yAxis[0], c.groupBy] : [c.xAxis, ...c.yAxis]),
  // 지도: 공간 컬럼은 항상, 가중치는 밀도(heatmap) 모드에서만 읽는다.
  // colorByColumn 은 설정 UI 에만 있고 MapChartView 가 읽지 않으므로 검사하지 않는다.
  MAP: (c) => [c.spatialColumn, c.mapDisplayMode === 'heatmap' ? c.weightColumn : undefined],
  // 게이지: 값 컬럼 하나
  GAUGE: (c) => [c.yAxis[0]],
  HISTOGRAM: xOnly,
  BOXPLOT: xOnly,
  // 캔들스틱: 라벨(xAxis) + 시가/고가/저가/종가
  CANDLESTICK: (c) => [c.xAxis, c.open, c.high, c.low, c.close],
  // 테이블은 결과 컬럼을 그대로 그리므로 설정 컬럼이 없다
  TABLE: () => [],
};

/**
 * 차트 설정이 가리키는 컬럼 중 쿼리 결과에 없는 이름을 돌려준다(WD-88).
 *
 * 원본 쿼리의 컬럼 이름이 바뀌면(region → area) 저장된 축 설정은 없는 컬럼을 가리키고, 차트는 빈 격자와
 * 범례만 그린 채 아무 안내도 하지 않았다. ChartRenderer·저장 검증·AI 챗 위젯이 이 결과를 쓴다.
 *
 * - columns 가 비어 있으면(권한 가림·결과 없음) 판단 근거가 없으므로 검사하지 않는다.
 * - "결과에 있는 컬럼"은 columns 와 rows 의 키를 합친 집합이다. 뷰는 row 키로 값을 읽는데, AI 챗 show_chart 의
 *   columns 는 LLM 이 따로 채운 목록이라 표시 라벨('지역')이거나 일부가 빠져 있을 수 있다. columns 만 보면 예전에
 *   잘 그려지던 차트가 안내로 바뀌는 오탐이 나므로 row 키도 인정한다.
 * - 비어 있는 설정값은 렌더러가 columns 로 자동 추론하므로 검사하지 않는다 — 원본(rawConfig)을 넘겨야 한다.
 * - 같은 이름이 여러 필드에 있어도 한 번만, 설정 순서대로 돌려준다.
 *
 * 효율: 먼저 columns 에 없는 설정 컬럼(후보)만 추린다. 정상 차트는 후보가 없어 행을 전혀 보지 않는다.
 * 후보가 있을 때만 행을 훑되 후보 키만 확인하고, 후보를 모두 찾으면 바로 멈춘다.
 */
export function findMissingChartColumns(
  chartType: ChartType,
  config: ChartConfig,
  columns: string[],
  rows: Record<string, unknown>[] = [],
): string[] {
  if (columns.length === 0) return [];
  const columnSet = new Set(columns);

  // 1단계: columns 에 없는 설정 컬럼 후보(중복 제거, 설정 순서 유지). 빈 값은 자동 추론 대상이라 제외한다.
  const candidates: string[] = [];
  for (const field of USED_COLUMN_FIELDS[chartType](normalizeYAxis(config))) {
    if (typeof field !== 'string' || field === '') continue;
    if (!columnSet.has(field) && !candidates.includes(field)) candidates.push(field);
  }
  if (candidates.length === 0) return [];

  // 2단계: 후보가 행 키로 존재하는지 확인한다. 첫 행에 없는 키(null 생략 등)도 있어 모든 행을 보되,
  // 후보를 모두 찾으면 조기 종료한다. 상속 속성('toString' 등)을 컬럼으로 오인하지 않도록 `in` 대신 Object.hasOwn 을 쓴다.
  const unresolved = new Set(candidates);
  for (const row of rows) {
    for (const key of unresolved) {
      if (Object.hasOwn(row, key)) unresolved.delete(key);
    }
    if (unresolved.size === 0) return [];
  }
  return candidates.filter((c) => unresolved.has(c));
}

/**
 * Y축 체크박스 토글 결과를 계산한다(WD-88).
 *
 * 원본 쿼리 컬럼 이름이 바뀌면 yAxis 에 결과에 없는 이름이 남는데, 그 이름은 체크박스가 없어 해제할 수 없다.
 * 그래서 사용자가 Y축을 다시 고를 때 결과에 없는 이름을 걷어 낸다. 이때 새로 체크한 컬럼은 맨 끝이 아니라
 * 첫 번째 누락 항목의 자리를 차지한다 — yAxis[0] 만 읽는 타입(게이지·산점도·트리맵·퍼널·워터폴 등)이
 * 사용자가 방금 고른 컬럼이 아니라 기존에 남아 있던 다른 컬럼을 그리는 일을 막기 위해서다.
 * 예: columns=[b,c], yAxis=[old,b] 에서 c 체크 → [c,b].
 */
export function toggleYAxisColumn(
  yAxis: string[],
  col: string,
  checked: boolean,
  columns: string[],
): string[] {
  const isValid = (c: string) => columns.includes(c);
  // 결과에 있는 이름만 남긴다(방금 토글한 컬럼은 아래에서 다시 넣거나 뺀다)
  const kept = yAxis.filter((c) => isValid(c) && c !== col);
  if (!checked) return kept;

  const firstMissing = yAxis.findIndex((c) => !isValid(c));
  if (firstMissing === -1) return [...kept, col]; // 누락이 없으면 기존처럼 끝에 붙인다

  // 첫 누락 항목 앞에 남아 있던 유효 이름 수 = kept 안에서 그 누락 자리의 위치
  const insertAt = yAxis.slice(0, firstMissing).filter((c) => isValid(c) && c !== col).length;
  kept.splice(insertAt, 0, col);
  return kept;
}
