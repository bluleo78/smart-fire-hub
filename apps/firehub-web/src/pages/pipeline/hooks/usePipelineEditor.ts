import { useCallback, useReducer, useRef } from 'react';

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
  const { save, isSaving } = usePipelineSave({ state, validate, pipelineId, dispatch, onSaved: handleSaved });

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

  return { state, dispatch, save, loadFromApi, cancelEdit, isSaving };
}
