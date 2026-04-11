/**
 * pipelineEditorReducer 단위 테스트 — 파이프라인 에디터 상태 전이.
 * dagre-layout는 실제 모듈을 사용하지만 AUTO_LAYOUT을 트리거하지 않는 액션 위주로 검증.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createDefaultStep,
  type EditorAction,
  initialState,
  pipelineEditorReducer,
  type PipelineEditorState,
} from './pipelineEditorReducer';

// dagre-layout은 실제 구현을 사용하면 느리거나 사이드이펙트가 있을 수 있어 경량 목
vi.mock('../utils/dagre-layout', () => ({
  getLayoutedElements: (nodes: { id: string; position: { x: number; y: number } }[]) => ({
    nodes: nodes.map((n, i) => ({ ...n, position: { x: i * 100, y: 0 } })),
  }),
}));

function dispatch(state: PipelineEditorState, action: EditorAction): PipelineEditorState {
  return pipelineEditorReducer(state, action);
}

describe('pipelineEditorReducer', () => {
  let state: PipelineEditorState;

  beforeEach(() => {
    state = { ...initialState };
  });

  describe('SET_META', () => {
    it('name/description/isActive 업데이트 + isDirty true', () => {
      const next = dispatch(state, {
        type: 'SET_META',
        payload: { name: 'My pipeline', description: 'desc', isActive: false },
      });
      expect(next.name).toBe('My pipeline');
      expect(next.description).toBe('desc');
      expect(next.isActive).toBe(false);
      expect(next.isDirty).toBe(true);
      expect(next.validationErrors).toEqual([]);
    });
  });

  describe('ADD_STEP', () => {
    it('새 스텝을 추가하고 selectedStepId 설정', () => {
      const next = dispatch(state, {
        type: 'ADD_STEP',
        payload: { position: { x: 10, y: 20 } },
      });
      expect(next.steps).toHaveLength(1);
      expect(next.steps[0].position).toEqual({ x: 10, y: 20 });
      expect(next.selectedStepId).toBe(next.steps[0].tempId);
      expect(next.isDirty).toBe(true);
    });
  });

  describe('REMOVE_STEP', () => {
    it('스텝 제거 + dependsOnTempIds에서 해당 ID 삭제', () => {
      const s1 = createDefaultStep({ x: 0, y: 0 });
      const s2 = createDefaultStep({ x: 100, y: 0 });
      s2.dependsOnTempIds = [s1.tempId];
      state = { ...state, steps: [s1, s2], selectedStepId: s1.tempId };

      const next = dispatch(state, { type: 'REMOVE_STEP', payload: { tempId: s1.tempId } });
      expect(next.steps).toHaveLength(1);
      expect(next.steps[0].tempId).toBe(s2.tempId);
      expect(next.steps[0].dependsOnTempIds).toEqual([]);
      expect(next.selectedStepId).toBeNull();
      expect(next.isDirty).toBe(true);
    });

    it('제거 시 {{#N}} 참조를 주석으로 치환하고 후속 번호 감소', () => {
      const s1 = createDefaultStep({ x: 0, y: 0 });
      const s2 = createDefaultStep({ x: 1, y: 0 });
      const s3 = createDefaultStep({ x: 2, y: 0 });
      s3.scriptContent = 'SELECT * FROM {{#1}} JOIN {{#3}}';
      state = { ...state, steps: [s1, s2, s3] };

      const next = dispatch(state, { type: 'REMOVE_STEP', payload: { tempId: s1.tempId } });
      const updatedS3 = next.steps.find((s) => s.tempId === s3.tempId)!;
      expect(updatedS3.scriptContent).toContain('/* 삭제된 스텝 #1 */');
      expect(updatedS3.scriptContent).toContain('{{#2}}');
    });

    it('삭제된 스텝의 의존성을 후속 스텝에 전파', () => {
      const a = createDefaultStep({ x: 0, y: 0 });
      const b = createDefaultStep({ x: 1, y: 0 });
      b.dependsOnTempIds = [a.tempId];
      const c = createDefaultStep({ x: 2, y: 0 });
      c.dependsOnTempIds = [b.tempId];
      state = { ...state, steps: [a, b, c] };

      const next = dispatch(state, { type: 'REMOVE_STEP', payload: { tempId: b.tempId } });
      const updatedC = next.steps.find((s) => s.tempId === c.tempId)!;
      expect(updatedC.dependsOnTempIds).toContain(a.tempId);
      expect(updatedC.dependsOnTempIds).not.toContain(b.tempId);
    });
  });

  describe('UPDATE_STEP', () => {
    it('대상 스텝만 변경 + 해당 validationError 제거', () => {
      const s1 = createDefaultStep({ x: 0, y: 0 });
      const s2 = createDefaultStep({ x: 1, y: 0 });
      state = {
        ...state,
        steps: [s1, s2],
        validationErrors: [
          { stepTempId: s1.tempId, field: 'name', message: 'required' },
          { stepTempId: s2.tempId, field: 'name', message: 'required' },
        ],
      };
      const next = dispatch(state, {
        type: 'UPDATE_STEP',
        payload: { tempId: s1.tempId, changes: { name: 'Renamed' } },
      });
      expect(next.steps.find((s) => s.tempId === s1.tempId)!.name).toBe('Renamed');
      expect(next.steps.find((s) => s.tempId === s2.tempId)!.name).toBe('');
      expect(next.validationErrors).toHaveLength(1);
      expect(next.validationErrors[0].stepTempId).toBe(s2.tempId);
    });
  });

  describe('SELECT_STEP', () => {
    it('selectedStepId만 변경', () => {
      const next = dispatch(state, { type: 'SELECT_STEP', payload: { tempId: 'xyz' } });
      expect(next.selectedStepId).toBe('xyz');
      expect(next.isDirty).toBe(false);
    });
  });

  describe('ADD_EDGE / REMOVE_EDGE', () => {
    it('엣지 추가는 dependsOnTempIds에 source 추가', () => {
      const a = createDefaultStep({ x: 0, y: 0 });
      const b = createDefaultStep({ x: 1, y: 0 });
      state = { ...state, steps: [a, b] };
      const next = dispatch(state, {
        type: 'ADD_EDGE',
        payload: { sourceTempId: a.tempId, targetTempId: b.tempId },
      });
      expect(next.steps.find((s) => s.tempId === b.tempId)!.dependsOnTempIds).toContain(
        a.tempId,
      );
      expect(next.isDirty).toBe(true);
    });

    it('사이클을 발생시키는 엣지는 거부되어 state 변경 없음', () => {
      const a = createDefaultStep({ x: 0, y: 0 });
      const b = createDefaultStep({ x: 1, y: 0 });
      b.dependsOnTempIds = [a.tempId];
      state = { ...state, steps: [a, b] };
      const next = dispatch(state, {
        type: 'ADD_EDGE',
        payload: { sourceTempId: b.tempId, targetTempId: a.tempId },
      });
      expect(next).toBe(state);
    });

    it('중복 엣지는 추가되지 않음', () => {
      const a = createDefaultStep({ x: 0, y: 0 });
      const b = createDefaultStep({ x: 1, y: 0 });
      b.dependsOnTempIds = [a.tempId];
      state = { ...state, steps: [a, b] };
      const next = dispatch(state, {
        type: 'ADD_EDGE',
        payload: { sourceTempId: a.tempId, targetTempId: b.tempId },
      });
      expect(next.steps.find((s) => s.tempId === b.tempId)!.dependsOnTempIds).toHaveLength(1);
    });

    it('엣지 제거', () => {
      const a = createDefaultStep({ x: 0, y: 0 });
      const b = createDefaultStep({ x: 1, y: 0 });
      b.dependsOnTempIds = [a.tempId];
      state = { ...state, steps: [a, b] };
      const next = dispatch(state, {
        type: 'REMOVE_EDGE',
        payload: { sourceTempId: a.tempId, targetTempId: b.tempId },
      });
      expect(next.steps.find((s) => s.tempId === b.tempId)!.dependsOnTempIds).toEqual([]);
    });
  });

  describe('ADD_STEP_AFTER', () => {
    it('기존 스텝 뒤에 새 스텝을 체인으로 추가', () => {
      const a = createDefaultStep({ x: 100, y: 100 });
      state = { ...state, steps: [a] };
      const next = dispatch(state, {
        type: 'ADD_STEP_AFTER',
        payload: { sourceTempId: a.tempId },
      });
      expect(next.steps).toHaveLength(2);
      const newStep = next.steps[1];
      expect(newStep.dependsOnTempIds).toEqual([a.tempId]);
      expect(newStep.position.x).toBe(420);
      expect(next.selectedStepId).toBe(newStep.tempId);
    });

    it('존재하지 않는 source면 state 변경 없음', () => {
      const next = dispatch(state, {
        type: 'ADD_STEP_AFTER',
        payload: { sourceTempId: 'missing' },
      });
      expect(next).toBe(state);
    });
  });

  describe('UPDATE_NODE_POSITION', () => {
    it('노드 위치만 변경, isDirty 건드리지 않음', () => {
      const a = createDefaultStep({ x: 0, y: 0 });
      state = { ...state, steps: [a] };
      const next = dispatch(state, {
        type: 'UPDATE_NODE_POSITION',
        payload: { tempId: a.tempId, position: { x: 99, y: 88 } },
      });
      expect(next.steps[0].position).toEqual({ x: 99, y: 88 });
      expect(next.isDirty).toBe(false);
    });
  });

  describe('MARK_SAVED', () => {
    it('isDirty false로, pipelineId 저장', () => {
      state = { ...state, isDirty: true };
      const next = dispatch(state, { type: 'MARK_SAVED', payload: { pipelineId: 42 } });
      expect(next.isDirty).toBe(false);
      expect(next.pipelineId).toBe(42);
    });

    it('payload 없으면 기존 pipelineId 유지', () => {
      state = { ...state, pipelineId: 7, isDirty: true };
      const next = dispatch(state, { type: 'MARK_SAVED' });
      expect(next.pipelineId).toBe(7);
      expect(next.isDirty).toBe(false);
    });
  });

  describe('SET_VALIDATION_ERRORS', () => {
    it('validation errors 교체', () => {
      const errs = [{ stepTempId: 't', field: 'name', message: 'bad' }];
      const next = dispatch(state, { type: 'SET_VALIDATION_ERRORS', payload: errs });
      expect(next.validationErrors).toEqual(errs);
    });
  });

  describe('default case', () => {
    it('알 수 없는 액션은 state 그대로', () => {
      // @ts-expect-error - intentional unknown action
      const next = pipelineEditorReducer(state, { type: 'UNKNOWN' });
      expect(next).toBe(state);
    });
  });
});
