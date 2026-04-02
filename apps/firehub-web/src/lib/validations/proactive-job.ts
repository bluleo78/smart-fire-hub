import { z } from 'zod';

export const channelConfigSchema = z.object({
  type: z.enum(['CHAT', 'EMAIL']),
  recipientUserIds: z.array(z.number()),
  recipientEmails: z.array(z.string().email('올바른 이메일 형식이 아닙니다')),
  attachPdf: z.boolean().optional(),
});

export const proactiveJobSchema = z.object({
  name: z.string().min(1, '작업 이름을 입력하세요'),
  prompt: z.string().min(1, '분석 프롬프트를 입력하세요'),
  templateId: z.number().nullable().optional(),
  cronExpression: z.string().min(1, '실행 주기를 설정하세요'),
  timezone: z.string().min(1),
  config: z.object({
    channels: z.array(channelConfigSchema),
  }),
});

export type ProactiveJobFormValues = z.infer<typeof proactiveJobSchema>;
export type ChannelConfigValues = z.infer<typeof channelConfigSchema>;
