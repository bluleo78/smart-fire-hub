import { useQuery } from '@tanstack/react-query';
import { auditLogsApi } from '../../api/auditLogs';

export function useAuditLogs(params: {
  search?: string;
  actionType?: string;
  resource?: string;
  result?: string;
  page?: number;
  size?: number;
}) {
  return useQuery({
    queryKey: ['auditLogs', params],
    queryFn: () => auditLogsApi.getAuditLogs(params).then(r => r.data),
  });
}
