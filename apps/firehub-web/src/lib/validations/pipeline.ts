import { z } from 'zod';

export const pipelineStepSchema = z.object({
  name: z.string().min(1, '스텝 이름을 입력하세요').max(100),
  description: z.string().optional().or(z.literal('')),
  scriptType: z.enum(['SQL', 'PYTHON'], { message: '스크립트 타입을 선택하세요' }),
  scriptContent: z.string().min(1, '스크립트를 입력하세요'),
  outputDatasetId: z.number({ message: '출력 데이터셋을 선택하세요' }),
  inputDatasetIds: z.array(z.number()).default([]),
  dependsOnStepNames: z.array(z.string()).default([]),
});

export const createPipelineSchema = z.object({
  name: z.string().min(1, '파이프라인 이름을 입력하세요').max(100),
  description: z.string().optional().or(z.literal('')),
  steps: z.array(pipelineStepSchema).min(1, '최소 1개의 스텝을 정의하세요'),
});

export type CreatePipelineFormData = z.infer<typeof createPipelineSchema>;
export type PipelineStepFormData = z.infer<typeof pipelineStepSchema>;

/** 에디터에서 save() 시 사용하는 검증 스키마 */
export const editorStepSchema = z.object({
  name: z.string().min(1, '스텝 이름을 입력하세요').max(100),
  description: z.string().optional().or(z.literal('')),
  scriptType: z.enum(['SQL', 'PYTHON'], { message: '스크립트 타입을 선택하세요' }),
  scriptContent: z.string().min(1, '스크립트를 입력하세요'),
  outputDatasetId: z.number().nullable(),
  inputDatasetIds: z.array(z.number()).default([]),
});

export const editorPipelineSchema = z.object({
  name: z.string().min(1, '파이프라인 이름을 입력하세요').max(100),
  description: z.string().optional().or(z.literal('')),
  steps: z.array(editorStepSchema).min(1, '최소 1개의 스텝을 정의하세요'),
});

export type EditorPipelineFormData = z.infer<typeof editorPipelineSchema>;
