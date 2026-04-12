import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

/**
 * 사용자/역할/권한 관련 MCP 도구 등록.
 * 각 도구는 firehub-mcp-server.ts의 TOOL_PERMISSION_REQUIREMENTS에
 * 매핑된 권한이 있어야 호출 가능하다.
 */
export function registerAdminTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool(
      'list_users',
      '사용자 목록을 조회합니다. 이름·이메일 검색과 페이지네이션을 지원합니다.',
      {
        search: z.string().optional().describe('이름 또는 이메일 검색어'),
        page: z.number().optional().describe('페이지 번호 (0부터 시작, 기본 0)'),
        size: z.number().optional().describe('페이지 크기 (기본 20)'),
      },
      async (args) => {
        const result = await apiClient.listUsers(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'get_user',
      '사용자 상세 정보와 할당된 역할 목록을 조회합니다.',
      {
        userId: z.number().describe('사용자 ID'),
      },
      async (args: { userId: number }) => {
        const result = await apiClient.getUser(args.userId);
        return jsonResult(result);
      },
    ),

    safeTool(
      'set_user_roles',
      '사용자에게 역할을 할당합니다. roleIds 배열로 기존 역할이 전부 교체됩니다. 빈 배열이면 모든 역할이 제거됩니다.',
      {
        userId: z.number().describe('사용자 ID'),
        roleIds: z.array(z.number()).describe('할당할 역할 ID 목록 (기존 역할 전부 교체)'),
      },
      async (args: { userId: number; roleIds: number[] }) => {
        await apiClient.setUserRoles(args.userId, args.roleIds);
        return jsonResult({ success: true, userId: args.userId, roleIds: args.roleIds });
      },
    ),

    safeTool(
      'set_user_active',
      '사용자 계정을 활성화하거나 비활성화합니다. 비활성화된 사용자는 로그인할 수 없습니다.',
      {
        userId: z.number().describe('사용자 ID'),
        active: z.boolean().describe('true: 활성화, false: 비활성화'),
      },
      async (args: { userId: number; active: boolean }) => {
        await apiClient.setUserActive(args.userId, args.active);
        return jsonResult({ success: true, userId: args.userId, active: args.active });
      },
    ),

    safeTool(
      'list_roles',
      '시스템에 등록된 모든 역할 목록을 조회합니다. 역할 ID를 set_user_roles에 사용하세요.',
      {},
      async () => {
        const result = await apiClient.listRoles();
        return jsonResult(result);
      },
    ),

    safeTool(
      'list_permissions',
      '시스템 권한 목록을 조회합니다. category로 필터링 가능합니다.',
      {
        category: z.string().optional().describe('권한 카테고리 (예: user, role, dataset, pipeline, trigger)'),
      },
      async (args: { category?: string }) => {
        const result = await apiClient.listPermissions(
          args.category ? { category: args.category } : undefined,
        );
        return jsonResult(result);
      },
    ),
  ];
}
