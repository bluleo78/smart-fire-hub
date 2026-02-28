import { useCallback, useReducer, useRef } from 'react';

import type { PipelineDetailResponse } from '@/types/pipeline';

import { initialState,pipelineEditorReducer } from './pipelineEditorReducer';
import { usePipelineSave } from './usePipelineSave';
import { usePipelineValidation } from './usePipelineValidation';

// Re-export types so existing consumers don't need to change their imports
export type { EditorAction,EditorStep, PipelineEditorState, ValidationError } from './pipelineEditorReducer';

export function usePipelineEditor(pipelineId?: number) {
  const [state, dispatch] = useReducer(pipelineEditorReducer, initialState);

  const lastLoadedDataRef = useRef<PipelineDetailResponse | null>(null);

  const validate = usePipelineValidation(state, dispatch);
  const { save, isSaving } = usePipelineSave({ state, validate, pipelineId, dispatch });

  const loadFromApi = useCallback((detail: PipelineDetailResponse) => {
    lastLoadedDataRef.current = detail;
    dispatch({ type: 'LOAD_FROM_API', payload: detail });
  }, []);

  const cancelEdit = useCallback(() => {
    if (lastLoadedDataRef.current) {
      dispatch({ type: 'LOAD_FROM_API', payload: lastLoadedDataRef.current });
    }
  }, []);

  return { state, dispatch, save, loadFromApi, cancelEdit, isSaving };
}
