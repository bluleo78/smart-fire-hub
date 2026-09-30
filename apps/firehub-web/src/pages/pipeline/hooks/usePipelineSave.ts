import type { Dispatch } from 'react';
import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { useCreatePipeline, useUpdatePipeline } from '@/hooks/queries/usePipelines';
import { handleApiError } from '@/lib/api-error';
import type { PipelineStepRequest } from '@/types/pipeline';

import type { EditorAction,PipelineEditorState } from './pipelineEditorReducer';

interface UsePipelineSaveOptions {
  state: PipelineEditorState;
  validate: () => boolean;
  pipelineId?: number;
  dispatch: Dispatch<EditorAction>;
  /**
   * 저장 성공 시 "서버에 저장된 것과 같은" 편집기 상태를 넘긴다 (#742).
   * 편집 취소의 복원 기준을 저장 직후 곧바로 갱신하기 위함 — 저장 후 재조회가 도착하기 전에
   * 수정→취소해도 방금 저장한 값으로 돌아가야 한다.
   */
  onSaved?: (saved: PipelineEditorState) => void;
}

export function usePipelineSave({
  state,
  validate,
  pipelineId,
  dispatch,
  onSaved,
}: UsePipelineSaveOptions): { save: () => Promise<boolean>; isSaving: boolean } {
  const navigate = useNavigate();
  const createMutation = useCreatePipeline();
  const updateMutation = useUpdatePipeline(pipelineId ?? 0);

  const save = useCallback(async (): Promise<boolean> => {
    if (!validate()) return false;

    const stepsRequest: PipelineStepRequest[] = state.steps.map((step) => ({
      name: step.name.trim(),
      description: step.description || undefined,
      scriptType: step.scriptType,
      scriptContent: (step.scriptType === 'API_CALL' || step.scriptType === 'AI_CLASSIFY') ? undefined : step.scriptContent,
      outputDatasetId: step.outputDatasetId,
      inputDatasetIds: step.inputDatasetIds,
      dependsOnStepNames: step.dependsOnTempIds
        .map((tempId) => state.steps.find((s) => s.tempId === tempId)?.name.trim())
        .filter((name): name is string => !!name),
      loadStrategy: step.loadStrategy,
      apiConfig: step.apiConfig,
      aiConfig: step.aiConfig,
      pythonConfig: step.pythonConfig,
      apiConnectionId: step.apiConnectionId,
    }));

    try {
      if (state.pipelineId === null) {
        const result = await createMutation.mutateAsync({
          name: state.name.trim(),
          description: state.description || undefined,
          steps: stepsRequest,
        });
        dispatch({ type: 'MARK_SAVED', payload: { pipelineId: result.data.id } });
        onSaved?.({ ...state, pipelineId: result.data.id, isDirty: false, validationErrors: [] });
        toast.success('파이프라인이 생성되었습니다.');
        navigate(`/pipelines/${result.data.id}`, { replace: true });
      } else {
        await updateMutation.mutateAsync({
          name: state.name.trim(),
          description: state.description || undefined,
          isActive: state.isActive,
          steps: stepsRequest,
        });
        dispatch({ type: 'MARK_SAVED' });
        onSaved?.({ ...state, isDirty: false, validationErrors: [] });
        toast.success('파이프라인이 저장되었습니다.');
      }
      return true;
    } catch (error) {
      // 백엔드 검증 실패(SQL 가드, 중복 이름 등) 시 구체적 원인 메시지를 그대로 노출한다
      handleApiError(error, '파이프라인 저장에 실패했습니다.');
      return false;
    }
  }, [state, validate, createMutation, updateMutation, navigate, dispatch, onSaved]);

  const isSaving = createMutation.isPending || updateMutation.isPending;

  return { save, isSaving };
}
