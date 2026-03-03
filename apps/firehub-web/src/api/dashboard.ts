import type {
  ActivityFeedParams,
  ActivityFeedResponse,
  AttentionItemResponse,
  DashboardStatsResponse,
  SystemHealthResponse,
} from '../types/dashboard';
import { client } from './client';

export const dashboardApi = {
  getStats: () => client.get<DashboardStatsResponse>('/dashboard/stats'),
  getHealth: () => client.get<SystemHealthResponse>('/dashboard/health'),
  getAttention: () => client.get<AttentionItemResponse[]>('/dashboard/attention'),
  getActivity: (params: ActivityFeedParams) =>
    client.get<ActivityFeedResponse>('/dashboard/activity', { params }),
};
