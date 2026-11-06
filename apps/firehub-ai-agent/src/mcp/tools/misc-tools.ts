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
      '데이터셋의 임포트 이력을 최신 순 페이지로 조회합니다(기본 20건). 응답은 { content, page, size, totalElements, totalPages } 이며, 더 오래된 이력은 page 를 늘려 조회하세요.',
      {
        datasetId: z.number().describe('데이터셋 ID'),
        page: z.number().int().min(0).optional().describe('0-based 페이지 번호 (기본: 0)'),
        size: z.number().int().min(1).max(200).optional().describe('페이지당 개수 (기본: 20, 최대 200)'),
      },
      async (args: { datasetId: number; page?: number; size?: number }) => {
        const { datasetId, ...params } = args;
        const result = await apiClient.listImports(datasetId, params);
        return jsonResult(result);
      },
    ),

    safeTool('get_dashboard', '대시보드 통계를 조회합니다', {}, async () => {
      const result = await apiClient.getDashboard();
      return jsonResult(result);
    }),
  ];
}
