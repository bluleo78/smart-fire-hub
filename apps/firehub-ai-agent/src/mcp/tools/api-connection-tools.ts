import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

export function registerApiConnectionTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool('list_api_connections', '저장된 API 연결 목록을 조회합니다', {}, async () => {
      const result = await apiClient.listApiConnections();
      return jsonResult(result);
    }),

    safeTool(
      'get_api_connection',
      'API 연결 상세 정보를 조회합니다 (인증 값은 마스킹됨)',
      {
        id: z.number().describe('API 연결 ID'),
      },
      async (args: { id: number }) => {
        const result = await apiClient.getApiConnection(args.id);
        return jsonResult(result);
      },
    ),

    safeTool(
      'create_api_connection',
      '새 API 연결을 생성합니다. 인증 정보는 암호화되어 저장됩니다.',
      {
        name: z.string().describe('연결 이름'),
        description: z.string().optional().describe('연결 설명'),
        authType: z.enum(['API_KEY', 'BEARER']).describe('인증 유형'),
        authConfig: z
          .record(z.string(), z.string())
          .describe(
            '인증 설정. API_KEY: {placement, headerName/paramName, apiKey}, BEARER: {token}',
          ),
      },
      async (args) => {
        const result = await apiClient.createApiConnection(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'update_api_connection',
      'API 연결을 수정합니다. authConfig를 제공하면 인증 정보가 갱신됩니다.',
      {
        id: z.number().describe('API 연결 ID'),
        name: z.string().optional().describe('연결 이름'),
        description: z.string().optional().describe('연결 설명'),
        authType: z.string().optional().describe('인증 유형 (API_KEY 또는 BEARER)'),
        authConfig: z.record(z.string(), z.string()).optional().describe('인증 설정'),
      },
      async (args) => {
        const { id, ...data } = args;
        const result = await apiClient.updateApiConnection(id, data);
        return jsonResult(result);
      },
    ),

    safeTool(
      'delete_api_connection',
      'API 연결을 삭제합니다',
      {
        id: z.number().describe('API 연결 ID'),
      },
      async (args: { id: number }) => {
        const result = await apiClient.deleteApiConnection(args.id);
        return jsonResult(result);
      },
    ),
  ];
}
