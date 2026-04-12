import type { AxiosInstance } from 'axios';

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

export interface SavedQueryList {
  content: Array<{
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
  }>;
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface CreateSavedQueryParams {
  name: string;
  sqlText: string;
  description?: string;
  datasetId?: number;
  folder?: string;
  isShared?: boolean;
}

export interface SchemaColumn {
  columnName: string;
  dataType: string;
  displayName: string | null;
}

export interface SchemaTable {
  tableName: string;
  datasetName: string | null;
  datasetId: number | null;
  columns: SchemaColumn[];
}

export interface SchemaInfo {
  tables: SchemaTable[];
}

export type ChartType =
  | 'BAR' | 'LINE' | 'PIE' | 'AREA' | 'SCATTER' | 'DONUT' | 'TABLE' | 'MAP'
  | 'HISTOGRAM' | 'BOXPLOT' | 'HEATMAP' | 'TREEMAP' | 'FUNNEL'
  | 'RADAR' | 'WATERFALL' | 'GAUGE' | 'CANDLESTICK';

export interface ChartConfig {
  xAxis: string;
  yAxis: string[];
  groupBy?: string;
  stacked?: boolean;
  spatialColumn?: string;
  // 신규 차트 타입용 선택 필드
  bins?: number;           // HISTOGRAM: 구간 수
  valueColumn?: string;    // HEATMAP: 셀 색상 기준 컬럼
  min?: number;            // GAUGE: 최솟값
  max?: number;            // GAUGE: 최댓값
  target?: number;         // GAUGE: 목표값
  open?: string;           // CANDLESTICK: 시가 컬럼
  high?: string;           // CANDLESTICK: 고가 컬럼
  low?: string;            // CANDLESTICK: 저가 컬럼
  close?: string;          // CANDLESTICK: 종가 컬럼
}

export interface CreateChartParams {
  name: string;
  savedQueryId: number;
  chartType: ChartType;
  config: ChartConfig;
  description?: string;
  isShared?: boolean;
}

export interface Chart {
  id: number;
  name: string;
  description: string | null;
  chartType: ChartType;
  config: ChartConfig;
  savedQueryId: number;
  savedQueryName: string;
  isShared: boolean;
  createdBy: number;
  createdByName: string;
  createdAt: string;
  updatedAt: string;
}

export interface ChartList {
  content: Array<{
    id: number;
    name: string;
    description: string | null;
    chartType: ChartType;
    savedQueryId: number;
    savedQueryName: string;
    isShared: boolean;
    createdByName: string;
    createdAt: string;
    updatedAt: string;
  }>;
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface ChartData {
  chart: Chart;
  queryResult: AnalyticsQueryResult;
}

export interface CreateDashboardParams {
  name: string;
  description?: string;
  isShared?: boolean;
  autoRefreshSeconds?: number;
}

export interface Dashboard {
  id: number;
  name: string;
  description: string | null;
  isShared: boolean;
  autoRefreshSeconds: number | null;
  createdBy: number;
  createdByName: string;
  createdAt: string;
  updatedAt: string;
}

export interface DashboardList {
  content: Array<{
    id: number;
    name: string;
    description: string | null;
    isShared: boolean;
    autoRefreshSeconds: number | null;
    createdByName: string;
    createdAt: string;
    updatedAt: string;
  }>;
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface AddDashboardWidgetParams {
  chartId: number;
  positionX: number;
  positionY: number;
  width: number;
  height: number;
}

export interface DashboardWidget {
  id: number;
  dashboardId: number;
  chartId: number;
  chartName: string;
  positionX: number;
  positionY: number;
  width: number;
  height: number;
  createdAt: string;
  updatedAt: string;
}

export function createAnalyticsApi(client: AxiosInstance) {
  return {
    async executeAnalyticsQuery(sql: string, maxRows?: number): Promise<AnalyticsQueryResult> {
      const response = await client.post('/analytics/queries/execute', { sql, maxRows, readOnly: true });
      return response.data;
    },

    async createSavedQuery(data: CreateSavedQueryParams): Promise<SavedQuery> {
      const response = await client.post('/analytics/queries', data);
      return response.data;
    },

    async listSavedQueries(params?: {
      search?: string;
      folder?: string;
    }): Promise<SavedQueryList> {
      const response = await client.get('/analytics/queries', { params });
      return response.data;
    },

    async executeSavedQuery(id: number): Promise<AnalyticsQueryResult> {
      const response = await client.post(`/analytics/queries/${id}/execute`, { readOnly: true });
      return response.data;
    },

    async getDataSchema(): Promise<SchemaInfo> {
      const response = await client.get('/analytics/queries/schema');
      return response.data;
    },

    async createChart(data: CreateChartParams): Promise<Chart> {
      const response = await client.post('/analytics/charts', data);
      return response.data;
    },

    async listCharts(params?: { search?: string }): Promise<ChartList> {
      const response = await client.get('/analytics/charts', { params });
      return response.data;
    },

    async getChartData(id: number): Promise<ChartData> {
      const response = await client.get(`/analytics/charts/${id}/data`);
      return response.data;
    },

    async createDashboard(data: CreateDashboardParams): Promise<Dashboard> {
      const response = await client.post('/analytics/dashboards', data);
      return response.data;
    },

    async listDashboards(params?: { search?: string }): Promise<DashboardList> {
      const response = await client.get('/analytics/dashboards', { params });
      return response.data;
    },

    async addDashboardWidget(dashboardId: number, data: AddDashboardWidgetParams): Promise<DashboardWidget> {
      const response = await client.post(`/analytics/dashboards/${dashboardId}/widgets`, data);
      return response.data;
    },
  };
}
