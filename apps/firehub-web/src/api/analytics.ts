import { normalizeChartConfig } from '../lib/normalize-chart-config';
import type {
  AddWidgetRequest,
  AnalyticsAdminItem,
  AnalyticsAdminKind,
  AnalyticsQueryRequest,
  AnalyticsQueryResult,
  Chart,
  ChartDataResponse,
  ChartListItem,
  CreateChartRequest,
  CreateDashboardRequest,
  CreateSavedQueryRequest,
  Dashboard,
  DashboardDataResponse,
  DashboardListItem,
  DashboardShareImpact,
  DashboardWidget,
  DeleteImpact,
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

/** 삭제 영향 범위 조회 시간 제한 — 넘으면 조회 실패로 보고 경고만 남긴 채 삭제를 허용한다(조회가 삭제를 무한정 막지 않게). */
export const DELETE_IMPACT_TIMEOUT_MS = 10_000;

/** 차트 응답의 config 를 정규화한다 — 가려진 config(null, configWithheld)는 그대로 둔다(WD-413). */
function withNormalizedChart<R extends { data: Chart }>(r: R): R {
  if (r.data?.config) r.data = { ...r.data, config: normalizeChartConfig(r.data.config) };
  return r;
}

export const analyticsApi = {
  // ============================================================
  // Phase 1: Saved Queries
  // ============================================================

  listQueries: (params: {
    search?: string;
    folder?: string;
    sharedOnly?: boolean;
    /** 내가 만든 항목만(「내 …」 탭, WD-328). 없으면 내 것 + 공유된 것 */
    mineOnly?: boolean;
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

  /**
   * 화면에 그린 SQL 결과(AI 표 위젯)의 내보내기 가능 여부 — 서버 플래그를 실을 수 없는 화면이 다운로드를 보일지 묻는다(S4).
   * 숨김·파싱 실패·정책 위반은 모두 false 로 온다(구분 불가).
   */
  exportCheck: (sql: string) =>
    client.post<{ exportAllowed: boolean }>('/analytics/queries/export-check', { sql }),

  getSchema: () =>
    client.get<SchemaInfo>('/analytics/queries/schema'),

  /** 폴더 목록 — 목록과 같은 탭 범위(sharedOnly/mineOnly, WD-328)를 줄 수 있다. 없으면 내 쿼리 + 공유 쿼리의 폴더 */
  getFolders: (params: { sharedOnly?: boolean; mineOnly?: boolean } = {}) =>
    client.get<string[]>('/analytics/queries/folders', { params }),

  // ============================================================
  // Phase 2: Charts
  // ============================================================

  listCharts: (params: {
    search?: string;
    savedQueryId?: number;
    sharedOnly?: boolean;
    /** 내가 만든 항목만(「내 …」 탭, WD-328). 없으면 내 것 + 공유된 것 */
    mineOnly?: boolean;
    page?: number;
    size?: number;
  }) => client.get<PageResponse<ChartListItem>>('/analytics/charts', { params }),

  // 차트를 받는 응답은 config 를 화면이 가정하는 모양으로 맞춘다(WD-413) — 잘못 저장된 config 로 편집 화면이 깨지지 않게.
  createChart: (data: CreateChartRequest) =>
    client.post<Chart>('/analytics/charts', data).then(withNormalizedChart),

  getChart: (id: number) =>
    client.get<Chart>(`/analytics/charts/${id}`).then(withNormalizedChart),

  updateChart: (id: number, data: UpdateChartRequest) =>
    client.put<Chart>(`/analytics/charts/${id}`, data).then(withNormalizedChart),

  deleteChart: (id: number) =>
    client.delete(`/analytics/charts/${id}`),


  /**
   * 삭제 영향 범위(WD-86) — 이 쿼리·차트를 지우면 함께 사라지는 차트·대시보드 위젯. 소유자만(남의 것·없는 것은 404).
   * timeout: 조회가 삭제를 무한정 막지 않게 끊는다(넘으면 호출부가 실패로 보고 경고 + 삭제 허용).
   */
  getDeleteImpact: (kind: 'query' | 'chart', id: number, signal?: AbortSignal) =>
    client.get<DeleteImpact>(`/analytics/${kind === 'query' ? 'queries' : 'charts'}/${id}/delete-impact`, {
      signal,
      timeout: DELETE_IMPACT_TIMEOUT_MS,
    }),

  // ============================================================
  // 워크스페이스 분석 자산 관리(analytics:admin, WD-199) — 목록(최소 정보)·공유 해제·삭제만 있다
  // ============================================================

  /** 워크스페이스의 해당 종류 자산 전부(이름·작성자·작성자 활성 여부·공유 여부·수정일). */
  getWorkspaceItems: (kind: AnalyticsAdminKind) =>
    client.get<AnalyticsAdminItem[]>(`/analytics/admin/${kind}`),

  /** 남의 자산이라도 공유를 해제한다(공유하기는 없다). */
  adminUnshare: (kind: AnalyticsAdminKind, id: number) =>
    client.post(`/analytics/admin/${kind}/${id}/unshare`),

  /** 남의 자산이라도 삭제한다 — 작성자 삭제와 같은 CASCADE(쿼리→차트→위젯, 차트→위젯, 대시보드→위젯). */
  adminDelete: (kind: AnalyticsAdminKind, id: number) =>
    client.delete(`/analytics/admin/${kind}/${id}`),

  /** 관리자 삭제 영향 범위 — 작성자 경로(getDeleteImpact)와 같은 응답. 이름은 관리자에게 보이는 것만, 나머지는 개수만. */
  getAdminDeleteImpact: (kind: 'query' | 'chart', id: number, signal?: AbortSignal) =>
    client.get<DeleteImpact>(`/analytics/admin/${kind === 'query' ? 'queries' : 'charts'}/${id}/delete-impact`, {
      signal,
      timeout: DELETE_IMPACT_TIMEOUT_MS,
    }),

  getChartData: (id: number) =>
    client.get<ChartDataResponse>(`/analytics/charts/${id}/data`).then((r) => {
      // 대시보드 위젯이 그리는 config 도 같은 모양으로 맞춘다(WD-413)
      if (r.data?.chart) r.data.chart = { ...r.data.chart, config: normalizeChartConfig(r.data.chart.config) };
      return r;
    }),

  // ============================================================
  // Phase 3: Dashboards
  // ============================================================

  listDashboards: (params: {
    search?: string;
    sharedOnly?: boolean;
    /** 내가 만든 항목만(「내 …」 탭, WD-328). 없으면 내 것 + 공유된 것 */
    mineOnly?: boolean;
    page?: number;
    size?: number;
  }) => client.get<PageResponse<DashboardListItem>>('/analytics/dashboards', { params }),

  createDashboard: (data: CreateDashboardRequest) =>
    client.post<Dashboard>('/analytics/dashboards', data),

  getDashboard: (id: number) =>
    client.get<Dashboard>(`/analytics/dashboards/${id}`),

  /** 공유를 켜거나 끌 때 함께 영향받는 내 차트·저장 쿼리 개수(WD-325) — 소유자 설정 창 안내용 */
  getDashboardShareImpact: (id: number, signal?: AbortSignal) =>
    client.get<DashboardShareImpact>(`/analytics/dashboards/${id}/share-impact`, { signal }),

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

  getDashboardData: (id: number) =>
    client.get<DashboardDataResponse>(`/analytics/dashboards/${id}/data`),
};
