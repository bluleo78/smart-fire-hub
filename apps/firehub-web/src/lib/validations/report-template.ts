import { z } from 'zod';

export const reportTemplateSchema = z.object({
  name: z.string().min(1, '이름을 입력해주세요.').max(100, '100자 이내로 입력해주세요.'),
  description: z.string().max(500, '500자 이내로 입력해주세요.').optional().or(z.literal('')),
});

export type ReportTemplateFormValues = z.infer<typeof reportTemplateSchema>;
