import { client } from './client';
import type { AuditLogResponse } from '../types/auditLog';
import type { PageResponse } from '../types/common';

export const auditLogsApi = {
  getAuditLogs: (params: {
    search?: string;
    actionType?: string;
    resource?: string;
    result?: string;
    page?: number;
    size?: number;
  }) => client.get<PageResponse<AuditLogResponse>>('/admin/audit-logs', { params }),
};
