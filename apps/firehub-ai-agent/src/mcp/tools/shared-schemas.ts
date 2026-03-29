import { z } from 'zod/v4';

export const canvasSchema = z.object({
  width: z.enum(['full', 'half', 'third']),
  height: z.enum(['full', 'half', 'third']),
  page: z.enum(['current', 'new']).optional().default('current'),
  pageLabel: z.string().optional(),
  replace: z.string().optional(),
}).optional().describe('Canvas layout hint for native mode');
