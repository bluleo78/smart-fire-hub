import type { AiClassifyConfig, PipelineDetailResponse, PythonStepConfig } from '@/types/pipeline';

import { wouldCreateCycle } from '../utils/cycle-detection';
import { getLayoutedElements } from '../utils/dagre-layout';

const generateId = (): string =>
  typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2, 11)}`;

// === 공개 타입 ===

export interface EditorStep {
  tempId: string;
  /**
   * 서버에 저장된 스텝 id(WD-108). 상세 응답에서 불러온 스텝에만 있고 새로 추가한 스텝은 없다.
   * 저장 시 그대로 보내 서버가 같은 스텝을 제자리 갱신하게 하고(과거 실행 기록 유지), 실행 상세에서 스텝 기록을 찾는 키로 쓴다.
   */
  id?: number;
  name: string;
  description: string;
  scriptType: 'SQL' | 'PYTHON' | 'API_CALL' | 'AI_CLASSIFY';
  scriptContent: string;
  /** 저장할 출력 — null 이면 「자동 생성」(실행기가 임시 데이터셋을 만들고 SELECT 열이 바뀌면 다시 만든다) */
  outputDatasetId: number | null;
  /**
   * 자동 생성 스텝의 현재 임시 데이터셋 id(WD-222) — 표시 전용(뒤 스텝의 입력 후보 「[스텝N]」). 저장 요청에는 보내지 않는다.
   * outputDatasetId 에 넣으면 저장이 임시 데이터셋을 명시 출력으로 고정해, 열을 바꿔도 임시 데이터셋이 다시 만들어지지 않았다.
   */
  autoOutputDatasetId?: number | null;
  inputDatasetIds: number[];
  dependsOnTempIds: string[];
  position: { x: number; y: number };
  loadStrategy: string;
  apiConfig?: Record<string, unknown>;
  aiConfig?: AiClassifyConfig;
  pythonConfig?: PythonStepConfig;
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
  /**
   * 지금 화면 내용의 기준 버전 — 그 내용을 불러온(또는 저장한) 시점의 서버 updatedAt(WD-353 낙관적 잠금).
   * 저장할 때 expectedUpdatedAt 으로 보내 그 사이 다른 곳에서 저장됐으면 409 로 막는다.
   * 상세 재조회 값(pipelineData.updatedAt)을 그대로 쓰면 안 된다: 미저장 변경이 있을 때 재조회는 화면을 바꾸지 않으므로
   * 기준만 최신으로 넘어가 다른 탭의 저장을 조용히 덮어쓴다. 그래서 화면 내용과 함께 바뀌도록 편집기 상태에 둔다.
   * 서버 문자열 그대로 둔다(Date 로 바꾸면 마이크로초가 잘려 매번 충돌한다). null 이면 비교하지 않는다(새 파이프라인).
   */
  baseUpdatedAt: string | null;
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
  // updatedAt: 저장 응답의 새 버전(WD-353) — 재조회를 기다리지 않고 다음 저장의 기준으로 쓴다
  | { type: 'MARK_SAVED'; payload?: { pipelineId?: number; updatedAt?: string | null } }
  // 취소 기준(마지막으로 서버와 일치했던 상태)으로 무조건 되돌린다 — 편집 취소용 (#742)
  | { type: 'RESTORE_SNAPSHOT'; payload: PipelineEditorState }
  // 서버에서 다시 불러온 상태를 반영하되, 미저장 변경이 있으면 무시한다 — 재조회용 (#742)
  | { type: 'SYNC_FROM_SERVER'; payload: PipelineEditorState }
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
  baseUpdatedAt: null,
};

// === 헬퍼 ===

export function createDefaultStep(position: { x: number; y: number }): EditorStep {
  return {
    tempId: generateId(),
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

/**
 * 서버 상세 응답을 편집기 상태로 변환한다 (의존 관계는 스텝 이름 → tempId 로 다시 잇고 자동 배치한다).
 * LOAD_FROM_API 와, 취소 기준 스냅샷을 만드는 usePipelineEditor 가 함께 쓴다 (#742).
 */
export function buildStateFromDetail(detail: PipelineDetailResponse): PipelineEditorState {
  const tempIdMap = new Map<string, string>();
  const steps: EditorStep[] = detail.steps.map((step) => {
    const tempId = generateId();
    tempIdMap.set(step.name, tempId);
    return {
      tempId,
      id: step.id,
      name: step.name,
      description: step.description ?? '',
      scriptType: step.scriptType as EditorStep['scriptType'],
      scriptContent: step.scriptContent ?? '',
      // 자동 생성 스텝은 응답의 outputDatasetId 가 임시 데이터셋 id 다(화면 표시용 해석값) — 편집 상태에서는 「자동 생성」(null)으로 두고
      // 그 id 는 표시용으로만 따로 보관한다(WD-222).
      outputDatasetId: step.outputAutoGenerated ? null : step.outputDatasetId,
      autoOutputDatasetId: step.outputAutoGenerated ? step.outputDatasetId : null,
      inputDatasetIds: step.inputDatasetIds,
      dependsOnTempIds: [],
      position: { x: 0, y: 0 },
      loadStrategy: step.loadStrategy ?? 'REPLACE',
      apiConfig: step.apiConfig ?? undefined,
      aiConfig: step.aiConfig as AiClassifyConfig | undefined,
      pythonConfig: step.pythonConfig ?? undefined,
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
    baseUpdatedAt: detail.updatedAt,
  };

  return applyAutoLayout(newState);
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
      const removedIndex = state.steps.findIndex((s) => s.tempId === tempId);
      const removedStep = state.steps.find((s) => s.tempId === tempId);
      const removedDeps = removedStep?.dependsOnTempIds ?? [];
      const removedNum = removedIndex + 1;

      const remainingSteps = state.steps
        .filter((s) => s.tempId !== tempId)
        .map((s) => {
          // Fix dependsOnTempIds
          let updated = s;
          if (s.dependsOnTempIds.includes(tempId)) {
            const newDeps = s.dependsOnTempIds
              .filter((id) => id !== tempId)
              .concat(removedDeps.filter((id) => !s.dependsOnTempIds.includes(id)));
            updated = { ...updated, dependsOnTempIds: newDeps };
          }
          // Renumber {{#N}} in scriptContent
          if (updated.scriptContent) {
            let content = updated.scriptContent;
            // Replace deleted step reference with comment
            content = content.replace(
              new RegExp(`\\{\\{#${removedNum}\\}\\}`, 'g'),
              `/* 삭제된 스텝 #${removedNum} */`,
            );
            // Decrement references to steps after the removed one
            content = content.replace(/\{\{#(\d+)\}\}/g, (_match, num) => {
              const n = parseInt(num, 10);
              if (n > removedNum) return `{{#${n - 1}}}`;
              return `{{#${n}}}`;
            });
            updated = { ...updated, scriptContent: content };
          }
          return updated;
        });

      return {
        ...state,
        steps: remainingSteps,
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

    case 'LOAD_FROM_API':
      return buildStateFromDetail(action.payload);

    case 'MARK_SAVED':
      return {
        ...state,
        isDirty: false,
        validationErrors: [],
        pipelineId: action.payload?.pipelineId ?? state.pipelineId,
        // 응답에 버전이 없으면(update 가 아닌 create 등) 기존 값을 둔다 — create 는 상세 이동 후 재조회가 채운다
        baseUpdatedAt:
          action.payload && 'updatedAt' in action.payload
            ? (action.payload.updatedAt ?? null)
            : state.baseUpdatedAt,
      };

    case 'RESTORE_SNAPSHOT':
      // 편집 취소 — 스냅샷을 그대로 복원하고 선택·검증 오류는 비운다(기존 취소 동작과 동일)
      return { ...action.payload, selectedStepId: null, isDirty: false, validationErrors: [] };

    case 'SYNC_FROM_SERVER': {
      // 미저장 변경이 있으면 서버 재조회가 사용자의 편집을 덮지 않는다.
      // 판단을 리듀서에서 하는 이유: 렌더 시점의 isDirty 로 판단하면 그 사이 입력된 변경을 덮을 수 있다.
      if (state.isDirty) return state;
      // 재조회로 tempId 가 새로 만들어지므로 선택된 스텝은 이름으로 다시 찾아 패널이 닫히지 않게 한다
      const selectedName = state.steps.find((s) => s.tempId === state.selectedStepId)?.name;
      const selectedStepId =
        selectedName !== undefined
          ? (action.payload.steps.find((s) => s.name === selectedName)?.tempId ?? null)
          : null;
      return { ...action.payload, selectedStepId, isDirty: false, validationErrors: [] };
    }

    case 'SET_VALIDATION_ERRORS':
      return { ...state, validationErrors: action.payload };

    default:
      return state;
  }
}
