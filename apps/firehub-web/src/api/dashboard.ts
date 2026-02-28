import type { DashboardStatsResponse } from '../types/dashboard';
import { client } from './client';

export const dashboardApi = {
  getStats: () => client.get<DashboardStatsResponse>('/dashboard/stats'),
};
