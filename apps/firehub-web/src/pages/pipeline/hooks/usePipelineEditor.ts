import { useReducer, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { useCreatePipeline, useUpdatePipeline } from '@/hooks/queries/usePipelines';
import { editorPipelineSchema } from '@/lib/validations/pipeline';
import { getLayoutedElements } from '../utils/dagre-layout';
import { wouldCreateCycle } from '../utils/cycle-detection';
import type { PipelineDetailResponse, PipelineStepRequest } from '@/types/pipeline';

// === 공개 타입 ===

export interface EditorStep {
  tempId: string;
  name: string;
  description: string;
  scriptType: 'SQL' | 'PYTHON';
  scriptContent: string;
  outputDatasetId: number | null;
  inputDatasetIds: number[];
  dependsOnTempIds: string[];
  position: { x: number; y: number };
  loadStrategy: string;
}

export interface ValidationError {
  stepTempId: string;
  field: string;
  message: string;
}

export interface PipelineEditorState {
  name: string;
  description: string;
  isActive: boolean;
  pipelineId: number | null;
  steps: EditorStep[];
  selectedStepId: string | null;
  isDirty: boolean;
  validationErrors: ValidationError[];
}

// === 액션 타입 ===

export type EditorAction =
  | { type: 'SET_META'; payload: Partial<Pick<PipelineEditorState, 'name' | 'description' | 'isActive'>> }
  | { type: 'ADD_STEP'; payload: { position: { x: number; y: number } } }
  | { type: 'REMOVE_STEP'; payload: { tempId: string } }
  | { type: 'UPDATE_STEP'; payload: { tempId: string; changes: Partial<EditorStep> } }
  | { type: 'SELECT_STEP'; payload: { tempId: string | null } }
  | { type: 'ADD_EDGE'; payload: { sourceTempId: string; targetTempId: string } }
  | { type: 'REMOVE_EDGE'; payload: { sourceTempId: string; targetTempId: string } }
  | { type: 'ADD_STEP_AFTER'; payload: { sourceTempId: string } }
  | { type: 'INSERT_STEP_BETWEEN'; payload: { sourceTempId: string; targetTempId: string } }
  | { type: 'UPDATE_NODE_POSITION'; payload: { tempId: string; position: { x: number; y: number } } }
  | { type: 'AUTO_LAYOUT' }
  | { type: 'LOAD_FROM_API'; payload: PipelineDetailResponse }
  | { type: 'MARK_SAVED'; payload?: { pipelineId: number } }
  | { type: 'SET_VALIDATION_ERRORS'; payload: ValidationError[] };

// === 초기 상태 ===

const initialState: PipelineEditorState = {
  name: '',
  description: '',
  isActive: true,
  pipelineId: null,
  steps: [],
  selectedStepId: null,
  isDirty: false,
  validationErrors: [],
};

// === 리듀서 ===

function createDefaultStep(position: { x: number; y: number }): EditorStep {
  return {
    tempId: crypto.randomUUID(),
    name: '',
    description: '',
    scriptType: 'SQL',
    scriptContent: '',
    outputDatasetId: null,
    inputDatasetIds: [],
    dependsOnTempIds: [],
    position,
    loadStrategy: 'REPLACE',
  };
}

function applyAutoLayout(state: PipelineEditorState): PipelineEditorState {
  if (state.steps.length === 0) return state;

  const nodes = state.steps.map((step) => ({
    id: step.tempId,
    type: 'step' as const,
    position: step.position,
    data: {},
  }));

  const edges = state.steps.flatMap((step) =>
    step.dependsOnTempIds.map((depTempId) => ({
      id: `${depTempId}-${step.tempId}`,
      source: depTempId,
      target: step.tempId,
    })),
  );

  const { nodes: layoutedNodes } = getLayoutedElements(nodes, edges);

  return {
    ...state,
    steps: state.steps.map((step) => {
      const layouted = layoutedNodes.find((n) => n.id === step.tempId);
      return layouted ? { ...step, position: layouted.position } : step;
    }),
  };
}

function pipelineEditorReducer(
  state: PipelineEditorState,
  action: EditorAction,
): PipelineEditorState {
  switch (action.type) {
    case 'SET_META':
      return { ...state, ...action.payload, isDirty: true, validationErrors: [] };

    case 'ADD_STEP': {
      const newStep = createDefaultStep(action.payload.position);
      return {
        ...state,
        steps: [...state.steps, newStep],
        selectedStepId: newStep.tempId,
        isDirty: true,
        validationErrors: [],
      };
    }

    case 'REMOVE_STEP': {
      const { tempId } = action.payload;
      const removedStep = state.steps.find((s) => s.tempId === tempId);
      const removedDeps = removedStep?.dependsOnTempIds ?? [];
      return {
        ...state,
        steps: state.steps
          .filter((s) => s.tempId !== tempId)
          .map((s) => {
            if (!s.dependsOnTempIds.includes(tempId)) return s;
            // Replace dependency on removed step with the removed step's own dependencies
            const newDeps = s.dependsOnTempIds
              .filter((id) => id !== tempId)
              .concat(removedDeps.filter((id) => !s.dependsOnTempIds.includes(id)));
            return { ...s, dependsOnTempIds: newDeps };
          }),
        selectedStepId: state.selectedStepId === tempId ? null : state.selectedStepId,
        isDirty: true,
        validationErrors: [],
      };
    }

    case 'UPDATE_STEP':
      return {
        ...state,
        steps: state.steps.map((s) =>
          s.tempId === action.payload.tempId
            ? { ...s, ...action.payload.changes }
            : s,
        ),
        isDirty: true,
        validationErrors: state.validationErrors.filter(
          (e) => e.stepTempId !== action.payload.tempId,
        ),
      };

    case 'SELECT_STEP':
      return { ...state, selectedStepId: action.payload.tempId };

    case 'ADD_EDGE': {
      const { sourceTempId, targetTempId } = action.payload;
      const currentEdges = state.steps.flatMap((s) =>
        s.dependsOnTempIds.map((dep) => ({ source: dep, target: s.tempId })),
      );
      if (wouldCreateCycle(currentEdges, sourceTempId, targetTempId)) {
        return state; // 사이클 발생 시 상태 변경 없음
      }
      return {
        ...state,
        steps: state.steps.map((s) =>
          s.tempId === targetTempId &&
          !s.dependsOnTempIds.includes(sourceTempId)
            ? { ...s, dependsOnTempIds: [...s.dependsOnTempIds, sourceTempId] }
            : s,
        ),
        isDirty: true,
      };
    }

    case 'REMOVE_EDGE':
      return {
        ...state,
        steps: state.steps.map((s) =>
          s.tempId === action.payload.targetTempId
            ? {
                ...s,
                dependsOnTempIds: s.dependsOnTempIds.filter(
                  (id) => id !== action.payload.sourceTempId,
                ),
              }
            : s,
        ),
        isDirty: true,
      };

    case 'ADD_STEP_AFTER': {
      const { sourceTempId } = action.payload;
      const sourceStep = state.steps.find((s) => s.tempId === sourceTempId);
      if (!sourceStep) return state;
      const newStep = createDefaultStep({
        x: sourceStep.position.x + 320,
        y: sourceStep.position.y,
      });
      newStep.dependsOnTempIds = [sourceTempId];
      return {
        ...state,
        steps: [...state.steps, newStep],
        selectedStepId: newStep.tempId,
        isDirty: true,
        validationErrors: [],
      };
    }

    case 'INSERT_STEP_BETWEEN': {
      const { sourceTempId, targetTempId } = action.payload;
      const source = state.steps.find((s) => s.tempId === sourceTempId);
      const target = state.steps.find((s) => s.tempId === targetTempId);
      if (!source || !target) return state;
      const newStep = createDefaultStep({
        x: (source.position.x + target.position.x) / 2,
        y: (source.position.y + target.position.y) / 2,
      });
      newStep.dependsOnTempIds = [sourceTempId];
      const updatedSteps = state.steps.map((s) => {
        if (s.tempId === targetTempId) {
          return {
            ...s,
            dependsOnTempIds: s.dependsOnTempIds
              .filter((id) => id !== sourceTempId)
              .concat(newStep.tempId),
          };
        }
        return s;
      });
      const newState: PipelineEditorState = {
        ...state,
        steps: [...updatedSteps, newStep],
        selectedStepId: newStep.tempId,
        isDirty: true,
        validationErrors: [],
      };
      return applyAutoLayout(newState);
    }

    case 'UPDATE_NODE_POSITION':
      return {
        ...state,
        steps: state.steps.map((s) =>
          s.tempId === action.payload.tempId
            ? { ...s, position: action.payload.position }
            : s,
        ),
      };

    case 'AUTO_LAYOUT':
      return applyAutoLayout(state);

    case 'LOAD_FROM_API': {
      const detail = action.payload;
      // 1. 각 스텝에 tempId 할당
      const tempIdMap = new Map<string, string>();
      const steps: EditorStep[] = detail.steps.map((step) => {
        const tempId = crypto.randomUUID();
        tempIdMap.set(step.name, tempId);
        return {
          tempId,
          name: step.name,
          description: step.description ?? '',
          scriptType: step.scriptType,
          scriptContent: step.scriptContent,
          outputDatasetId: step.outputDatasetId,
          inputDatasetIds: step.inputDatasetIds,
          dependsOnTempIds: [], // 2차 패스에서 설정
          position: { x: 0, y: 0 }, // auto-layout에서 설정
          loadStrategy: step.loadStrategy ?? 'REPLACE',
        };
      });

      // 2. dependsOnStepNames → dependsOnTempIds 변환
      for (const step of steps) {
        const originalStep = detail.steps.find((s) => tempIdMap.get(s.name) === step.tempId);
        if (originalStep) {
          step.dependsOnTempIds = originalStep.dependsOnStepNames
            .map((name) => tempIdMap.get(name))
            .filter((id): id is string => id !== undefined);
        }
      }

      const newState: PipelineEditorState = {
        name: detail.name,
        description: detail.description ?? '',
        isActive: detail.isActive,
        pipelineId: detail.id,
        steps,
        selectedStepId: null,
        isDirty: false,
        validationErrors: [],
      };

      // 3. auto-layout 적용
      return applyAutoLayout(newState);
    }

    case 'MARK_SAVED':
      return {
        ...state,
        isDirty: false,
        validationErrors: [],
        pipelineId: action.payload?.pipelineId ?? state.pipelineId,
      };

    case 'SET_VALIDATION_ERRORS':
      return { ...state, validationErrors: action.payload };

    default:
      return state;
  }
}

// === 훅 ===

export function usePipelineEditor(pipelineId?: number) {
  const [state, dispatch] = useReducer(pipelineEditorReducer, initialState);
  const navigate = useNavigate();
  const createMutation = useCreatePipeline();
  // Note: After create, navigate(replace:true) remounts the component with the real pipelineId,
  // so updateMutation is always correctly bound when editing an existing pipeline.
  const updateMutation = useUpdatePipeline(pipelineId ?? 0);

  const lastLoadedDataRef = useRef<PipelineDetailResponse | null>(null);

  const loadFromApi = useCallback(
    (detail: PipelineDetailResponse) => {
      lastLoadedDataRef.current = detail;
      dispatch({ type: 'LOAD_FROM_API', payload: detail });
    },
    [],
  );

  const cancelEdit = useCallback(() => {
    if (lastLoadedDataRef.current) {
      dispatch({ type: 'LOAD_FROM_API', payload: lastLoadedDataRef.current });
    }
  }, []);

  const validate = useCallback((): boolean => {
    const errors: ValidationError[] = [];

    // 파이프라인 레벨 검증
    if (!state.name.trim()) {
      toast.error('파이프라인 이름을 입력하세요');
      return false;
    }

    if (state.steps.length === 0) {
      toast.error('최소 1개의 스텝을 정의하세요');
      return false;
    }

    // 스텝별 검증
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

      // 이름 중복 추적
      const trimmed = step.name.trim();
      if (trimmed) {
        const existing = nameCount.get(trimmed) ?? [];
        existing.push(step.tempId);
        nameCount.set(trimmed, existing);
      }
    }

    // 중복 이름 에러
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
  }, [state]);

  const save = useCallback(async () => {
    if (!validate()) return;

    // EditorStep[] → PipelineStepRequest[] 변환
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
        // 생성 모드
        const result = await createMutation.mutateAsync({
          name: state.name.trim(),
          description: state.description || undefined,
          steps: stepsRequest,
        });
        dispatch({ type: 'MARK_SAVED', payload: { pipelineId: result.data.id } });
        toast.success('파이프라인이 생성되었습니다');
        navigate(`/pipelines/${result.data.id}`, { replace: true });
      } else {
        // 수정 모드
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
  }, [state, validate, createMutation, updateMutation, navigate]);

  const isSaving = createMutation.isPending || updateMutation.isPending;

  return { state, dispatch, save, loadFromApi, cancelEdit, isSaving };
}
