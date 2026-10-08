import { z } from 'zod';

import { PASSWORD_POLICY } from '../temp-password';

/**
 * 운영자 계정 생성 폼(WD-46). 경계는 서버 `CreatePlatformAccountRequest` 와 **같아야** 한다 —
 * 웹의 멤버 추가 스키마(`addMemberSchema`, WD-2)에서 역할만 뺀 것이다. 어긋나면 클라이언트가 통과시킨 값이
 * 서버 400 으로 떨어진다.
 */
export const createAccountSchema = z.object({
  // 50 = "user".username·name 컬럼 길이(V1). 서버 요청 DTO 와 동일.
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
});

export type CreateAccountFormData = z.infer<typeof createAccountSchema>;
