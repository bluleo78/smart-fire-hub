import { z } from 'zod';

export const updateProfileSchema = z.object({
  // 이름은 1자 이상 100자 이하 — DB 컬럼 제약과 일치시켜 서버 500 방지 (#26)
  name: z.string().min(1, '이름을 입력하세요').max(100, '이름은 100자 이하여야 합니다'),
  email: z.string().email('유효한 이메일을 입력하세요').optional().or(z.literal('')),
});

export const changePasswordSchema = z.object({
  currentPassword: z.string().min(1, '현재 비밀번호를 입력하세요'),
  newPassword: z.string().min(8, '새 비밀번호는 8자 이상이어야 합니다'),
  confirmPassword: z.string().min(1, '비밀번호 확인을 입력하세요'),
}).refine(data => data.newPassword === data.confirmPassword, {
  message: '비밀번호가 일치하지 않습니다',
  path: ['confirmPassword'],
});

export type UpdateProfileFormData = z.infer<typeof updateProfileSchema>;
export type ChangePasswordFormData = z.infer<typeof changePasswordSchema>;
