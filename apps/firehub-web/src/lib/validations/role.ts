import { z } from 'zod';

export const createRoleSchema = z.object({
  name: z.string().min(1, '역할 이름을 입력하세요').max(50, '역할 이름은 50자 이하여야 합니다'),
  description: z.string().optional().or(z.literal('')),
});

export const updateRoleSchema = createRoleSchema;

export type CreateRoleFormData = z.infer<typeof createRoleSchema>;
export type UpdateRoleFormData = z.infer<typeof updateRoleSchema>;
