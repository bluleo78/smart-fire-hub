// ============================================================
// Phase 1: Saved Queries
// ============================================================

export interface SavedQuery {
  id: number;
  name: string;
  description: string | null;
  sqlText: string;
  datasetId: number | null;
  datasetName: string | null;
  folder: string | null;
  isShared: boolean;
  createdByName: string;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
  chartCount: number;
}

export interface SavedQueryListItem {
  id: number;
  name: string;
  description: string | null;
  folder: string | null;
  datasetId: number | null;
  datasetName: string | null;
  isShared: boolean;
  createdByName: string;
  createdAt: string;
  updatedAt: string;
  chartCount: number;
}

export interface CreateSavedQueryRequest {
  name: string;
  description?: string;
  sqlText: string;
  datasetId?: number | null;
  folder?: string | null;
  isShared: boolean;
}

export interface UpdateSavedQueryRequest {
  name?: string;
  description?: string;
  sqlText?: string;
  datasetId?: number | null;
  folder?: string | null;
  isShared?: boolean;
}

export interface AnalyticsQueryRequest {
  sql: string;
  maxRows?: number;
}

export interface AnalyticsQueryResult {
  queryType: string;
  columns: string[];
  rows: Record<string, unknown>[];
  affectedRows: number;
  executionTimeMs: number;
  totalRows: number;
  truncated: boolean;
  error: string | null;
  /**
   * 조회자 기준 내보내기 가능(S4). 애드혹·저장 쿼리 실행 응답에만 실린다 — 대시보드 공유 캐시 결과는 null.
   * `=== true` 일 때만 허용한다(fail-closed).
   */
  exportAllowed?: boolean | null;
  /** 애드혹 실행 기록 id — 쿼리 결과 내보내기는 이 id 로 서버가 재판정·재실행한다. 저장 쿼리 실행 등은 null. */
  runId?: string | null;
}

export interface SchemaTable {
  tableName: string;
  datasetName: string | null;
  datasetId: number | null;
  columns: SchemaColumn[];
}

export interface SchemaColumn {
  columnName: string;
  dataType: string;
  displayName: string | null;
}

export interface SchemaInfo {
  tables: SchemaTable[];
}

// ============================================================
// Phase 2: Charts
// ============================================================

export type ChartType =
  | 'BAR' | 'LINE' | 'PIE' | 'AREA' | 'SCATTER' | 'DONUT' | 'TABLE' | 'MAP'
  | 'HISTOGRAM' | 'BOXPLOT' | 'HEATMAP' | 'TREEMAP' | 'FUNNEL'
  | 'RADAR' | 'WATERFALL' | 'GAUGE' | 'CANDLESTICK';

/** 차트 타입 → 한국어 레이블 매핑. 단일 원본으로 InlineChartWidget, ChartListPage 등에서 공유. */
export const CHART_TYPE_LABELS: Record<ChartType, string> = {
  BAR: '막대 차트', LINE: '꺾은선 차트', AREA: '영역 차트',
  PIE: '파이 차트', DONUT: '도넛 차트', SCATTER: '산점도',
  TABLE: '테이블', MAP: '지도',
  HISTOGRAM: '히스토그램', BOXPLOT: '박스플롯', HEATMAP: '히트맵',
  TREEMAP: '트리맵', FUNNEL: '퍼널', RADAR: '레이더',
  WATERFALL: '워터폴', GAUGE: '게이지', CANDLESTICK: '캔들스틱',
};

export interface ChartConfig {
  xAxis: string;
  yAxis: string[];
  groupBy?: string;
  colors?: string[];
  showLegend?: boolean;
  showGrid?: boolean;
  xAxisLabel?: string;
  yAxisLabel?: string;
  stacked?: boolean;
  spatialColumn?: string;    // MAP 차트: GEOMETRY 컬럼명 (필수)
  colorByColumn?: string;    // MAP 차트: 색상 기준 컬럼 (선택, points 모드 전용)

  // MAP 차트 표시 모드 — 'points' (기존 점/폴리곤) vs 'heatmap' (좌표 밀도). 기본 'points' (#119)
  mapDisplayMode?: 'points' | 'heatmap';
  // MAP+heatmap 전용 가중치 컬럼 (numeric, 선택). 미지정 시 균등 가중치 1. (#119)
  weightColumn?: string;
  // HISTOGRAM: 구간 수 (기본 20)
  bins?: number;
  // HEATMAP: 셀 색상 기준 컬럼 (xAxis=행, yAxis[0]=열)
  valueColumn?: string;
  // GAUGE: 값 범위 및 목표
  min?: number;
  max?: number;
  target?: number;
  // CANDLESTICK: 시가/고가/저가/종가 컬럼명
  open?: string;
  high?: string;
  low?: string;
  close?: string;
}

