import { useReducer, useCallback, useRef } from 'react';
import { pipelineEditorReducer, initialState } from './pipelineEditorReducer';
import { usePipelineValidation } from './usePipelineValidation';
import { usePipelineSave } from './usePipelineSave';
import type { PipelineDetailResponse } from '@/types/pipeline';

// Re-export types so existing consumers don't need to change their imports
export type { EditorStep, ValidationError, PipelineEditorState, EditorAction } from './pipelineEditorReducer';

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
