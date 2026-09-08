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
  /** 쿼리 활성화 여부 (기본 true) — 날짜 범위 역전 등 유효하지 않은 필터 조합일 때 false로 API 호출을 보류한다 (#541) */
  enabled?: boolean;
}) {
  const { enabled = true, ...queryParams } = params;
  return useQuery({
    queryKey: ['auditLogs', queryParams],
    queryFn: () => auditLogsApi.getAuditLogs(queryParams).then(r => r.data),
    enabled,
  });
}
