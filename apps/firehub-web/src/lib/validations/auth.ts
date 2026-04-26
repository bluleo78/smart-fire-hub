import { z } from 'zod';

export const loginSchema = z.object({
  username: z.string().email("유효한 이메일 형식의 아이디를 입력하세요"),
  password: z.string().min(1, "비밀번호를 입력하세요"),
});

export const signupSchema = z.object({
  username: z.string().email("유효한 이메일 형식의 아이디를 입력하세요"),
  password: z
    .string()
    .min(8, "비밀번호는 8자 이상이어야 합니다")
    .regex(
      /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).+$/,
      "비밀번호는 영문 대문자, 소문자, 숫자를 각각 하나 이상 포함해야 합니다",
    ),
  // 이름 필드: 1자 이상 100자 이하 — DB 컬럼 제약과 일치, 100자 초과 시 서버 500 방지
  name: z.string().min(1, "이름을 입력하세요").max(100, "이름은 100자 이하여야 합니다"),
  email: z.string().email("유효한 이메일을 입력하세요").optional().or(z.literal('')),
});

export type LoginFormData = z.infer<typeof loginSchema>;
export type SignupFormData = z.infer<typeof signupSchema>;
