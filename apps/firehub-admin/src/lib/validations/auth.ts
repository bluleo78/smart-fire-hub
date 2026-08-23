import { z } from 'zod';

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
