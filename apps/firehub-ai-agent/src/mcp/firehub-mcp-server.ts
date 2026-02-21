import { z } from 'zod/v4';
import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import type { McpSdkServerConfigWithInstance, AnyZodRawShape, InferShape } from '@anthropic-ai/claude-agent-sdk';
import { FireHubApiClient } from './api-client.js';

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

function safeTool<Schema extends AnyZodRawShape>(
  name: string,
  description: string,
  schema: Schema,
  handler: (args: InferShape<Schema>) => Promise<ToolResult>,
) {
  return tool(name, description, schema, async (args: InferShape<Schema>): Promise<ToolResult> => {
    try {
      return await handler(args);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      console.error(`[MCP Tool] ${name} failed: ${message}`);
      return { content: [{ type: 'text', text: message }], isError: true };
    }
  });
}

function jsonResult(data: unknown): ToolResult {
  return { content: [{ type: 'text', text: JSON.stringify(data, null, 2) }] };
}

export function createFireHubMcpServer(apiClient: FireHubApiClient): McpSdkServerConfigWithInstance {
  return createSdkMcpServer({
    name: 'firehub',
    version: '1.0.0',
    tools: [
      safeTool(
        'list_categories',
        '데이터셋 카테고리 목록을 조회합니다',
        {},
        async () => {
          const result = await apiClient.listCategories();
          return jsonResult(result);
        }
      ),

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
        }
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
        }
      ),

      safeTool(
        'list_datasets',
        '데이터셋 목록을 조회합니다',
        {
          categoryId: z.number().optional().describe('카테고리 ID'),
          datasetType: z.string().optional().describe('데이터셋 타입'),
          search: z.string().optional().describe('검색어'),
          page: z.number().optional().describe('페이지 번호 (0부터 시작)'),
          size: z.number().optional().describe('페이지 크기'),
        },
        async (args: { categoryId?: number; datasetType?: string; search?: string; page?: number; size?: number }) => {
          const result = await apiClient.listDatasets(args);
          return jsonResult(result);
        }
      ),

      safeTool(
        'get_dataset',
        '데이터셋 상세 정보를 조회합니다',
        {
          id: z.number().describe('데이터셋 ID'),
        },
        async (args: { id: number }) => {
          const result = await apiClient.getDataset(args.id);
          return jsonResult(result);
        }
      ),

      safeTool(
        'query_dataset_data',
        '데이터셋의 데이터를 조회합니다',
        {
          id: z.number().describe('데이터셋 ID'),
          search: z.string().optional().describe('검색어'),
          page: z.number().optional().describe('페이지 번호 (0부터 시작)'),
          size: z.number().optional().describe('페이지 크기'),
        },
        async (args: { id: number; search?: string; page?: number; size?: number }) => {
          const { id, ...params } = args;
          const result = await apiClient.queryDatasetData(id, params);
          return jsonResult(result);
        }
      ),

      safeTool(
        'get_dataset_columns',
        '데이터셋의 컬럼 정보를 조회합니다',
        {
          id: z.number().describe('데이터셋 ID'),
        },
        async (args: { id: number }) => {
          const result = await apiClient.getDatasetColumns(args.id);
          return jsonResult(result);
        }
      ),

      safeTool(
        'create_dataset',
        '새 데이터셋을 생성합니다. 데이터셋 생성 시 data 스키마에 실제 PostgreSQL 테이블이 생성됩니다.',
        {
          name: z.string().describe('데이터셋 이름'),
          tableName: z.string().describe('테이블 이름 ([a-z][a-z0-9_]* 패턴)'),
          description: z.string().optional().describe('데이터셋 설명'),
          categoryId: z.number().optional().describe('카테고리 ID'),
          datasetType: z.string().optional().describe('데이터셋 타입 (TABLE, VIEW 등)'),
          columns: z.array(z.object({
            columnName: z.string().describe('컬럼 이름 ([a-z][a-z0-9_]* 패턴)'),
            displayName: z.string().describe('표시 이름'),
            dataType: z.string().describe('데이터 타입 (TEXT, INTEGER, BIGINT, DECIMAL, BOOLEAN, DATE, TIMESTAMP, VARCHAR)'),
            maxLength: z.number().optional().describe('VARCHAR 타입의 최대 길이'),
            isNullable: z.boolean().optional().describe('NULL 허용 여부 (기본값: true)'),
            isIndexed: z.boolean().optional().describe('인덱스 생성 여부 (기본값: false)'),
            description: z.string().optional().describe('컬럼 설명'),
          })).optional().describe('컬럼 목록'),
        },
        async (args: {
          name: string; tableName: string; description?: string; categoryId?: number; datasetType?: string;
          columns?: Array<{ columnName: string; displayName: string; dataType: string; maxLength?: number; isNullable?: boolean; isIndexed?: boolean; description?: string }>;
        }) => {
          const result = await apiClient.createDataset(args);
          return jsonResult(result);
        }
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
        }
      ),

      safeTool(
        'list_pipelines',
        '파이프라인 목록을 조회합니다',
        {
          search: z.string().optional().describe('검색어'),
          isActive: z.boolean().optional().describe('활성 상태'),
          page: z.number().optional().describe('페이지 번호 (0부터 시작)'),
          size: z.number().optional().describe('페이지 크기'),
        },
        async (args: { search?: string; isActive?: boolean; page?: number; size?: number }) => {
          const result = await apiClient.listPipelines(args);
          return jsonResult(result);
        }
      ),

      safeTool(
        'get_pipeline',
        '파이프라인 상세 정보를 조회합니다',
        {
          id: z.number().describe('파이프라인 ID'),
        },
        async (args: { id: number }) => {
          const result = await apiClient.getPipeline(args.id);
          return jsonResult(result);
        }
      ),

      safeTool(
        'execute_pipeline',
        '파이프라인을 실행합니다',
        {
          id: z.number().describe('파이프라인 ID'),
        },
        async (args: { id: number }) => {
          const result = await apiClient.executePipeline(args.id);
          return jsonResult(result);
        }
      ),

      safeTool(
        'get_execution_status',
        '파이프라인 실행 상태를 조회합니다',
        {
          id: z.number().describe('실행 ID'),
        },
        async (args: { id: number }) => {
          const result = await apiClient.getExecutionStatus(args.id);
          return jsonResult(result);
        }
      ),

      safeTool(
        'list_imports',
        '데이터 임포트 이력을 조회합니다',
        {
          datasetId: z.number().optional().describe('데이터셋 ID'),
          status: z.string().optional().describe('상태 (PENDING, SUCCESS, FAILED)'),
          page: z.number().optional().describe('페이지 번호 (0부터 시작)'),
          size: z.number().optional().describe('페이지 크기'),
        },
        async (args: { datasetId?: number; status?: string; page?: number; size?: number }) => {
          const result = await apiClient.listImports(args);
          return jsonResult(result);
        }
      ),

      safeTool(
        'get_dashboard',
        '대시보드 통계를 조회합니다',
        {},
        async () => {
          const result = await apiClient.getDashboard();
          return jsonResult(result);
        }
      ),

      safeTool(
        'execute_sql_query',
        '데이터셋 테이블에 SQL 쿼리를 실행합니다. SELECT, INSERT, UPDATE, DELETE를 지원합니다. 테이블명은 데이터셋의 tableName을 사용하세요 (get_dataset으로 확인).',
        {
          datasetId: z.number().describe('대상 데이터셋 ID'),
          sql: z.string().describe('실행할 SQL 쿼리. 테이블명은 data."{tableName}" 형식으로 사용'),
          maxRows: z.number().optional().describe('최대 반환 행 수 (SELECT 시, 기본 100)'),
        },
        async (args: { datasetId: number; sql: string; maxRows?: number }) => {
          const result = await apiClient.executeQuery(args.datasetId, args.sql, args.maxRows);
          return jsonResult(result);
        }
      ),

      safeTool(
        'add_row',
        '데이터셋에 단일 행을 추가합니다. 컬럼명-값 쌍으로 데이터를 전달합니다.',
        {
          datasetId: z.number().describe('대상 데이터셋 ID'),
          data: z.record(z.string(), z.unknown()).describe('컬럼명-값 쌍. 예: {"name": "홍길동", "age": 30}'),
        },
        async (args: { datasetId: number; data: Record<string, unknown> }) => {
          const result = await apiClient.addRow(args.datasetId, args.data);
          return jsonResult(result);
        }
      ),

      safeTool(
        'add_rows',
        '데이터셋에 여러 행을 한번에 추가합니다. 최대 100행까지 지원합니다.',
        {
          datasetId: z.number().describe('대상 데이터셋 ID'),
          rows: z.array(z.record(z.string(), z.unknown())).min(1).max(100).describe('추가할 행 배열. 각 항목은 컬럼명-값 쌍'),
        },
        async (args: { datasetId: number; rows: Record<string, unknown>[] }) => {
          const result = await apiClient.addRowsBatch(args.datasetId, args.rows);
          return jsonResult(result);
        }
      ),

      safeTool(
        'update_row',
        '데이터셋의 기존 행을 수정합니다. 모든 필수(non-nullable) 컬럼 값을 포함해야 합니다.',
        {
          datasetId: z.number().describe('대상 데이터셋 ID'),
          rowId: z.number().describe('수정할 행의 ID'),
          data: z.record(z.string(), z.unknown()).describe('수정할 컬럼명-값 쌍'),
        },
        async (args: { datasetId: number; rowId: number; data: Record<string, unknown> }) => {
          const result = await apiClient.updateRow(args.datasetId, args.rowId, args.data);
          return jsonResult(result);
        }
      ),

      safeTool(
        'delete_rows',
        '데이터셋에서 행을 삭제합니다. 최대 1000행까지 한번에 삭제 가능합니다.',
        {
          datasetId: z.number().describe('대상 데이터셋 ID'),
          rowIds: z.array(z.number()).min(1).max(1000).describe('삭제할 행 ID 배열'),
        },
        async (args: { datasetId: number; rowIds: number[] }) => {
          const result = await apiClient.deleteRows(args.datasetId, args.rowIds);
          return jsonResult(result);
        }
      ),
    ],
  });
}
