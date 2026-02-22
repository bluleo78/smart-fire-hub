import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

export function registerDataTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool(
      'execute_sql_query',
      '데이터셋 테이블에 SQL 쿼리를 실행합니다. SELECT, INSERT, UPDATE, DELETE를 지원합니다. 테이블명은 데이터셋의 tableName을 사용하세요 (get_dataset으로 확인).',
      {
        datasetId: z.number().describe('대상 데이터셋 ID'),
        sql: z.string().describe('실행할 SQL 쿼리. 테이블명은 data."{tableName}" 형식으로 사용'),
        maxRows: z
          .number()
          .min(1)
          .max(1000)
          .optional()
          .describe('최대 반환 행 수 (SELECT 시, 기본 1000)'),
      },
      async (args: { datasetId: number; sql: string; maxRows?: number }) => {
        const result = await apiClient.executeQuery(args.datasetId, args.sql, args.maxRows);
        return jsonResult(result);
      },
    ),

    safeTool(
      'add_row',
      '데이터셋에 단일 행을 추가합니다. 컬럼명-값 쌍으로 데이터를 전달합니다.',
      {
        datasetId: z.number().describe('대상 데이터셋 ID'),
        data: z
          .record(z.string(), z.unknown())
          .describe('컬럼명-값 쌍. 예: {"name": "홍길동", "age": 30}'),
      },
      async (args: { datasetId: number; data: Record<string, unknown> }) => {
        const result = await apiClient.addRow(args.datasetId, args.data);
        return jsonResult(result);
      },
    ),

    safeTool(
      'add_rows',
      '데이터셋에 여러 행을 한번에 추가합니다. 최대 100행까지 지원합니다.',
      {
        datasetId: z.number().describe('대상 데이터셋 ID'),
        rows: z
          .array(z.record(z.string(), z.unknown()))
          .min(1)
          .max(100)
          .describe('추가할 행 배열. 각 항목은 컬럼명-값 쌍'),
      },
      async (args: { datasetId: number; rows: Record<string, unknown>[] }) => {
        const result = await apiClient.addRowsBatch(args.datasetId, args.rows);
        return jsonResult(result);
      },
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
      },
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
      },
    ),

    safeTool(
      'truncate_dataset',
      '데이터셋의 모든 데이터를 삭제합니다. 테이블 구조(스키마)는 유지됩니다. 전체 삭제 시 delete_rows 대신 이 도구를 사용하세요.',
      {
        datasetId: z.number().describe('대상 데이터셋 ID'),
      },
      async (args: { datasetId: number }) => {
        const result = await apiClient.truncateDataset(args.datasetId);
        return jsonResult(result);
      },
    ),

    safeTool(
      'get_row_count',
      '데이터셋의 행 수를 조회합니다. 데이터를 조회하지 않고 빠르게 행 수만 확인할 때 사용합니다.',
      {
        datasetId: z.number().describe('대상 데이터셋 ID'),
      },
      async (args: { datasetId: number }) => {
        const result = await apiClient.getRowCount(args.datasetId);
        return jsonResult(result);
      },
    ),

    safeTool(
      'replace_dataset_data',
      '데이터셋의 모든 데이터를 새 데이터로 교체합니다. 기존 데이터 전체 삭제 후 새 데이터를 삽입합니다 (원자적 트랜잭션). 최대 100행.',
      {
        datasetId: z.number().describe('대상 데이터셋 ID'),
        rows: z
          .array(z.record(z.string(), z.unknown()))
          .min(1)
          .max(100)
          .describe('새로 삽입할 행 배열. 각 항목은 컬럼명-값 쌍'),
      },
      async (args: { datasetId: number; rows: Record<string, unknown>[] }) => {
        const result = await apiClient.replaceDatasetData(args.datasetId, args.rows);
        return jsonResult(result);
      },
    ),
  ];
}
