import { z } from 'zod';

/**
 * 역할 이름 입력 규칙 안내(WD-817) — 아래 스키마(정규식·50자·앞뒤 공백 잘라냄)를 사람 말로 옮긴 문구.
 * 예전에는 규칙이 제출 때 오류로만 드러나 「editor」처럼 입력한 뒤에야 알 수 있었다. 생성 다이얼로그와 상세 편집이
 * 같은 문구를 입력 전에 보이도록 여기 한 곳에 둔다 — 스키마를 바꾸면 이 문구도 함께 고친다.
 */
export const ROLE_NAME_RULE = '대문자로 시작하고 대문자·숫자·밑줄(_)만, 50자까지 쓸 수 있습니다. 예: DATA_EDITOR';

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
