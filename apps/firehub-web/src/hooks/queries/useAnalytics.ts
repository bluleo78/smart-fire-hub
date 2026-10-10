import { useInfiniteQuery,useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { analyticsApi } from '../../api/analytics';
import { fetchAllPages } from '../../lib/fetch-all-pages';
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

/** 서버가 저장 쿼리·대시보드 목록 size 를 100 으로 자르므로(SavedQueryController·AnalyticsDashboardController) 이 크기로 순회한다. */
const ANALYTICS_LIST_MAX_SIZE = 100;

/**
 * 선택 목록(차트 빌더의 저장 쿼리 드롭다운)용 전체 저장 쿼리 조회 (#737).
 *
 * 왜: `size: 100` 첫 페이지만 쓰면 101번째 이후 쿼리가 드롭다운에서 조용히 빠지고,
 * 그 쿼리를 쓰는 기존 차트를 열면 쿼리 칸이 빈 칸으로 보인다. 전 페이지를 순회해 모은다.
 * queryKey 가 ['analytics', 'queries'] 로 시작하므로 저장 쿼리 생성·수정·삭제 무효화에 함께 걸린다.
 */
export function useAllSavedQueries() {
  return useQuery({
    queryKey: ['analytics', 'queries', 'all'],
    queryFn: () =>
      fetchAllPages(
        (page, size) => analyticsApi.listQueries({ page, size }).then((r) => r.data),
        ANALYTICS_LIST_MAX_SIZE,
      ),
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

/**
 * 차트 데이터 쿼리의 키·조회 함수 — useChartData 와 useDashboardExportAllowed 가 같은 캐시 항목을 봐야 하므로 한 곳에 둔다.
 * 키가 어긋나면 enabled: false 관찰자가 늘 undefined 를 받아 내보내기 판정이 조용히 "전부 허용"이 된다.
 */
function chartDataQuery(id: number) {
  return {
    queryKey: ['analytics', 'charts', id, 'data'],
    queryFn: () => analyticsApi.getChartData(id).then((r) => r.data),
  };
}

export function useChartData(id: number | null | undefined, options?: {
  refetchInterval?: number;
  enabled?: boolean;
}) {
  // 위젯들이 동시에 재조회하지 않도록 주기에 ±10% 지터를 준다. 지터는 마운트당 한 번만 정한다(#778):
  // 과거엔 렌더마다 Math.random() 으로 다른 주기를 계산해, TanStack 이 "주기가 바뀌었다"고 보고
  // 컴포넌트가 다시 렌더될 때마다 타이머를 처음부터 재시작했다. 주기보다 자주 렌더되면 자동 새로고침이 영영 발화하지 않는다.
  const [jitterFactor] = useState(() => 1 + (Math.random() - 0.5) * 0.2);
  return useQuery({
    ...chartDataQuery(id!),
    enabled: options?.enabled !== false && !!id,
    refetchInterval: options?.refetchInterval
      ? Math.round(options.refetchInterval * jitterFactor)
      : undefined,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: false,
  });
}

/**
 * 대시보드 위젯들의 내보내기 가능 여부(S4) — 위젯 카드(useChartData)가 이미 받은 데이터를 캐시에서 관찰만 한다.
 * enabled: false 라 이 훅은 요청을 보내지 않는다(위젯의 지연 로딩·자동 새로고침 주기를 건드리지 않게).
 * 로드된 위젯 중 하나라도 denied 이거나 exportAllowed !== true 면 false. 아직 로드되지 않은 위젯은 화면·인쇄에 데이터가
 * 없으므로 판정에서 뺀다 — 넣으면 화면 밖 위젯이 있는 긴 대시보드에서 허용 사용자도 PDF 를 못 쓴다.
 */
export function useDashboardExportAllowed(chartIds: number[]): boolean {
  const uniqueIds = [...new Set(chartIds)];
  return useQueries({
    queries: uniqueIds.map((id) => ({ ...chartDataQuery(id), enabled: false })),
    combine: (results) =>
      results.every((r) => r.data === undefined || (r.data.denied !== true && r.data.exportAllowed === true)),
  });
}

/**
 * 화면 표시 데이터(AI 표 위젯)의 SQL 내보내기 가능 여부(S4) — 서버가 판정만 한다(실행·감사 없음).
 * 응답 전·실패·SQL 없음은 호출부가 숨김으로 다룬다(fail-closed). 같은 SQL 은 60초 동안 다시 묻지 않는다.
 */
export function useExportCheck(sql: string | undefined) {
  return useQuery({
    queryKey: ['analytics', 'export-check', sql],
    queryFn: () => analyticsApi.exportCheck(sql!).then((r) => r.data),
    enabled: Boolean(sql),
    staleTime: 60_000,
    retry: false,
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

/**
 * 선택 목록(차트 빌더의 "대시보드에 추가" 다이얼로그)용 전체 대시보드 조회 (#737).
 *
 * 왜: `size: 100` 첫 페이지만 쓰면 101번째 이후 대시보드가 조용히 빠져 고를 수 없다. 전 페이지를 순회해 모은다.
 * queryKey 가 ['analytics', 'dashboards'] 로 시작하므로 대시보드 생성·수정·삭제 무효화에 함께 걸린다.
 */
export function useAllDashboards() {
  return useQuery({
    queryKey: ['analytics', 'dashboards', 'all'],
    queryFn: () =>
      fetchAllPages(
        (page, size) => analyticsApi.listDashboards({ page, size }).then((r) => r.data),
        ANALYTICS_LIST_MAX_SIZE,
      ),
  });
}

export function useDashboard(id: number | null) {
  return useQuery({
    queryKey: ['analytics', 'dashboards', id],
    queryFn: () => analyticsApi.getDashboard(id!).then((r) => r.data),
    enabled: !!id,
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
