import { client } from './client';
import type { DashboardStatsResponse } from '../types/dashboard';

export const dashboardApi = {
  getStats: () => client.get<DashboardStatsResponse>('/dashboard/stats'),
};
