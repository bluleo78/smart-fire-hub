import type { Dispatch } from 'react';
import { useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { useCreatePipeline, useUpdatePipeline } from '@/hooks/queries/usePipelines';
import { handleApiError, isEditConflict } from '@/lib/api-error';
import type { PipelineStepRequest } from '@/types/pipeline';

import type { EditorAction,PipelineEditorState } from './pipelineEditorReducer';
import { stripKvRows } from './stepColumnValidation';

interface UsePipelineSaveOptions {
  state: PipelineEditorState;
  /** 저장 전 검증 — 출력 데이터셋 컬럼 조회가 있어 비동기다(WD-154) */
  validate: () => Promise<boolean>;
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
}: UsePipelineSaveOptions): {
  save: () => Promise<boolean>;
  /** 편집 충돌(409 EDIT_CONFLICT) 뒤 기준 버전 없이 다시 저장한다 — 먼저 저장된 변경을 내 변경으로 바꾼다(WD-353) */
  overwrite: () => Promise<boolean>;
  /** 마지막 저장이 편집 충돌로 거부됐는가 — 참이면 화면이 EditConflictDialog 를 띄운다 */
  editConflict: boolean;
  dismissEditConflict: () => void;
  isSaving: boolean;
} {
  const navigate = useNavigate();
  const createMutation = useCreatePipeline();
  const updateMutation = useUpdatePipeline(pipelineId ?? 0);
  const [editConflict, setEditConflict] = useState(false);

  /** force: 기준 버전을 보내지 않아 서버가 비교하지 않는다(「내 변경으로 덮어쓰기」) */
  const persist = useCallback(async (force: boolean): Promise<boolean> => {
    if (!(await validate())) return false;

    const stepsRequest: PipelineStepRequest[] = state.steps.map((step) => ({
      // 기존 스텝 id 를 함께 보내 서버가 같은 스텝을 제자리 갱신하게 한다(WD-108) — 이름을 바꿔도 과거 실행 기록이 이어진다.
      id: step.id,
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
      apiConfig: stripKvRows(step.apiConfig),
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
        const res = await updateMutation.mutateAsync({
          name: state.name.trim(),
          description: state.description || undefined,
          isActive: state.isActive,
          steps: stepsRequest,
          // 편집 기준 버전(WD-353) — 그 사이 다른 곳에서 저장됐으면 서버가 409 EDIT_CONFLICT 로 거부한다
          ...(force ? {} : { expectedUpdatedAt: state.baseUpdatedAt }),
        });
        // 새 버전을 바로 기준으로 삼는다 — 재조회를 기다리면 그 사이 다시 고쳐 저장할 때 자기 저장과 충돌한다.
        // 본문이 없으면(옛 서버·204 모킹) null — 다음 저장은 비교 없이 나간다(자기 충돌보다 안전한 쪽)
        const updatedAt = res?.data?.updatedAt ?? null;
        dispatch({ type: 'MARK_SAVED', payload: { updatedAt } });
        onSaved?.({ ...state, isDirty: false, validationErrors: [], baseUpdatedAt: updatedAt });
        setEditConflict(false);
        toast.success('파이프라인이 저장되었습니다.');
      }
      return true;
    } catch (error) {
      // 편집 충돌은 토스트 대신 안내 창으로 — 사용자가 다시 불러올지 덮어쓸지 고른다(WD-353)
      if (isEditConflict(error)) {
        setEditConflict(true);
        return false;
      }
      // 백엔드 검증 실패(SQL 가드, 중복 이름 등) 시 구체적 원인 메시지를 그대로 노출한다
      handleApiError(error, '파이프라인 저장에 실패했습니다.');
      return false;
    }
  }, [state, validate, createMutation, updateMutation, navigate, dispatch, onSaved]);

  const save = useCallback(() => persist(false), [persist]);
  const overwrite = useCallback(() => persist(true), [persist]);
  const dismissEditConflict = useCallback(() => setEditConflict(false), []);

  const isSaving = createMutation.isPending || updateMutation.isPending;

  return { save, overwrite, editConflict, dismissEditConflict, isSaving };
}
