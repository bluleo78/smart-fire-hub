import { useQuery } from '@tanstack/react-query';

import { auditLogsApi } from '../../api/auditLogs';

export function useAuditLogs(params: {
  search?: string;
  /** 사용자 ID 정확 일치 필터 (#89) */
  userId?: number;
  actionType?: string;
  resource?: string;
  result?: string;
  /** 날짜 범위 시작 (ISO 8601) */
  startDate?: string;
  /** 날짜 범위 종료 (ISO 8601) */
  endDate?: string;
  page?: number;
  size?: number;
}) {
  return useQuery({
    queryKey: ['auditLogs', params],
    queryFn: () => auditLogsApi.getAuditLogs(params).then(r => r.data),
  });
}
