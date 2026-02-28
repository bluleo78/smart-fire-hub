import type {
  AddWidgetRequest,
  AnalyticsQueryRequest,
  AnalyticsQueryResult,
  Chart,
  ChartDataResponse,
  ChartListItem,
  CreateChartRequest,
  CreateDashboardRequest,
  CreateSavedQueryRequest,
  Dashboard,
  DashboardListItem,
  DashboardWidget,
  SavedQuery,
  SavedQueryListItem,
  SchemaInfo,
  UpdateChartRequest,
  UpdateDashboardRequest,
  UpdateSavedQueryRequest,
  UpdateWidgetRequest,
} from '../types/analytics';
import type { PageResponse } from '../types/common';
import { client } from './client';

export const analyticsApi = {
  // ============================================================
  // Phase 1: Saved Queries
  // ============================================================

  listQueries: (params: {
    search?: string;
    folder?: string;
    sharedOnly?: boolean;
    page?: number;
    size?: number;
  }) => client.get<PageResponse<SavedQueryListItem>>('/analytics/queries', { params }),

  createQuery: (data: CreateSavedQueryRequest) =>
    client.post<SavedQuery>('/analytics/queries', data),

  getQuery: (id: number) =>
    client.get<SavedQuery>(`/analytics/queries/${id}`),

  updateQuery: (id: number, data: UpdateSavedQueryRequest) =>
    client.put<SavedQuery>(`/analytics/queries/${id}`, data),

  deleteQuery: (id: number) =>
    client.delete(`/analytics/queries/${id}`),

  executeSavedQuery: (id: number) =>
    client.post<AnalyticsQueryResult>(`/analytics/queries/${id}/execute`),

  cloneQuery: (id: number) =>
    client.post<SavedQuery>(`/analytics/queries/${id}/clone`),

  executeAdhoc: (data: AnalyticsQueryRequest) =>
    client.post<AnalyticsQueryResult>('/analytics/queries/execute', data),

  getSchema: () =>
    client.get<SchemaInfo>('/analytics/queries/schema'),

  getFolders: () =>
    client.get<string[]>('/analytics/queries/folders'),

  // ============================================================
  // Phase 2: Charts
  // ============================================================

  listCharts: (params: {
    search?: string;
    savedQueryId?: number;
    sharedOnly?: boolean;
    page?: number;
    size?: number;
  }) => client.get<PageResponse<ChartListItem>>('/analytics/charts', { params }),

  createChart: (data: CreateChartRequest) =>
    client.post<Chart>('/analytics/charts', data),

  getChart: (id: number) =>
    client.get<Chart>(`/analytics/charts/${id}`),

  updateChart: (id: number, data: UpdateChartRequest) =>
    client.put<Chart>(`/analytics/charts/${id}`, data),

  deleteChart: (id: number) =>
    client.delete(`/analytics/charts/${id}`),

  getChartData: (id: number) =>
    client.get<ChartDataResponse>(`/analytics/charts/${id}/data`),

  // ============================================================
  // Phase 3: Dashboards
  // ============================================================

  listDashboards: (params: {
    search?: string;
    sharedOnly?: boolean;
    page?: number;
    size?: number;
  }) => client.get<PageResponse<DashboardListItem>>('/analytics/dashboards', { params }),

  createDashboard: (data: CreateDashboardRequest) =>
    client.post<Dashboard>('/analytics/dashboards', data),

  getDashboard: (id: number) =>
    client.get<Dashboard>(`/analytics/dashboards/${id}`),

  updateDashboard: (id: number, data: UpdateDashboardRequest) =>
    client.put<Dashboard>(`/analytics/dashboards/${id}`, data),

  deleteDashboard: (id: number) =>
    client.delete(`/analytics/dashboards/${id}`),

  addWidget: (dashboardId: number, data: AddWidgetRequest) =>
    client.post<DashboardWidget>(`/analytics/dashboards/${dashboardId}/widgets`, data),

  updateWidget: (dashboardId: number, widgetId: number, data: UpdateWidgetRequest) =>
    client.put<DashboardWidget>(`/analytics/dashboards/${dashboardId}/widgets/${widgetId}`, data),

  removeWidget: (dashboardId: number, widgetId: number) =>
    client.delete(`/analytics/dashboards/${dashboardId}/widgets/${widgetId}`),
};
