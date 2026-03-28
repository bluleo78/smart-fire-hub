import { z } from 'zod/v4';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

export function registerUiTools(
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    // 1. 데이터셋 정보 위젯 (프론트엔드에서 직접 API fetch — Reference pattern)
    safeTool(
      'show_dataset',
      '채팅에 데이터셋 정보를 카드로 표시합니다. 메타정보와 샘플 데이터를 인터랙티브 카드로 보여줍니다.',
      {
        datasetId: z.coerce.number().describe('표시할 데이터셋 ID'),
      },
      async (args: { datasetId: number }) => {
        return jsonResult({ displayed: true, datasetId: args.datasetId });
      },
    ),

    // 2. 리치 테이블 위젯 (Passthrough — AI가 데이터 직접 전달)
    safeTool(
      'show_table',
      '채팅에 인터랙티브 테이블을 표시합니다. 정렬, 필터, 페이지네이션, CSV 내보내기를 지원합니다. execute_analytics_query 결과를 테이블로 보여줄 때 사용합니다.',
      {
        title: z.string().optional().describe('테이블 제목 (선택)'),
        sql: z.string().describe('테이블 데이터를 조회한 SQL 쿼리 (참조용)'),
        columns: z.array(z.string()).describe('컬럼 목록'),
        rows: z.array(z.record(z.string(), z.unknown())).max(2000, '최대 2000행까지 지원합니다').describe('데이터 행 배열'),
        totalRows: z.number().optional().describe('전체 행 수 (표시용)'),
      },
      async (args: {
        title?: string;
        sql: string;
        columns: string[];
        rows: Record<string, unknown>[];
        totalRows?: number;
      }) => {
        return jsonResult({
          displayed: true,
          rowCount: args.rows.length,
          totalRows: args.totalRows ?? args.rows.length,
        });
      },
    ),

    // 3. 딥링크 이동 (프론트엔드가 자동으로 페이지 이동)
    safeTool(
      'navigate_to',
      '메인 UI의 특정 페이지로 이동합니다. 데이터셋, 파이프라인, 대시보드를 생성하거나 수정한 후 해당 페이지로 자동 이동할 때 사용합니다.',
      {
        type: z.enum(['dataset', 'pipeline', 'dashboard']).describe('이동할 리소스 타입'),
        id: z.coerce.number().describe('리소스 ID'),
        label: z.string().describe('표시할 리소스 이름'),
      },
      async (args: { type: string; id: number; label: string }) => {
        return jsonResult({ navigated: true, type: args.type, id: args.id });
      },
    ),
  ];
}
