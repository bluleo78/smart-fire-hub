import { getLayoutedElements } from '../utils/dagre-layout';
import { wouldCreateCycle } from '../utils/cycle-detection';
import type { PipelineDetailResponse } from '@/types/pipeline';

// === 공개 타입 ===

export interface EditorStep {
  tempId: string;
  name: string;
  description: string;
  scriptType: 'SQL' | 'PYTHON' | 'API_CALL';
  scriptContent: string;
  outputDatasetId: number | null;
  inputDatasetIds: number[];
  dependsOnTempIds: string[];
  position: { x: number; y: number };
  loadStrategy: string;
  apiConfig?: Record<string, unknown>;
  apiConnectionId?: number | null;
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

export const initialState: PipelineEditorState = {
  name: '',
  description: '',
  isActive: true,
  pipelineId: null,
  steps: [],
  selectedStepId: null,
  isDirty: false,
  validationErrors: [],
};

// === 헬퍼 ===

export function createDefaultStep(position: { x: number; y: number }): EditorStep {
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

export function applyAutoLayout(state: PipelineEditorState): PipelineEditorState {
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

// === 리듀서 ===

export function pipelineEditorReducer(
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
        return state;
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
      const tempIdMap = new Map<string, string>();
      const steps: EditorStep[] = detail.steps.map((step) => {
        const tempId = crypto.randomUUID();
        tempIdMap.set(step.name, tempId);
        return {
          tempId,
          name: step.name,
          description: step.description ?? '',
          scriptType: step.scriptType as EditorStep['scriptType'],
          scriptContent: step.scriptContent ?? '',
          outputDatasetId: step.outputDatasetId,
          inputDatasetIds: step.inputDatasetIds,
          dependsOnTempIds: [],
          position: { x: 0, y: 0 },
          loadStrategy: step.loadStrategy ?? 'REPLACE',
          apiConfig: step.apiConfig ?? undefined,
          apiConnectionId: step.apiConnectionId ?? undefined,
        };
      });

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
