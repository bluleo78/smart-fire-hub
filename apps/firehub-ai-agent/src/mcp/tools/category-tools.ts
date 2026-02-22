import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

export function registerCategoryTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool('list_categories', '데이터셋 카테고리 목록을 조회합니다', {}, async () => {
      const result = await apiClient.listCategories();
      return jsonResult(result);
    }),

    safeTool(
      'create_category',
      '새 데이터셋 카테고리를 생성합니다',
      {
        name: z.string().describe('카테고리 이름'),
        description: z.string().optional().describe('카테고리 설명'),
      },
      async (args: { name: string; description?: string }) => {
        const result = await apiClient.createCategory(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'update_category',
      '데이터셋 카테고리를 수정합니다',
      {
        id: z.number().describe('카테고리 ID'),
        name: z.string().describe('카테고리 이름'),
        description: z.string().optional().describe('카테고리 설명'),
      },
      async (args: { id: number; name: string; description?: string }) => {
        const { id, ...data } = args;
        const result = await apiClient.updateCategory(id, data);
        return jsonResult(result);
      },
    ),
  ];
}
