import type { AxiosInstance } from 'axios';

/** 감사 로그 항목 */
export interface AuditLogResponse {
  id: number;
  userId: number;
  username: string;
  actionType: string;
  resource: string;
  resourceId: string | null;
  description: string;
  actionTime: string;
  ipAddress: string | null;
  userAgent: string | null;
  result: string;
  errorMessage: string | null;
  metadata: unknown | null;
}

/** 감사 로그 페이지 응답 */
export interface AuditLogPage {
  content: AuditLogResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * 감사 로그 조회 API 서브모듈.
 * firehub-api의 /admin/audit-logs 엔드포인트를 호출한다.
 * audit:read 권한이 있는 ADMIN 역할만 접근 가능하다.
 */
export function createAuditApi(client: AxiosInstance) {
  return {
    /**
     * 감사 로그 목록 조회.
     * 최신 항목부터 정렬되며, 필터/페이지네이션을 지원한다.
     */
    async listAuditLogs(params?: {
      search?: string;
      actionType?: string;
      resource?: string;
      result?: string;
      page?: number;
      size?: number;
    }): Promise<AuditLogPage> {
      const response = await client.get<AuditLogPage>('/admin/audit-logs', { params });
      return response.data;
    },
  };
}
