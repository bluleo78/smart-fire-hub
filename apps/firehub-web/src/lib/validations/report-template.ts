import { z } from 'zod';

export const reportTemplateSchema = z.object({
  // 공백만 있는 이름은 비어 있는 것으로 본다(WD-501) — trim 없이 min(1) 이면 "   " 가 통과해 버튼이 켜지고
  // 서버 @NotBlank 400 을 받았다. trim 이라 앞뒤 공백을 잘라 보낸다.
  name: z.string().trim().min(1, '이름을 입력해주세요.').max(100, '100자 이내로 입력해주세요.'),
  description: z.string().max(500, '500자 이내로 입력해주세요.').optional().or(z.literal('')),
});

export type ReportTemplateFormValues = z.infer<typeof reportTemplateSchema>;
