import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useReducer, useRef } from 'react';

import { pipelinesApi } from '@/api/pipelines';
import type { PipelineDetailResponse } from '@/types/pipeline';

import type { PipelineEditorState } from './pipelineEditorReducer';
import { buildStateFromDetail, initialState, pipelineEditorReducer } from './pipelineEditorReducer';
import { usePipelineSave } from './usePipelineSave';
import { usePipelineValidation } from './usePipelineValidation';

// Re-export types so existing consumers don't need to change their imports
export type { EditorAction,EditorStep, PipelineEditorState, ValidationError } from './pipelineEditorReducer';

export function usePipelineEditor(pipelineId?: number) {
  const [state, dispatch] = useReducer(pipelineEditorReducer, initialState);

  /**
   * 편집 취소 시 되돌아갈 기준 — "마지막으로 서버와 일치했던 편집기 상태" (#742).
   * 예전엔 최초 로드 응답만 담아, 저장 후 수정→취소하면 최초 로드 값으로 돌아가고
   * 이어진 저장이 앞서 저장한 변경을 조용히 덮어썼다. 이제 저장 성공과 서버 재조회 때마다 갱신한다.
   */
  const baselineRef = useRef<PipelineEditorState | null>(null);

  const handleSaved = useCallback((saved: PipelineEditorState) => {
    baselineRef.current = saved;
  }, []);

  const validate = usePipelineValidation(state, dispatch);
  const { save, overwrite, editConflict, dismissEditConflict, isSaving } = usePipelineSave({
    state,
    validate,
    pipelineId,
    dispatch,
    onSaved: handleSaved,
  });
  const queryClient = useQueryClient();

  /**
   * 서버에서 (다시) 불러온 상세를 반영한다.
   * - 취소 기준은 항상 최신 서버 상태로 갱신한다(편집 중에 재조회가 와도 취소하면 최신 값으로 돌아감).
   * - 화면(편집기 상태)은 미저장 변경이 없을 때만 바꾼다 — 편집 중인 입력은 재조회가 덮지 않는다.
   *   (예: 실행 완료 후 증분 스텝 상세 재조회 #733, 저장 후 재조회)
   */
  const loadFromApi = useCallback((detail: PipelineDetailResponse) => {
    const snapshot = buildStateFromDetail(detail);
    baselineRef.current = snapshot;
    dispatch({ type: 'SYNC_FROM_SERVER', payload: snapshot });
  }, []);

  const cancelEdit = useCallback(() => {
    if (baselineRef.current) {
      dispatch({ type: 'RESTORE_SNAPSHOT', payload: baselineRef.current });
    }
  }, []);

  /**
   * 편집 충돌의 「다시 불러오기」(WD-353) — 최신 상세를 서버에서 새로 받아 화면을 그것으로 바꾸고 내 변경을 버린다.
   * SYNC_FROM_SERVER 는 미저장 변경이 있으면 무시하므로 쓸 수 없다 — 무조건 바꾸는 RESTORE_SNAPSHOT 으로 반영한다.
   * 캐시도 갱신해 상세 헤더(수정자·수정 시각)가 함께 최신이 된다.
   */
  const reloadLatest = useCallback(async () => {
    if (!pipelineId) return;
    const detail = await queryClient.fetchQuery({
      queryKey: ['pipelines', pipelineId],
      queryFn: () => pipelinesApi.getPipelineById(pipelineId).then((r) => r.data),
      staleTime: 0,
    });
    const snapshot = buildStateFromDetail(detail);
    baselineRef.current = snapshot;
    dispatch({ type: 'RESTORE_SNAPSHOT', payload: snapshot });
    dismissEditConflict();
  }, [pipelineId, queryClient, dismissEditConflict]);

  return {
    state,
    dispatch,
    save,
    overwrite,
    reloadLatest,
    editConflict,
    dismissEditConflict,
    loadFromApi,
    cancelEdit,
    isSaving,
  };
}
