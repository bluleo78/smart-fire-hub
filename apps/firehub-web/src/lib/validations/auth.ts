import { z } from 'zod';

export const loginSchema = z.object({
  username: z.string().email("유효한 이메일 형식의 아이디를 입력하세요"),
  password: z.string().min(1, "비밀번호를 입력하세요"),
});

export const signupSchema = z.object({
  username: z.string().email("유효한 이메일 형식의 아이디를 입력하세요"),
  password: z.string().min(8, "비밀번호는 8자 이상이어야 합니다"),
  name: z.string().min(1, "이름을 입력하세요"),
  email: z.string().email("유효한 이메일을 입력하세요").optional().or(z.literal('')),
});

export type LoginFormData = z.infer<typeof loginSchema>;
export type SignupFormData = z.infer<typeof signupSchema>;
