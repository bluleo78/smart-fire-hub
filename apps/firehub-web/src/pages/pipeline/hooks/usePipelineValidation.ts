import type { Dispatch } from 'react';
import { useCallback } from 'react';
import { toast } from 'sonner';

import { editorPipelineSchema } from '@/lib/validations/pipeline';

import type { EditorAction, PipelineEditorState, ValidationError } from './pipelineEditorReducer';

export function usePipelineValidation(
  state: PipelineEditorState,
  dispatch: Dispatch<EditorAction>,
): () => boolean {
  return useCallback((): boolean => {
    const errors: ValidationError[] = [];

    if (!state.name.trim()) {
      toast.error('파이프라인 이름을 입력하세요');
      return false;
    }

    if (state.steps.length === 0) {
      toast.error('최소 1개의 스텝을 정의하세요');
      return false;
    }

    const nameCount = new Map<string, string[]>();
    for (const step of state.steps) {
      const result = editorPipelineSchema.shape.steps.element.safeParse({
        name: step.name,
        description: step.description,
        scriptType: step.scriptType,
        scriptContent: step.scriptContent,
        outputDatasetId: step.outputDatasetId,
        inputDatasetIds: step.inputDatasetIds,
        loadStrategy: step.loadStrategy,
        apiConfig: step.apiConfig,
        apiConnectionId: step.apiConnectionId,
      });

      if (!result.success) {
        for (const issue of result.error.issues) {
          errors.push({
            stepTempId: step.tempId,
            field: issue.path.join('.'),
            message: issue.message,
          });
        }
      }

      const trimmed = step.name.trim();
      if (trimmed) {
        const existing = nameCount.get(trimmed) ?? [];
        existing.push(step.tempId);
        nameCount.set(trimmed, existing);
      }
    }

    for (const [name, tempIds] of nameCount) {
      if (tempIds.length > 1) {
        for (const tempId of tempIds) {
          errors.push({
            stepTempId: tempId,
            field: 'name',
            message: `스텝 이름 "${name}"이(가) 중복됩니다`,
          });
        }
      }
    }

    if (errors.length > 0) {
      dispatch({ type: 'SET_VALIDATION_ERRORS', payload: errors });
      toast.error('입력 오류를 확인하세요');
      return false;
    }

    return true;
  }, [state, dispatch]);
}
