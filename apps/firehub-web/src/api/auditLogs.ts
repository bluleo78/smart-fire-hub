import type { AuditLogResponse } from '../types/auditLog';
import type { PageResponse } from '../types/common';
import { client } from './client';

export const auditLogsApi = {
  getAuditLogs: (params: {
    search?: string;
    /** 사용자 ID 정확 일치 필터 (#89) — free-text search와 별도. */
    userId?: number;
    actionType?: string;
    resource?: string;
    result?: string;
    /** 날짜 범위 시작 (ISO 8601, 예: 2026-04-01T00:00:00) */
    startDate?: string;
    /** 날짜 범위 종료 (ISO 8601, 예: 2026-04-30T23:59:59) */
    endDate?: string;
    page?: number;
    size?: number;
  }) => client.get<PageResponse<AuditLogResponse>>('/admin/audit-logs', { params }),
};
