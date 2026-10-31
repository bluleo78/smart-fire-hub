import { z } from 'zod';

import type { TemplateSection } from '@/api/proactive';
import { cronTooFrequentMessage } from '@/lib/cron-frequency';

/** 분석 프롬프트 길이 상한(자) — 서버 CreateProactiveJobRequest.PROMPT_MAX_LENGTH 와 같다(WD-425). */
export const MAX_JOB_PROMPT_LENGTH = 10_000;

export const channelConfigSchema = z.object({
  type: z.enum(['CHAT', 'EMAIL', 'KAKAO', 'SLACK']),
  recipientUserIds: z.array(z.number()),
  recipientEmails: z.array(z.string().email('올바른 이메일 형식이 아닙니다')),
  attachPdf: z.boolean().optional(),
});

export const anomalyMetricConfigSchema = z.object({
  id: z.string(),
  name: z.string().min(1, '메트릭 이름을 입력하세요'),
  source: z.enum(['system', 'dataset']),
  metricKey: z.string().optional(),
  datasetId: z.number().optional(),
  query: z.string().optional(),
  pollingInterval: z.number().min(60, '최소 60초 이상이어야 합니다'),
});

export const anomalyConfigSchema = z.object({
  enabled: z.boolean(),
  metrics: z.array(anomalyMetricConfigSchema),
  sensitivity: z.enum(['low', 'medium', 'high']),
  cooldownMinutes: z.number().min(1, '최소 1분 이상이어야 합니다'),
});

export const proactiveJobSchema = z
  .object({
    name: z.string().min(1, '작업 이름을 입력하세요').max(200, '이름은 200자 이내여야 합니다'),
    // 상한은 서버 CreateProactiveJobRequest.PROMPT_MAX_LENGTH 와 같다(WD-425). 입력란 maxLength 는 타이핑만 막으므로, 리포트에서
    // 프리필한 긴 질문처럼 코드로 채운 값도 저장 전에 여기서 잡는다.
    prompt: z
      .string()
      .min(1, '분석 프롬프트를 입력하세요')
      .max(MAX_JOB_PROMPT_LENGTH, '분석 프롬프트는 10,000자 이내여야 합니다'),
    templateId: z.number().nullable().optional(),
    // 「목표 기반」으로 만든 섹션 구성(WD-297). 서버로 보내는 작업 필드가 아니라, 생성 때 리포트 양식으로 먼저 저장해 templateId 로
    // 연결하려고 폼에 둔다. null 이면 목표 기반 구성이 없다.
    generatedSections: z.array(z.custom<TemplateSection>()).nullable().optional(),
    cronExpression: z.string().optional(),
    timezone: z.string().min(1),
    triggerType: z.enum(['SCHEDULE', 'ANOMALY', 'BOTH']).optional(),
    config: z.object({
      channels: z.array(channelConfigSchema),
      anomaly: anomalyConfigSchema.optional(),
    }),
  })
  .superRefine((data, ctx) => {
    // ANOMALY 전용이 아닌 경우 cronExpression 필수 (#38/#43)
    if (data.triggerType !== 'ANOMALY' && !data.cronExpression) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: '실행 주기를 설정하세요',
        path: ['cronExpression'],
      });
    }
    // 1분보다 잦은 주기(매초 등)는 서버가 거부한다 — 저장 전에 같은 문구로 막는다(WD-366)
    const tooFrequent = data.triggerType !== 'ANOMALY' ? cronTooFrequentMessage(data.cronExpression) : undefined;
    if (tooFrequent) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: tooFrequent, path: ['cronExpression'] });
    }
  });

/** 리포트 양식 이름 한도(report_template.name VARCHAR(200)). */
const TEMPLATE_NAME_MAX = 200;
const GOAL_TEMPLATE_SUFFIX = ' 양식';

/**
 * 목표 기반 작업이 함께 저장하는 리포트 양식 이름(WD-297) — 「<작업 이름> 양식」. 작업 이름도 200자까지라 그대로 붙이면 양식 이름 한도를
 * 넘으므로 작업 이름을 잘라 맞춘다.
 */
export function goalTemplateName(jobName: string): string {
  return jobName.trim().slice(0, TEMPLATE_NAME_MAX - GOAL_TEMPLATE_SUFFIX.length) + GOAL_TEMPLATE_SUFFIX;
}

export type ProactiveJobFormValues = z.infer<typeof proactiveJobSchema>;
export type ChannelConfigValues = z.infer<typeof channelConfigSchema>;
export type AnomalyMetricConfigValues = z.infer<typeof anomalyMetricConfigSchema>;
export type AnomalyConfigValues = z.infer<typeof anomalyConfigSchema>;
