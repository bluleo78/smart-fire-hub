import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

/**
 * 트리거 config 의 유형별 키 안내 — create_trigger·update_trigger 설명이 함께 쓴다(WD-227).
 * 키 이름·범위는 firehub-api TriggerService(createTrigger/updateTrigger/validateScheduleConfig/validateAllowedIps)가
 * 실제로 읽고 검증하는 값 그대로다. 예전 설명은 DATASET_CHANGE 를 {datasetId}(단수)로 안내해 그대로 따르면 400 이었다.
 */
const TRIGGER_CONFIG_GUIDE =
  'SCHEDULE: {cron(필수), timezone?(기본 "Asia/Seoul"), concurrencyPolicy?("SKIP"|"ALLOW", 기본 "SKIP")} — 필드명은 반드시 "cron" (cronExpression/cron_expression 금지). 값은 Spring CronTrigger 6필드("초 분 시 일 월 요일") 또는 5필드("분 시 일 월 요일"). 5필드 입력 시 서버가 앞에 "0 "을 붙여 6필드로 정규화. 예: "0 0 3 * * *" (매일 3시). ' +
  'API: {allowedIps?: 문자열 배열(IPv4 또는 CIDR, 비우면 모든 IP 허용)} — 토큰은 서버가 만든다. ' +
  'PIPELINE_CHAIN: {upstreamPipelineId(필수, 정수), condition?("SUCCESS"|"FAILURE"|"ANY", 기본 "SUCCESS")}. ' +
  'WEBHOOK: {secret?(생성 때만 설정 가능)}. ' +
  'DATASET_CHANGE: {datasetIds(필수, 데이터셋 ID 정수 배열 1개 이상 — 단수 datasetId 아님), pollingIntervalSeconds?(정수 30~3600, 기본 60), debounceSeconds?(정수 0~3600, 기본 0)}. ' +
  '숫자 값은 문자열("60")이 아니라 숫자(60)로 보낸다.';

/**
 * 트리거 config 스키마. 서버가 읽는 키는 타입·범위를 검증하고(문자열 숫자 "60" 이 그대로 가 500 이 나던 문제, WD-227),
 * 그 밖의 키는 통과시킨다 — 조회 결과의 config(webhookId·hasSecret 등 표시값 포함)를 그대로 되돌려 보내는
 * 수정이 스키마에서 막히지 않게 하기 위함이다(웹훅 암호문·webhookId·API tokenHash 같은 서버 소유 키는 응답에 없거나
 * 보내도 무시되고, 서버가 저장값으로 되살린다 — WD-220·WD-243). 유형별 필수 키 검사는 서버에 맡긴다 —
 * 수정 요청에는 유형이 없어 여기서 유형별로 가를 수 없다.
 */
const triggerConfigSchema = z.looseObject({
  cron: z.string().optional(),
  timezone: z.string().optional(),
  concurrencyPolicy: z.enum(['SKIP', 'ALLOW']).optional(),
  allowedIps: z.array(z.string()).optional(),
  upstreamPipelineId: z.number().int().positive().optional(),
  condition: z.enum(['SUCCESS', 'FAILURE', 'ANY']).optional(),
  secret: z.string().optional(),
  datasetIds: z.array(z.number().int().positive()).min(1).optional(),
  pollingIntervalSeconds: z.number().int().min(30).max(3600).optional(),
  debounceSeconds: z.number().int().min(0).max(3600).optional(),
});

