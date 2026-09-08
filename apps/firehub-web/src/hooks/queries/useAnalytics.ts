import { useInfiniteQuery,useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { analyticsApi } from '../../api/analytics';
import type {
  AddWidgetRequest,
  AnalyticsQueryRequest,
  CreateChartRequest,
  CreateDashboardRequest,
  CreateSavedQueryRequest,
  UpdateChartRequest,
  UpdateDashboardRequest,
  UpdateSavedQueryRequest,
  UpdateWidgetRequest,
} from '../../types/analytics';

// ============================================================
// Phase 1: Saved Queries
// ============================================================

export function useSavedQueries(params: {
  search?: string;
  folder?: string;
  sharedOnly?: boolean;
  page?: number;
  size?: number;
}) {
  return useQuery({
    queryKey: ['analytics', 'queries', params],
    queryFn: () => analyticsApi.listQueries(params).then((r) => r.data),
  });
}

export function useSavedQuery(id: number | null) {
  return useQuery({
    queryKey: ['analytics', 'queries', id],
    queryFn: () => analyticsApi.getQuery(id!).then((r) => r.data),
    enabled: !!id,
  });
}

export function useCreateSavedQuery() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateSavedQueryRequest) =>
      analyticsApi.createQuery(data).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['analytics', 'queries'] });
      queryClient.invalidateQueries({ queryKey: ['analytics', 'folders'] });
    },
  });
}

export function useUpdateSavedQuery() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateSavedQueryRequest }) =>
      analyticsApi.updateQuery(id, data).then((r) => r.data),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ['analytics', 'queries'] });
      queryClient.invalidateQueries({ queryKey: ['analytics', 'queries', id] });
      queryClient.invalidateQueries({ queryKey: ['analytics', 'folders'] });
    },
  });
}

export function useDeleteSavedQuery() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => analyticsApi.deleteQuery(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['analytics', 'queries'] });
    },
  });
}

export function useCloneSavedQuery() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => analyticsApi.cloneQuery(id).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['analytics', 'queries'] });
    },
  });
}

export function useExecuteAnalyticsQuery() {
  return useMutation({
    mutationFn: (data: AnalyticsQueryRequest) =>
      analyticsApi.executeAdhoc(data).then((r) => r.data),
  });
}

export function useExecuteSavedQuery() {
  return useMutation({
    mutationFn: (id: number) =>
      analyticsApi.executeSavedQuery(id).then((r) => r.data),
  });
}

export function useSchemaInfo() {
  return useQuery({
    queryKey: ['analytics', 'schema'],
    queryFn: () => analyticsApi.getSchema().then((r) => r.data),
    staleTime: 10 * 60 * 1000,
  });
}

export function useQueryFolders() {
  return useQuery({
    queryKey: ['analytics', 'folders'],
    queryFn: () => analyticsApi.getFolders().then((r) => r.data),
    staleTime: 5 * 60 * 1000,
  });
}

// ============================================================
// Phase 2: Charts
// ============================================================

export function useCharts(params: {
  search?: string;
  savedQueryId?: number;
  sharedOnly?: boolean;
  page?: number;
  size?: number;
}) {
  return useQuery({
    queryKey: ['analytics', 'charts', params],
    queryFn: () => analyticsApi.listCharts(params).then((r) => r.data),
  });
}

/**
 * 차트 목록 무한스크롤 조회 (#537) — 페이지 기반 누적 fetch.
 * "차트 추가" 다이얼로그 등 전체 차트가 20개(기본 size)를 초과할 수 있는 화면에서,
 * 검색 없이도 "더 보기"로 나머지 차트에 접근할 수 있도록 useCharts(단일 페이지)와 별도로 제공한다.
 */
export function useChartsInfinite(params: {
  search?: string;
  savedQueryId?: number;
  sharedOnly?: boolean;
  size?: number;
}) {
  return useInfiniteQuery({
    queryKey: ['analytics', 'charts', 'infinite', params],
    queryFn: ({ pageParam }) =>
      analyticsApi.listCharts({ ...params, page: pageParam }).then((r) => r.data),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page < lastPage.totalPages - 1 ? lastPage.page + 1 : undefined,
  });
}

