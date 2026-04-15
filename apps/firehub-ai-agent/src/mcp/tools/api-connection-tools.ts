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
      '새 API 연결을 생성합니다. baseUrl은 필수, healthCheckPath는 선택. 인증 정보는 AES-256-GCM 암호화되어 저장됩니다.',
      {
        name: z.string().describe('연결 이름 (예: Make.com API)'),
        description: z.string().optional().describe('연결 설명'),
        authType: z.enum(['API_KEY', 'BEARER', 'OAUTH2']).describe('인증 유형'),
        authConfig: z
          .record(z.string(), z.string())
          .describe(
            '인증 설정 — API_KEY: { placement, headerName/paramName, apiKey }, BEARER: { token }',
          ),
        baseUrl: z
          .string()
          .url()
          .describe('호출 대상 서비스의 기본 URL (예: https://api.make.com/v2, trailing slash 불필요)'),
        healthCheckPath: z
          .string()
          .regex(/^\//)
          .optional()
          .describe('주기적 상태 점검 경로 (예: /health). 생략 시 점검 미수행.'),
      },
      async (args) => {
        const result = await apiClient.createApiConnection(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'update_api_connection',
      'API 연결을 수정합니다. authConfig를 제공하면 인증 정보가 갱신됩니다. baseUrl/healthCheckPath도 변경 가능.',
      {
        id: z.number().describe('API 연결 ID'),
        name: z.string().optional().describe('연결 이름'),
        description: z.string().optional().describe('연결 설명'),
        authType: z.string().optional().describe('인증 유형 (API_KEY 또는 BEARER)'),
        authConfig: z.record(z.string(), z.string()).optional().describe('인증 설정'),
        baseUrl: z.string().url().optional().describe('기본 URL 변경'),
        healthCheckPath: z
          .string()
          .regex(/^\//)
          .optional()
          .describe('상태 점검 경로 변경 (/ 로 시작)'),
      },
      async (args) => {
        const { id, ...data } = args;
        const result = await apiClient.updateApiConnection(id, data);
        return jsonResult(result);
      },
    ),

    safeTool(
      'test_api_connection',
      '저장된 API 연결의 상태를 즉시 점검합니다. healthCheckPath(또는 baseUrl)로 GET 요청 후 2xx 여부로 UP/DOWN 판정합니다. 결과는 DB에 반영되어 이후 list에 노출됩니다.',
      {
        id: z.number().describe('API 연결 ID'),
      },
      async (args: { id: number }) => {
        const result = await apiClient.testApiConnection(args.id);
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
