import { z } from 'zod';

import type { TemplateSection } from '@/api/proactive';

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
    prompt: z.string().min(1, '분석 프롬프트를 입력하세요'),
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
