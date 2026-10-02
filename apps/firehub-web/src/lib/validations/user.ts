import { z } from 'zod';

import { PASSWORD_POLICY } from '../temp-password';

export const updateProfileSchema = z.object({
  // 이름은 1자 이상 100자 이하 — DB 컬럼 제약과 일치시켜 서버 500 방지 (#26)
  name: z.string().min(1, '이름을 입력하세요').max(100, '이름은 100자 이하여야 합니다'),
  email: z.string().email('유효한 이메일을 입력하세요').optional().or(z.literal('')),
});

export const changePasswordSchema = z.object({
  currentPassword: z.string().min(1, '현재 비밀번호를 입력하세요'),
  // 백엔드 ChangePasswordRequest.newPassword의 @Pattern 규칙과 동기화 (#521) — 대문자/소문자/숫자 각 1자 이상 필요
  newPassword: z
    .string()
    .min(8, '새 비밀번호는 8자 이상이어야 합니다')
    .regex(PASSWORD_POLICY, '대문자, 소문자, 숫자를 모두 포함해야 합니다'),
  confirmPassword: z.string().min(1, '비밀번호 확인을 입력하세요'),
}).refine(data => data.newPassword === data.confirmPassword, {
  message: '비밀번호가 일치하지 않습니다',
  path: ['confirmPassword'],
});

/**
 * 강제 비밀번호 변경(WD-2) — 일반 변경 규칙 + "임시 비밀번호 재사용 금지".
 * 서버 정책 상한(128자)도 여기서 미리 막는다.
 */
export const forcedPasswordChangeSchema = changePasswordSchema
  .refine((data) => data.newPassword.length <= 128, {
    message: '새 비밀번호는 128자 이하여야 합니다',
    path: ['newPassword'],
  })
  .refine((data) => data.newPassword !== data.currentPassword, {
    message: '새 비밀번호는 임시 비밀번호와 달라야 합니다',
    path: ['newPassword'],
  });

export type UpdateProfileFormData = z.infer<typeof updateProfileSchema>;
export type ChangePasswordFormData = z.infer<typeof changePasswordSchema>;

/** 멤버 추가(WD-2). 비밀번호 정책은 서버 AddMemberRequest 와 동일(PASSWORD_POLICY 단일 원본). 오류 문구는 "…은/는 필수입니다" 규칙. */
export const addMemberSchema = z.object({
  // 50 = "user".username·name 컬럼 길이(V1). 서버 AddMemberRequest 와 동일.
  email: z
    .string()
    .trim()
    .min(1, '이메일은 필수입니다')
    .max(50, '이메일은 50자 이하여야 합니다')
    .email('올바른 이메일 형식이 아닙니다'),
  name: z.string().trim().min(1, '이름은 필수입니다').max(50, '이름은 50자 이하여야 합니다'),
  temporaryPassword: z
    .string()
    .min(8, '비밀번호는 8자 이상이어야 합니다')
    .max(128, '비밀번호는 128자 이하여야 합니다')
    .regex(PASSWORD_POLICY, '대문자, 소문자, 숫자를 모두 포함해야 합니다'),
  roleIds: z.array(z.number()),
});
export type AddMemberFormData = z.infer<typeof addMemberSchema>;
