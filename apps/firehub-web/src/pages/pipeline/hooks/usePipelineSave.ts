import { useCallback } from 'react';
import type { Dispatch } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { useCreatePipeline, useUpdatePipeline } from '@/hooks/queries/usePipelines';
import type { PipelineStepRequest } from '@/types/pipeline';
import type { PipelineEditorState, EditorAction } from './pipelineEditorReducer';

interface UsePipelineSaveOptions {
  state: PipelineEditorState;
  validate: () => boolean;
  pipelineId?: number;
  dispatch: Dispatch<EditorAction>;
}

export function usePipelineSave({
  state,
  validate,
  pipelineId,
  dispatch,
}: UsePipelineSaveOptions): { save: () => Promise<void>; isSaving: boolean } {
  const navigate = useNavigate();
  const createMutation = useCreatePipeline();
  const updateMutation = useUpdatePipeline(pipelineId ?? 0);

  const save = useCallback(async () => {
    if (!validate()) return;

    const stepsRequest: PipelineStepRequest[] = state.steps.map((step) => ({
      name: step.name.trim(),
      description: step.description || undefined,
      scriptType: step.scriptType,
      scriptContent: step.scriptContent,
      outputDatasetId: step.outputDatasetId,
      inputDatasetIds: step.inputDatasetIds,
      dependsOnStepNames: step.dependsOnTempIds
        .map((tempId) => state.steps.find((s) => s.tempId === tempId)?.name.trim())
        .filter((name): name is string => !!name),
      loadStrategy: step.loadStrategy,
    }));

    try {
      if (state.pipelineId === null) {
        const result = await createMutation.mutateAsync({
          name: state.name.trim(),
          description: state.description || undefined,
          steps: stepsRequest,
        });
        dispatch({ type: 'MARK_SAVED', payload: { pipelineId: result.data.id } });
        toast.success('파이프라인이 생성되었습니다');
        navigate(`/pipelines/${result.data.id}`, { replace: true });
      } else {
        await updateMutation.mutateAsync({
          name: state.name.trim(),
          description: state.description || undefined,
          isActive: state.isActive,
          steps: stepsRequest,
        });
        dispatch({ type: 'MARK_SAVED' });
        toast.success('파이프라인이 저장되었습니다');
      }
    } catch {
      toast.error('파이프라인 저장에 실패했습니다');
    }
  }, [state, validate, createMutation, updateMutation, navigate, dispatch]);

  const isSaving = createMutation.isPending || updateMutation.isPending;

  return { save, isSaving };
}
