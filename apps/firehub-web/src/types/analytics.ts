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

export type ChartType = 'BAR' | 'LINE' | 'PIE' | 'AREA' | 'SCATTER' | 'DONUT' | 'TABLE';

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