/** 조회 응답에서 수정에 필요한 부분만 본 모양. */
type ExistingTrigger = { name?: unknown; config?: unknown };

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
        name: z.string().min(1).describe('트리거 이름'),
        triggerType: z
          .enum(['SCHEDULE', 'API', 'PIPELINE_CHAIN', 'WEBHOOK', 'DATASET_CHANGE'])
          .describe('트리거 유형'),
        description: z.string().optional().describe('트리거 설명'),
        config: triggerConfigSchema.describe(`트리거 설정. ${TRIGGER_CONFIG_GUIDE}`),
      },
      async (args) => {
        const { pipelineId, ...data } = args;
        const result = await apiClient.createTrigger(pipelineId, data);
        return jsonResult(result);
      },
    ),

    safeTool(
      'update_trigger',
      '파이프라인 트리거를 수정합니다. 보낸 필드만 바뀌고 나머지는 유지됩니다 — name 을 빼면 기존 이름을 그대로 쓰고, config 는 보낸 키만 기존 config 위에 덮어씁니다(예: 폴링 주기만 바꾸려면 config: {pollingIntervalSeconds: 120} 만 보내면 감시 데이터셋 목록은 유지). 배열 값(datasetIds·allowedIps)은 보낸 배열로 통째로 바뀌므로 추가·제외할 때는 최종 목록 전체를 보냅니다. 트리거 유형은 바꿀 수 없고, WEBHOOK 시크릿은 수정으로 바꿀 수 없습니다.',
      {
        pipelineId: z.number().int().describe('파이프라인 ID'),
        triggerId: z.number().int().describe('트리거 ID'),
        name: z.string().min(1).optional().describe('새 트리거 이름 (생략 시 기존 이름 유지)'),
        isEnabled: z.boolean().optional().describe('활성화 여부 (생략 시 유지)'),
        description: z
          .string()
          .optional()
          .describe('트리거 설명 (생략 시 유지, 빈 문자열 "" 이면 설명을 지움)'),
        config: triggerConfigSchema
          .optional()
          .describe(`바꿀 config 키만 담은 객체 (생략 시 config 유지). ${TRIGGER_CONFIG_GUIDE}`),
      },
      async (args) => {
        const { pipelineId, triggerId, name, isEnabled, description, config } = args;
        // 서버 계약(UpdateTriggerRequest): name 은 @NotBlank 필수이고, config 를 보내면 저장된 config 를 통째로 바꾼다.
        // 도구는 「보낸 것만 바꾼다」로 쓰이므로 — 「폴링 주기만 바꿔 줘」가 감시 목록을 지우거나(서버가 빈 목록을 400 으로 거부),
        // API 트리거의 allowedIps 수정이 다른 키를 지우거나(tokenHash 는 서버가 따로 보존, WD-243), 연쇄 조건만 바꾼 수정이 상위 파이프라인을
        // 지우지 않게 — 이름이 없거나 config 를 받았을 때만 기존 트리거를 읽어 이름을 채우고 config 를 얕게 합친다(WD-227).
        // isEnabled·description 은 서버가 null 을 「바꾸지 않음」으로 보므로 받은 그대로 넘긴다.
        let resolvedName = name;
        let resolvedConfig: Record<string, unknown> | undefined = config;
        if (resolvedName === undefined || config !== undefined) {
          const existing = (await apiClient.getTrigger(pipelineId, triggerId)) as ExistingTrigger;
          if (resolvedName === undefined) {
            if (typeof existing?.name !== 'string' || existing.name.trim() === '') {
              throw new Error(
                `트리거 ${triggerId}번의 기존 이름을 확인하지 못해 수정할 수 없습니다. name 을 함께 지정해 주세요.`,
              );
            }
            resolvedName = existing.name;
          }
          if (config !== undefined) {
            const existingConfig =
              existing?.config && typeof existing.config === 'object'
                ? (existing.config as Record<string, unknown>)
                : {};
            resolvedConfig = { ...existingConfig, ...config };
          }
        }
        const data: {
          name: string;
          isEnabled?: boolean;
          description?: string;
          config?: Record<string, unknown>;
        } = { name: resolvedName };
        if (isEnabled !== undefined) data.isEnabled = isEnabled;
        if (description !== undefined) data.description = description;
        if (resolvedConfig !== undefined) data.config = resolvedConfig;
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
