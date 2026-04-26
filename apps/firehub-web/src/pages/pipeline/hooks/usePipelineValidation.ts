import type { Dispatch } from 'react';
import { useCallback } from 'react';
import { toast } from 'sonner';

import { iGa } from '@/lib/utils';
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

      // AI_CLASSIFY specific validation
      if (step.scriptType === 'AI_CLASSIFY') {
        const cfg = step.aiConfig;
        if (!cfg || !cfg.prompt || !(cfg.prompt as string).trim()) {
          errors.push({ stepTempId: step.tempId, field: 'aiConfig.prompt', message: '프롬프트를 입력하세요' });
        }
        if (!cfg || !cfg.outputColumns || (cfg.outputColumns as unknown[]).length === 0) {
          errors.push({ stepTempId: step.tempId, field: 'aiConfig.outputColumns', message: '출력 컬럼을 최소 1개 정의하세요' });
        }
      }

      // PYTHON 스텝 출력 컬럼 이름 빈값 검증
      // pythonConfig.outputColumns 중 name.trim() === '' 인 항목이 있으면 저장을 차단한다
      if (step.scriptType === 'PYTHON') {
        const cols = step.pythonConfig?.outputColumns ?? [];
        const hasBlankName = cols.some((col) => !col.name.trim());
        if (hasBlankName) {
          errors.push({
            stepTempId: step.tempId,
            field: 'pythonConfig.outputColumns.name',
            message: '출력 컬럼명을 입력하세요',
          });
        }
      }

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
            message: `스텝 이름 "${name}"${iGa(name)} 중복됩니다`,
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