export interface Chart {
  id: number;
  name: string;
  description: string | null;
  savedQueryId: number;
  /** 조회자가 원본 데이터셋을 볼 수 없어 denied 인 차트 데이터 응답(`ChartDataResponse.chart`)에서는 null — 원본 메타 비노출(보안 등급) */
  savedQueryName: string | null;
  chartType: ChartType;
  /** 조회자가 저장 쿼리의 데이터를 볼 수 없으면 null — configWithheld 가 true 다(WD-31②). */
  config: ChartConfig | null;
  /** 서버가 설정을 가렸는가. true 면 빌더는 편집을 잠그고 저장 시 config 를 보내지 않는다(기존 값 유지). */
  configWithheld?: boolean;
  isShared: boolean;
  createdByName: string;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChartListItem {
  id: number;
  name: string;
  description: string | null;
  savedQueryId: number;
  savedQueryName: string;
  chartType: ChartType;
  /** 서버가 설정을 가린 차트인가(WD-31②) — 목록에는 config 가 없어 표시용 플래그만 둔다. */
  configWithheld?: boolean;
  isShared: boolean;
  createdByName: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateChartRequest {
  name: string;
  description?: string;
  savedQueryId: number;
  chartType: ChartType;
  config: ChartConfig;
  isShared: boolean;
}

export interface UpdateChartRequest {
  name?: string;
  description?: string;
  chartType?: ChartType;
  config?: ChartConfig;
  isShared?: boolean;
}

export interface ChartDataResponse {
  /** 데이터 응답의 chart 는 config 를 항상 객체로 준다(denied 면 빈 객체) — 단건 조회의 null 가림과 타입을 나눈다(WD-31②). */
  chart: Omit<Chart, 'config' | 'configWithheld'> & { config: ChartConfig };
  queryResult: AnalyticsQueryResult;
  /**
   * 조회자가 원본 데이터셋을 볼 수 없음 — 위젯은 "열람 권한 없음" 상태를 그린다(스펙 §5-4).
   * true 면 서버가 chart 를 최소 메타로 준다: config 는 빈 객체(원본 컬럼명 비노출), savedQueryName 은 null.
   * 그래서 denied 분기는 chart.config 를 읽지 않고 먼저 반환해야 한다(DashboardWidgetCard).
   */
  denied?: boolean;
  /** 조회자 기준 차트 데이터 내보내기 가능 — 공유 캐시와 무관하게 매 요청 계산된다(S4). 없으면 불가로 본다. */
  exportAllowed?: boolean;
}

// ============================================================
// Phase 3: Dashboards
// ============================================================

export interface Dashboard {
  id: number;
  name: string;
  description: string | null;
  isShared: boolean;
  autoRefreshSeconds: number | null;
  widgets: DashboardWidget[];
  createdByName: string;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface DashboardListItem {
  id: number;
  name: string;
  description: string | null;
  isShared: boolean;
  autoRefreshSeconds: number | null;
  widgetCount: number;
  createdByName: string;
  createdAt: string;
  updatedAt: string;
}

export interface DashboardWidget {
  id: number;
  chartId: number;
  chartName: string;
  chartType: ChartType;
  positionX: number;
  positionY: number;
  width: number;
  height: number;
}

export interface CreateDashboardRequest {
  name: string;
  description?: string;
  isShared: boolean;
  autoRefreshSeconds?: number | null;
}

export interface UpdateDashboardRequest {
  name?: string;
  description?: string;
  isShared?: boolean;
  autoRefreshSeconds?: number | null;
  /** autoRefreshSeconds가 null일 때 "값 미제공"이 아니라 "명시적으로 지움(수동 전환)"임을 알리는 플래그 (#568) */
  clearAutoRefresh?: boolean;
}

export interface AddWidgetRequest {
  chartId: number;
  positionX: number;
  positionY: number;
  width: number;
  height: number;
}

export interface UpdateWidgetRequest {
  positionX?: number;
  positionY?: number;
  width?: number;
  height?: number;
}

// ============================================================
// Phase 4: Dashboard Batch Data
// ============================================================

/**
 * `GET /analytics/dashboards/{id}/data` 의 실제 API 계약 (#778).
 * API: `DashboardDataResponse(DashboardResponse dashboard, List<WidgetData> widgetData)`,
 * `WidgetData(Long widgetId, ChartDataResponse chartData)` 와 1:1 로 맞춘다.
 * 과거 프론트 타입(`dashboardId`/`widgets[].queryResult`)이 실제 응답과 달라 일괄 응답이 한 번도 쓰이지 못했다.
 * 현재 대시보드 화면은 위젯별 `/charts/{id}/data` 로 데이터를 가져오므로 이 엔드포인트를 호출하지 않는다.
 */
export interface WidgetData {
  widgetId: number;
  chartData: ChartDataResponse;
}

export interface DashboardDataResponse {
  dashboard: Dashboard;
  widgetData: WidgetData[];
}
