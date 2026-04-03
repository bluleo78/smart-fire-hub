import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

export function registerProactiveTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool(
      'list_proactive_jobs',
      '스마트 작업(정기 AI 분석) 목록을 조회합니다',
      {},
      async () => {
        const result = await apiClient.listSmartJobs();
        return jsonResult(result);
      },
    ),

    safeTool(
      'create_proactive_job',
      '새 스마트 작업을 생성합니다. cron 스케줄에 따라 AI가 자동으로 분석을 실행하고 결과를 전달합니다.',
      {
        name: z.string().describe('작업 이름'),
        prompt: z.string().describe('AI 분석 프롬프트 (어떤 분석을 수행할지 기술)'),
        cronExpression: z
          .string()
          .describe('cron 표현식 (예: "0 9 * * *" = 매일 오전 9시, "0 9 * * 1" = 매주 월요일 오전 9시)'),
        timezone: z.string().optional().describe('타임존 (기본: Asia/Seoul)'),
        templateId: z.number().optional().describe('리포트 양식 ID'),
        channels: z
          .array(z.string())
          .optional()
          .describe('전달 채널 목록 (기본: ["CHAT"])'),
      },
      async (args: {
        name: string;
        prompt: string;
        cronExpression: string;
        timezone?: string;
        templateId?: number;
        channels?: string[];
      }) => {
        const result = await apiClient.createSmartJob(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'update_proactive_job',
      '스마트 작업을 수정합니다. 활성화/비활성화, 스케줄, 프롬프트 등을 변경할 수 있습니다.',
      {
        id: z.number().describe('스마트 작업 ID'),
        name: z.string().optional().describe('작업 이름'),
        prompt: z.string().optional().describe('AI 분석 프롬프트'),
        cronExpression: z.string().optional().describe('cron 표현식'),
        timezone: z.string().optional().describe('타임존'),
        templateId: z.number().optional().describe('리포트 양식 ID'),
        channels: z.array(z.string()).optional().describe('전달 채널 목록'),
        enabled: z.boolean().optional().describe('활성화 여부'),
      },
      async (args: {
        id: number;
        name?: string;
        prompt?: string;
        cronExpression?: string;
        timezone?: string;
        templateId?: number;
        channels?: string[];
        enabled?: boolean;
      }) => {
        const { id, ...data } = args;
        const result = await apiClient.updateSmartJob(id, data);
        return jsonResult(result);
      },
    ),

    safeTool(
      'delete_proactive_job',
      '스마트 작업을 삭제합니다',
      {
        id: z.number().describe('스마트 작업 ID'),
      },
      async (args: { id: number }) => {
        const result = await apiClient.deleteSmartJob(args.id);
        return jsonResult(result);
      },
    ),

    safeTool(
      'execute_proactive_job',
      '스마트 작업을 즉시 실행합니다. 스케줄을 기다리지 않고 지금 바로 AI 분석을 실행합니다.',
      {
        id: z.number().describe('스마트 작업 ID'),
      },
      async (args: { id: number }) => {
        const result = await apiClient.executeSmartJob(args.id);
        return jsonResult(result);
      },
    ),

    safeTool(
      'list_report_templates',
      '리포트 양식 목록을 조회합니다',
      {},
      async () => {
        const result = await apiClient.listReportTemplates();
        return jsonResult(result);
      },
    ),

    safeTool(
      'create_report_template',
      '커스텀 리포트 양식을 생성합니다. 스마트 작업의 AI 분석 결과를 구조화된 형식으로 출력하는 데 사용됩니다.',
      {
        name: z.string().describe('양식 이름'),
        description: z.string().optional().describe('양식 설명'),
        structure: z
          .object({
            sections: z.array(
              z.object({
                key: z.string().describe('섹션 키 (영문, 고유 식별자)'),
                label: z.string().describe('섹션 레이블 (표시 이름)'),
                required: z.boolean().optional().describe('필수 여부'),
                type: z.string().optional().describe('섹션 타입 (예: text, table, chart)'),
              }),
            ),
            output_format: z.string().describe('출력 형식 (예: "markdown")'),
          })
          .describe('리포트 구조 정의'),
      },
      async (args: {
        name: string;
        description?: string;
        structure: {
          sections: Array<{
            key: string;
            label: string;
            required?: boolean;
            type?: string;
          }>;
          output_format: string;
        };
      }) => {
        const result = await apiClient.createReportTemplate(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'generate_report',
      '비즈니스 질문을 기반으로 데이터를 탐색하고 구조화된 리포트 섹션을 생성합니다. 사용자가 분석 요청을 하면 이 도구를 사용하세요.',
      {
        question: z.string().describe('비즈니스 질문'),
        datasetIds: z
          .array(z.number())
          .optional()
          .describe('분석 대상 데이터셋 ID 목록'),
        templateStructure: z
          .object({
            sections: z.array(
              z.object({
                key: z.string(),
                label: z.string(),
                type: z.string(),
                instruction: z.string().optional(),
                required: z.boolean().optional(),
              }),
            ),
            output_format: z.string().optional(),
          })
          .describe('리포트 구조'),
        sectionContents: z
          .record(z.string(), z.string())
          .describe('각 섹션의 분석 결과'),
        style: z.string().optional().describe('작성 스타일'),
      },
      async (args: {
        question: string;
        datasetIds?: number[];
        templateStructure: {
          sections: Array<{
            key: string;
            label: string;
            type: string;
            instruction?: string;
            required?: boolean;
          }>;
          output_format?: string;
        };
        sectionContents: Record<string, string>;
        style?: string;
      }) => {
        return jsonResult({
          displayed: true,
          question: args.question,
          datasetIds: args.datasetIds ?? [],
          templateStructure: args.templateStructure,
          sectionContents: args.sectionContents,
          style: args.style,
        });
      },
    ),

    safeTool(
      'save_as_smart_job',
      '챗에서 생성한 분석을 스마트 작업 + 리포트 양식으로 저장합니다.',
      {
        name: z.string().describe('스마트 작업 이름'),
        templateStructure: z
          .object({
            sections: z.array(
              z.object({
                key: z.string(),
                label: z.string(),
                type: z.string(),
                instruction: z.string().optional(),
                required: z.boolean().optional(),
              }),
            ),
            output_format: z.string().optional(),
          })
          .describe('리포트 양식 구조'),
        prompt: z.string().describe('AI 분석 프롬프트'),
        style: z.string().optional(),
        cronExpression: z.string().optional().describe('Cron 표현식'),
        channels: z.array(z.string()).optional(),
      },
      async (args: {
        name: string;
        templateStructure: {
          sections: Array<{
            key: string;
            label: string;
            type: string;
            instruction?: string;
            required?: boolean;
          }>;
          output_format?: string;
        };
        prompt: string;
        style?: string;
        cronExpression?: string;
        channels?: string[];
      }) => {
        const result = await apiClient.createSmartJobWithTemplate({
          name: args.name,
          prompt: args.prompt,
          cronExpression: args.cronExpression,
          channels: args.channels,
          templateName: `${args.name} 양식`,
          templateStructure: {
            sections: args.templateStructure.sections,
            output_format: args.templateStructure.output_format ?? 'markdown',
          },
          templateStyle: args.style,
        });
        return jsonResult(result);
      },
    ),
  ];
}
