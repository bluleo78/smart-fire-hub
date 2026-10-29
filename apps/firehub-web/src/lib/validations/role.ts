import { z } from 'zod';

export const createRoleSchema = z.object({
  // 앞뒤 공백은 잘라서 검사·전송한다(WD-279) — 입력란에서 끝 공백은 보이지 않아, 예전에 「EDITOR 」처럼 저장된 역할은
  // 설명만 고쳐도 이름 규칙 오류가 나 무엇을 고쳐야 할지 알 수 없었다. 잘라 보내면 그대로 바로잡히거나 서버의 중복 안내를 받는다.
  name: z
    .string()
    .trim()
    .min(1, '역할 이름을 입력하세요')
    .max(50, '역할 이름은 50자 이하여야 합니다')
    .regex(/^[A-Z][A-Z0-9_]*$/, '역할 이름은 대문자, 숫자, 밑줄만 허용됩니다'),
  description: z.string().optional().or(z.literal('')),
});

export const updateRoleSchema = createRoleSchema;

export type CreateRoleFormData = z.infer<typeof createRoleSchema>;
export type UpdateRoleFormData = z.infer<typeof updateRoleSchema>;
