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
  colorByColumn?: string;    // MAP 차트: 색상 기준 컬럼 (선택)
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
  savedQueryName: string;
  chartType: ChartType;
  config: ChartConfig;
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
  chart: Chart;
  queryResult: AnalyticsQueryResult;
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

export interface WidgetData {
  widgetId: number;
  chartId: number;
  queryResult: AnalyticsQueryResult | null;
  error?: string;
}

export interface DashboardDataResponse {
  dashboardId: number;
  widgets: WidgetData[];
}