export function useChart(id: number | null) {
  return useQuery({
    queryKey: ['analytics', 'charts', id],
    queryFn: () => analyticsApi.getChart(id!).then((r) => r.data),
    enabled: !!id,
  });
}

export function useChartData(id: number | null | undefined, options?: {
  refetchInterval?: number;
  enabled?: boolean;
}) {
  return useQuery({
    queryKey: ['analytics', 'charts', id, 'data'],
    queryFn: () => analyticsApi.getChartData(id!).then((r) => r.data),
    enabled: options?.enabled !== false && !!id,
    refetchInterval: options?.refetchInterval
      ? () => {
          const base = options.refetchInterval!;
          const jitter = (Math.random() - 0.5) * 0.2 * base; // ±10%
          return base + jitter;
        }
      : undefined,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: false,
  });
}

export function useCreateChart() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateChartRequest) =>
      analyticsApi.createChart(data).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['analytics', 'charts'] });
    },
  });
}

export function useUpdateChart() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateChartRequest }) =>
      analyticsApi.updateChart(id, data).then((r) => r.data),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ['analytics', 'charts'] });
      queryClient.invalidateQueries({ queryKey: ['analytics', 'charts', id] });
    },
  });
}

export function useDeleteChart() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => analyticsApi.deleteChart(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['analytics', 'charts'] });
    },
  });
}

// ============================================================
// Phase 3: Dashboards
// ============================================================

export function useDashboards(params: {
  search?: string;
  sharedOnly?: boolean;
  page?: number;
  size?: number;
}) {
  return useQuery({
    queryKey: ['analytics', 'dashboards', params],
    queryFn: () => analyticsApi.listDashboards(params).then((r) => r.data),
  });
}

export function useDashboard(id: number | null) {
  return useQuery({
    queryKey: ['analytics', 'dashboards', id],
    queryFn: () => analyticsApi.getDashboard(id!).then((r) => r.data),
    enabled: !!id,
  });
}

export function useDashboardData(dashboardId: number | undefined) {
  return useQuery({
    queryKey: ['analytics', 'dashboards', dashboardId, 'data'],
    queryFn: () => analyticsApi.getDashboardData(dashboardId!).then((r) => r.data),
    enabled: !!dashboardId,
    // refetch 중 data가 undefined로 초기화되지 않도록 이전 데이터 유지 → N+1 개별 차트 fetch 방지
    placeholderData: (previousData) => previousData,
  });
}

export function useCreateDashboard() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateDashboardRequest) =>
      analyticsApi.createDashboard(data).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['analytics', 'dashboards'] });
    },
  });
}

export function useUpdateDashboard() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateDashboardRequest }) =>
      analyticsApi.updateDashboard(id, data).then((r) => r.data),
    onSuccess: (_, { id }) => {
      queryClient.invalidateQueries({ queryKey: ['analytics', 'dashboards'] });
      queryClient.invalidateQueries({ queryKey: ['analytics', 'dashboards', id] });
    },
  });
}

export function useDeleteDashboard() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => analyticsApi.deleteDashboard(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['analytics', 'dashboards'] });
    },
  });
}

export function useAddWidget(dashboardId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: AddWidgetRequest) =>
      analyticsApi.addWidget(dashboardId, data).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['analytics', 'dashboards', dashboardId] });
    },
  });
}

export function useUpdateWidget(dashboardId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ widgetId, data }: { widgetId: number; data: UpdateWidgetRequest }) =>
      analyticsApi.updateWidget(dashboardId, widgetId, data).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['analytics', 'dashboards', dashboardId] });
    },
  });
}

export function useRemoveWidget(dashboardId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (widgetId: number) => analyticsApi.removeWidget(dashboardId, widgetId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['analytics', 'dashboards', dashboardId] });
    },
  });
}
