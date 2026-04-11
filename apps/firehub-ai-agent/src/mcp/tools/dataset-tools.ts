import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

export function registerDatasetTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool(
      'list_datasets',
      '데이터셋 목록을 조회합니다',
      {
        categoryId: z.number().optional().describe('카테고리 ID'),
        datasetType: z.enum(['SOURCE', 'DERIVED']).optional().describe('데이터셋 타입 (SOURCE 또는 DERIVED)'),
        search: z.string().optional().describe('검색어'),
        status: z.string().optional().describe('상태 (NONE, CERTIFIED, DEPRECATED)'),
        favoriteOnly: z.boolean().optional().describe('즐겨찾기만 조회 (기본값: false)'),
        page: z.number().optional().describe('페이지 번호 (0부터 시작)'),
        size: z.number().optional().describe('페이지 크기'),
      },
      async (args: {
        categoryId?: number;
        datasetType?: string;
        search?: string;
        status?: string;
        favoriteOnly?: boolean;
        page?: number;
        size?: number;
      }) => {
        const result = await apiClient.listDatasets(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'get_dataset',
      '데이터셋 상세 정보를 조회합니다. 컬럼 정보도 포함됩니다.',
      {
        id: z.number().describe('데이터셋 ID'),
      },
      async (args: { id: number }) => {
        const result = await apiClient.getDataset(args.id);
        return jsonResult(result);
      },
    ),

    safeTool(
      'query_dataset_data',
      '데이터셋의 데이터를 조회합니다',
      {
        id: z.number().describe('데이터셋 ID'),
        search: z.string().optional().describe('검색어'),
        sortBy: z.string().optional().describe('정렬 기준 컬럼명'),
        sortDir: z.string().optional().describe('정렬 방향 (ASC 또는 DESC, 기본값: ASC)'),
        includeTotalCount: z.boolean().optional().describe('전체 행 수 포함 여부 (기본값: true)'),
        page: z.number().optional().describe('페이지 번호 (0부터 시작)'),
        size: z.number().optional().describe('페이지 크기'),
      },
      async (args: {
        id: number;
        search?: string;
        sortBy?: string;
        sortDir?: string;
        includeTotalCount?: boolean;
        page?: number;
        size?: number;
      }) => {
        const { id, ...params } = args;
        const result = await apiClient.queryDatasetData(id, params);
        return jsonResult(result);
      },
    ),

    safeTool(
      'create_dataset',
      '새 데이터셋을 생성합니다. 데이터셋 생성 시 data 스키마에 실제 PostgreSQL 테이블이 생성됩니다.',
      {
        name: z.string().describe('데이터셋 이름'),
        tableName: z.string().describe('테이블 이름 ([a-z][a-z0-9_]* 패턴)'),
        description: z.string().optional().describe('데이터셋 설명'),
        categoryId: z.number().optional().describe('카테고리 ID'),
        datasetType: z
          .enum(['SOURCE', 'DERIVED'])
          .optional()
          .describe('데이터셋 타입 (SOURCE 또는 DERIVED, 기본값: SOURCE)'),
        columns: z
          .array(
            z.object({
              columnName: z.string().describe('컬럼 이름 ([a-z][a-z0-9_]* 패턴)'),
              displayName: z.string().describe('표시 이름'),
              dataType: z
                .string()
                .describe(
                  '데이터 타입 (TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, VARCHAR)',
                ),
              maxLength: z.number().optional().describe('VARCHAR 타입의 최대 길이'),
              isNullable: z.boolean().optional().describe('NULL 허용 여부 (기본값: false)'),
              isIndexed: z.boolean().optional().describe('인덱스 생성 여부 (기본값: false)'),
              isPrimaryKey: z
                .boolean()
                .optional()
                .describe(
                  '기본키 여부 (기본값: false). 기본키 컬럼은 isNullable이 false여야 합니다',
                ),
              description: z.string().optional().describe('컬럼 설명'),
            }),
          )
          .describe('컬럼 목록 (필수)'),
      },
      async (args: {
        name: string;
        tableName: string;
        description?: string;
        categoryId?: number;
        datasetType?: string;
        columns: Array<{
          columnName: string;
          displayName: string;
          dataType: string;
          maxLength?: number;
          isNullable?: boolean;
          isIndexed?: boolean;
          isPrimaryKey?: boolean;
          description?: string;
        }>;
      }) => {
        const result = await apiClient.createDataset(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'update_dataset',
      '데이터셋 정보를 수정합니다 (이름, 설명, 카테고리)',
      {
        id: z.number().describe('데이터셋 ID'),
        name: z.string().optional().describe('데이터셋 이름'),
        description: z.string().optional().describe('데이터셋 설명'),
        categoryId: z.number().optional().describe('카테고리 ID'),
      },
      async (args: { id: number; name?: string; description?: string; categoryId?: number }) => {
        const { id, ...data } = args;
        const result = await apiClient.updateDataset(id, data);
        return jsonResult(result);
      },
    ),

    safeTool(
      'delete_dataset',
      '데이터셋을 삭제합니다. 물리 테이블과 모든 데이터가 영구 제거됩니다. 사용자의 명시적 평문 확인 없이는 호출하지 마세요.',
      {
        id: z.number().describe('삭제할 데이터셋 ID'),
      },
      async (args: { id: number }) => {
        await apiClient.deleteDataset(args.id);
        return jsonResult({ success: true, datasetId: args.id });
      },
    ),

    safeTool(
      'add_dataset_column',
      '데이터셋에 컬럼을 추가합니다. GEOMETRY 컬럼의 경우 SRID 4326을 권장합니다.',
      {
        datasetId: z.number().describe('데이터셋 ID'),
        columnName: z.string().describe('컬럼 이름 ([a-z][a-z0-9_]* 패턴)'),
        displayName: z.string().describe('표시 이름'),
        dataType: z
          .string()
          .describe(
            '데이터 타입 (TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, VARCHAR, GEOMETRY)',
          ),
        maxLength: z.number().optional().describe('VARCHAR 최대 길이'),
        isNullable: z.boolean().optional().describe('NULL 허용 여부 (기본값: true)'),
        isIndexed: z.boolean().optional().describe('인덱스 생성 여부'),
        description: z.string().optional().describe('컬럼 설명'),
      },
      async (args: {
        datasetId: number;
        columnName: string;
        displayName: string;
        dataType: string;
        maxLength?: number;
        isNullable?: boolean;
        isIndexed?: boolean;
        description?: string;
      }) => {
        const { datasetId, ...column } = args;
        const result = await apiClient.addDatasetColumn(datasetId, column);
        return jsonResult(result);
      },
    ),

    safeTool(
      'drop_dataset_column',
      '데이터셋에서 컬럼을 제거합니다. 해당 컬럼의 모든 데이터가 영구 삭제됩니다. 사용자의 명시적 평문 확인 없이 호출하지 마세요.',
      {
        datasetId: z.number().describe('데이터셋 ID'),
        columnId: z.number().describe('컬럼 ID'),
      },
      async (args: { datasetId: number; columnId: number }) => {
        await apiClient.dropDatasetColumn(args.datasetId, args.columnId);
        return jsonResult({ success: true });
      },
    ),
  ];
}
