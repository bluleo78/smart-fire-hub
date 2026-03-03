import { useQuery } from '@tanstack/react-query';

import { dashboardApi } from '../../api/dashboard';
import type { ActivityFeedParams } from '../../types/dashboard';

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

export function useActivityFeed(params: ActivityFeedParams) {
  return useQuery({
    queryKey: ['dashboard', 'activity', params],
    queryFn: () => dashboardApi.getActivity(params).then(r => r.data),
  });
}
