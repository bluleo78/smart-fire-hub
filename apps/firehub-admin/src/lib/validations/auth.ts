import { z } from 'zod';

import { PASSWORD_MAX_BYTES_MESSAGE, PASSWORD_POLICY, withinPasswordMaxBytes } from '../temp-password';

/**
 * 로그인 폼 검증.
 * firehub-web 은 아이디를 이메일로 강제하지만(회원가입이 이메일 기반) 운영자 계정은 승격으로만
 * 생기므로 형식을 단정할 수 없다 — 비어 있지 않기만 요구한다.
 */
export const loginSchema = z.object({
  username: z.string().min(1, '아이디를 입력하세요'),
  password: z.string().min(1, '비밀번호를 입력하세요'),
});

export type LoginFormData = z.infer<typeof loginSchema>;

/**
 * 첫 운영자 가입 폼 검증(WD-749). 서버 가입 규칙과 같다 — 아이디는 이메일, 이메일은 선택,
 * 비밀번호 8자 이상·72바이트 이하·대소문자·숫자 포함, 이름 1~50자.
 * 비밀번호 확인은 클라이언트 전용이라 서버로 보내지 않는다(제출부에서 뺀다).
 */
export const signupSchema = z
  .object({
    username: z.string().min(1, '아이디를 입력하세요').email('이메일 형식으로 입력하세요'),
    name: z.string().trim().min(1, '이름을 입력하세요').max(50, '이름은 50자 이하여야 합니다'),
    // 빈 문자열은 "입력 안 함" — 제출 시 생략한다.
    email: z.union([z.literal(''), z.string().email('이메일 형식으로 입력하세요')]),
    password: z
      .string()
      .min(8, '비밀번호는 8자 이상이어야 합니다')
      .refine(withinPasswordMaxBytes, PASSWORD_MAX_BYTES_MESSAGE)
      .regex(PASSWORD_POLICY, '대문자, 소문자, 숫자를 각각 1자 이상 포함해야 합니다'),
    confirmPassword: z.string().min(1, '비밀번호 확인을 입력하세요'),
  })
  .refine((v) => v.password === v.confirmPassword, {
    message: '비밀번호가 일치하지 않습니다',
    path: ['confirmPassword'],
  });

export type SignupFormData = z.infer<typeof signupSchema>;
