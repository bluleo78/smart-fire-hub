import { z } from 'zod';

export const channelConfigSchema = z.object({
  type: z.enum(['CHAT', 'EMAIL']),
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

export const proactiveJobSchema = z.object({
  name: z.string().min(1, '작업 이름을 입력하세요'),
  prompt: z.string().min(1, '분석 프롬프트를 입력하세요'),
  templateId: z.number().nullable().optional(),
  cronExpression: z.string().min(1, '실행 주기를 설정하세요'),
  timezone: z.string().min(1),
  triggerType: z.enum(['SCHEDULE', 'ANOMALY', 'BOTH']).optional(),
  config: z.object({
    channels: z.array(channelConfigSchema),
    anomaly: anomalyConfigSchema.optional(),
  }),
});

export type ProactiveJobFormValues = z.infer<typeof proactiveJobSchema>;
export type ChannelConfigValues = z.infer<typeof channelConfigSchema>;
export type AnomalyMetricConfigValues = z.infer<typeof anomalyMetricConfigSchema>;
export type AnomalyConfigValues = z.infer<typeof anomalyConfigSchema>;
