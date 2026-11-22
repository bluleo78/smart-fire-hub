import type { Dispatch } from 'react';
import { useCallback, useRef, useState } from 'react';
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

/**
 * API_CALL 직접 입력 모드의 customUrl 앞뒤 공백을 요청에서 뺀다(WD-909).
 * 검증은 앞뒤 공백을 무시하고 통과시키는데, 저장값에 공백이 남으면 실행기가 실패하진 않지만
 * 끝 공백이 %20 으로 붙어 다른 경로를 호출한다.
 * 연결 모드의 path 는 URL 이 아니라 건드리지 않는다.
 */
function trimCustomUrl(step: PipelineEditorState['steps'][number], apiConfig: PipelineStepRequest['apiConfig']) {
  if (step.scriptType !== 'API_CALL' || step.apiConnectionId != null) return apiConfig;
  const customUrl = apiConfig?.['customUrl'];
  if (typeof customUrl !== 'string') return apiConfig;
  return { ...apiConfig, customUrl: customUrl.trim() };
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
  // 저장 시도 전체(저장 전 검사 + 요청)가 도는 중인가 — 화면 반영용 state 와 겹친 호출 차단용 ref 를 함께 둔다(WD-953).
  // 저장 전 검사(validate)는 출력 데이터셋 컬럼을 네트워크로 가져올 수 있어 mutation isPending 만 보면
  // 그 사이 편집이 열려 있고, 그 편집은 요청에 실리지 않은 채 MARK_SAVED·재조회로 사라진다. 저장 버튼 이중 클릭도 같은 틈으로 들어온다.
  const [persisting, setPersisting] = useState(false);
  const persistingRef = useRef(false);

  /** force: 기준 버전을 보내지 않아 서버가 비교하지 않는다(「내 변경으로 덮어쓰기」) */
  const persistInner = useCallback(async (force: boolean): Promise<boolean> => {
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
      apiConfig: trimCustomUrl(step, stripKvRows(step.apiConfig)),
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

  /** 저장 시도 전체를 한 번에 하나로 묶는다 — overwrite 도 같은 경로라 같은 보호를 받는다 */
  const persist = useCallback(async (force: boolean): Promise<boolean> => {
    // 이미 도는 중이면 두 번째 요청(이중 클릭·충돌 창 중복 클릭)을 무시한다 — 두 번째 PUT 이 409 충돌 창을 띄우는 것을 막는다
    if (persistingRef.current) return false;
    persistingRef.current = true;
    setPersisting(true);
    try {
      return await persistInner(force);
    } finally {
      persistingRef.current = false;
      setPersisting(false);
    }
  }, [persistInner]);

  const save = useCallback(() => persist(false), [persist]);
  const overwrite = useCallback(() => persist(true), [persist]);
  const dismissEditConflict = useCallback(() => setEditConflict(false), []);

  // 저장 전 검사 구간도 저장 중으로 본다 — 화면이 편집을 잠그고 저장 버튼을 막는다(WD-953)
  const isSaving = persisting || createMutation.isPending || updateMutation.isPending;

  return { save, overwrite, editConflict, dismissEditConflict, isSaving };
}
