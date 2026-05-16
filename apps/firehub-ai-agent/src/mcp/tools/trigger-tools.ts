import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

export function registerTriggerTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool(
      'list_triggers',
      '파이프라인의 트리거 목록을 조회합니다',
      {
        pipelineId: z.number().describe('파이프라인 ID'),
      },
      async (args: { pipelineId: number }) => {
        const result = await apiClient.listTriggers(args.pipelineId);
        return jsonResult(result);
      },
    ),

    safeTool(
      'create_trigger',
      '파이프라인 트리거를 생성합니다. 유형: SCHEDULE(크론), API(토큰), PIPELINE_CHAIN(연쇄), WEBHOOK(웹훅), DATASET_CHANGE(데이터 변경)',
      {
        pipelineId: z.number().describe('파이프라인 ID'),
        name: z.string().describe('트리거 이름'),
        triggerType: z
          .enum(['SCHEDULE', 'API', 'PIPELINE_CHAIN', 'WEBHOOK', 'DATASET_CHANGE'])
          .describe('트리거 유형'),
        description: z.string().optional().describe('트리거 설명'),
        config: z
          .record(z.string(), z.unknown())
          .describe(
            '트리거 설정. SCHEDULE: {cron} — 필드명은 반드시 "cron" (cronExpression/cron_expression 금지). 값은 Spring CronTrigger 6필드("초 분 시 일 월 요일") 또는 5필드("분 시 일 월 요일"). 5필드 입력 시 서버가 앞에 "0 "을 붙여 6필드로 정규화. 예: "0 0 3 * * *" (매일 3시). API: {}, PIPELINE_CHAIN: {upstreamPipelineId, condition?}, WEBHOOK: {secret?}, DATASET_CHANGE: {datasetId}',
          ),
      },
      async (args) => {
        const { pipelineId, ...data } = args;
        const result = await apiClient.createTrigger(pipelineId, data);
        return jsonResult(result);
      },
    ),

    safeTool(
      'update_trigger',
      '파이프라인 트리거를 수정합니다',
      {
        pipelineId: z.number().describe('파이프라인 ID'),
        triggerId: z.number().describe('트리거 ID'),
        name: z.string().optional().describe('트리거 이름'),
        isEnabled: z.boolean().optional().describe('활성화 여부'),
        description: z.string().optional().describe('트리거 설명'),
        config: z.record(z.string(), z.unknown()).optional().describe('트리거 설정'),
      },
      async (args) => {
        const { pipelineId, triggerId, ...data } = args;
        const result = await apiClient.updateTrigger(pipelineId, triggerId, data);
        return jsonResult(result);
      },
    ),

    safeTool(
      'delete_trigger',
      '파이프라인 트리거를 삭제합니다',
      {
        pipelineId: z.number().describe('파이프라인 ID'),
        triggerId: z.number().describe('트리거 ID'),
      },
      async (args: { pipelineId: number; triggerId: number }) => {
        const result = await apiClient.deleteTrigger(args.pipelineId, args.triggerId);
        return jsonResult(result);
      },
    ),
  ];
}
