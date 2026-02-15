import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../../api/dashboard';

export function useDashboardStats() {
  return useQuery({
    queryKey: ['dashboard', 'stats'],
    queryFn: () => dashboardApi.getStats().then(r => r.data),
  });
}
