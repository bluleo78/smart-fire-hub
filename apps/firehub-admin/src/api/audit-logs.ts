import type { PageResponse, PlatformAuditLogQuery, PlatformAuditLogResponse } from '../types/platform';
import { client } from './client';

/** 운영자 콘솔 플랫폼 감사 로그 API(WD-4). 잘못된 기간·페이지 값은 서버가 400. */
export const auditLogsApi = {
  search: (params: PlatformAuditLogQuery) =>
    client.get<PageResponse<PlatformAuditLogResponse>>('/audit-logs', { params }),
};
