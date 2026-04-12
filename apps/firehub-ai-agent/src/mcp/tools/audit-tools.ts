import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

/**
 * 감사 로그 조회 MCP 도구 등록.
 * audit:read 권한이 있는 세션 사용자에게만 노출된다.
 */
export function registerAuditTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool(
      'list_audit_logs',
      '시스템 감사 로그를 조회합니다. 사용자 활동, 리소스 변경, 실패 이벤트를 검색·필터링할 수 있습니다. 최신 항목부터 정렬됩니다.',
      {
        search: z.string().optional().describe('사용자명 또는 설명 검색어'),
        actionType: z.string().optional().describe('액션 유형 필터 (CREATE, UPDATE, DELETE, LOGIN, LOGOUT 등)'),
        resource: z.string().optional().describe('리소스 유형 필터 (dataset, pipeline, user, trigger, role, api_connection 등)'),
        result: z.string().optional().describe('결과 상태 필터 (SUCCESS, FAILURE)'),
        page: z.number().optional().describe('페이지 번호 (0부터 시작, 기본 0)'),
        size: z.number().optional().describe('페이지 크기 (기본 20, 최대 100)'),
      },
      async (args) => {
        const result = await apiClient.listAuditLogs(args);
        return jsonResult(result);
      },
    ),
  ];
}
