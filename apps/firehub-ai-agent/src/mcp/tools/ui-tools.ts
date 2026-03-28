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

    // 4. 파이프라인 실행 상태 (Reference pattern)
    safeTool(
      'show_pipeline',
      '채팅에 파이프라인 실행 상태를 카드로 표시합니다. 실행 상태, 스텝 진행률, 소요 시간을 보여줍니다.',
      {
        pipelineId: z.coerce.number().describe('파이프라인 ID'),
      },
      async (args: { pipelineId: number }) => {
        return jsonResult({ displayed: true, pipelineId: args.pipelineId });
      },
    ),

    // 5. 데이터셋 목록 (Passthrough — AI가 list_datasets 결과 전달)
    safeTool(
      'show_dataset_list',
      '채팅에 데이터셋 목록을 카드 리스트로 표시합니다. list_datasets로 조회한 결과를 전달하세요.',
      {
        items: z.array(z.object({
          id: z.coerce.number(),
          name: z.string(),
          datasetType: z.string().optional(),
          rowCount: z.coerce.number().optional(),
          updatedAt: z.string().optional(),
        })).describe('데이터셋 목록'),
      },
      async (args: { items: Array<Record<string, unknown>> }) => {
        return jsonResult({ displayed: true, count: args.items.length });
      },
    ),

    // 6. 파이프라인 목록 (Passthrough)
    safeTool(
      'show_pipeline_list',
      '채팅에 파이프라인 목록을 카드 리스트로 표시합니다. list_pipelines로 조회한 결과를 전달하세요.',
      {
        items: z.array(z.object({
          id: z.coerce.number(),
          name: z.string(),
          isActive: z.boolean().optional(),
          stepCount: z.coerce.number().optional(),
          triggerCount: z.coerce.number().optional(),
          lastStatus: z.string().optional(),
        })).describe('파이프라인 목록'),
      },
      async (args: { items: Array<Record<string, unknown>> }) => {
        return jsonResult({ displayed: true, count: args.items.length });
      },
    ),

    // 7. 대시보드 KPI 요약 (Reference pattern — FE가 dashboard API fetch)
    safeTool(
      'show_dashboard_summary',
      '채팅에 시스템 전체 현황 대시보드를 표시합니다. 데이터셋/파이프라인 수, 건강 상태, 주의 필요 항목 등 KPI를 보여줍니다.',
      {},
      async () => {
        return jsonResult({ displayed: true });
      },
    ),

    // 8. 최근 활동 피드 (Reference pattern — FE가 activity API fetch)
    safeTool(
      'show_activity',
      '채팅에 최근 활동 타임라인을 표시합니다. 파이프라인 실행, 데이터셋 변경, 오류 등 최근 이벤트를 시간순으로 보여줍니다.',
      {
        size: z.coerce.number().optional().describe('표시할 항목 수 (기본 10)'),
      },
      async (args: { size?: number }) => {
        return jsonResult({ displayed: true, size: args.size ?? 10 });
      },
    ),
  ];
}
