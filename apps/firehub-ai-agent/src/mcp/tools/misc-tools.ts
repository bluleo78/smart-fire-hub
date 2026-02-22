import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

export function registerMiscTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool(
      'list_imports',
      '데이터셋의 임포트 이력을 조회합니다',
      {
        datasetId: z.number().describe('데이터셋 ID'),
      },
      async (args: { datasetId: number }) => {
        const result = await apiClient.listImports(args.datasetId);
        return jsonResult(result);
      },
    ),

    safeTool('get_dashboard', '대시보드 통계를 조회합니다', {}, async () => {
      const result = await apiClient.getDashboard();
      return jsonResult(result);
    }),
  ];
}
