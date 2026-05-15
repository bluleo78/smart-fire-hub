import { useInfiniteQuery, useQuery } from '@tanstack/react-query';

import { dashboardApi } from '../../api/dashboard';
import type { ActivityFeedParams, ActivityFeedResponse } from '../../types/dashboard';

export function useDashboardStats() {
  return useQuery({
    queryKey: ['dashboard', 'stats'],
    queryFn: () => dashboardApi.getStats().then(r => r.data),
  });
}

export function useSystemHealth() {
  return useQuery({
    queryKey: ['dashboard', 'health'],
    queryFn: () => dashboardApi.getHealth().then(r => r.data),
    refetchInterval: 60_000,
  });
}

export function useAttentionItems() {
  return useQuery({
    queryKey: ['dashboard', 'attention'],
    queryFn: () => dashboardApi.getAttention().then(r => r.data),
    refetchInterval: 60_000,
  });
}

/**
 * 활동 피드 무한 스크롤 훅 (이슈 #226)
 *
 * - 기존 `useQuery` + `setActivityParams(size+=20)` 방식은 새 queryKey로 fetch가
 *   발생할 때마다 리스트가 재마운트/스크롤 리셋되어 사용자가 다시 끝까지 스크롤해야 했음.
 * - `useInfiniteQuery`로 page-by-page 누적 fetch하고, HomePage에서 모든 페이지의
 *   `items`를 평탄화하여 렌더하면 스크롤 위치가 자연스럽게 보존된다.
 * - `getNextPageParam`은 서버 `hasMore` 플래그로 다음 페이지 존재 여부를 판단한다.
 *
 * @param params - type/severity/size (page는 내부 관리)
 */
export function useActivityFeed(params: Omit<ActivityFeedParams, 'page'>) {
  const size = params.size ?? 20;
  return useInfiniteQuery({
    queryKey: ['dashboard', 'activity', { type: params.type, severity: params.severity, size }],
    queryFn: ({ pageParam }) =>
      dashboardApi
        .getActivity({ ...params, page: pageParam, size })
        .then(r => r.data),
    initialPageParam: 0,
    getNextPageParam: (lastPage: ActivityFeedResponse, allPages) =>
      lastPage.hasMore ? allPages.length : undefined,
  });
}
