/**
 * pipelineEditorReducer 단위 테스트 — 파이프라인 에디터 상태 전이.
 * dagre-layout는 실제 모듈을 사용하지만 AUTO_LAYOUT을 트리거하지 않는 액션 위주로 검증.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { sectionOf } from './errorFieldOrder';
import {
  createDefaultStep,
  type EditorAction,
  initialState,
  pipelineEditorReducer,
  type PipelineEditorState,
} from './pipelineEditorReducer';
import {
  AI_OUTPUT_COLUMNS_FIELD,
  AI_PROMPT_FIELD,
  aiOutputColumnNameField,
  API_CUSTOM_URL_FIELD,
  API_PAGE_SIZE_FIELD,
  API_PATH_FIELD,
  KV_ROW_KEY,
  kvRowField,
  MAPPING_ROW_KEY,
  MAPPING_TARGET_KEY,
  mappingRowField,
  mappingSourceField,
  mappingTargetField,
  pythonOutputColumnNameField,
} from './stepColumnValidation';

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

    // loadStrategy는 이미 string 타입이라 리듀서 수정 없이 MERGE 값도 그대로 반영돼야 한다 (위 케이스 복제)
    it('loadStrategy를 MERGE로 변경하면 반영된다', () => {
      const s1 = createDefaultStep({ x: 0, y: 0 });
      state = { ...state, steps: [s1] };
      const next = dispatch(state, {
        type: 'UPDATE_STEP',
        payload: { tempId: s1.tempId, changes: { loadStrategy: 'MERGE' } },
      });
      expect(next.steps.find((s) => s.tempId === s1.tempId)!.loadStrategy).toBe('MERGE');
    });
  });

  describe('SELECT_STEP', () => {
    it('selectedStepId만 변경', () => {
      const next = dispatch(state, { type: 'SELECT_STEP', payload: { tempId: 'xyz' } });
      expect(next.selectedStepId).toBe('xyz');
      expect(next.isDirty).toBe(false);
    });
  });

  // 저장 검증 실패 후 오류 스텝 선택·첫 오류 필드 포커스 요청(WD-560)
  describe('REQUEST_ERROR_FOCUS / CLEAR_FOCUS_REQUEST', () => {
    const a = { ...createDefaultStep({ x: 0, y: 0 }), tempId: 'a' };
    const b = { ...createDefaultStep({ x: 0, y: 0 }), tempId: 'b' };

    it('선택 스텝에 오류가 없으면 스텝 순서상 첫 오류 스텝을 고르고 이름→스크립트→출력 순서의 첫 필드를 요청한다', () => {
      const next = dispatch(
        {
          ...state,
          steps: [a, b],
          selectedStepId: null,
          // 목록 순서가 b 먼저여도 스텝 순서(a)로 고른다 — 중복 이름 오류는 목록 끝에 붙는다
          validationErrors: [
            { stepTempId: 'b', field: 'name', message: 'x' },
            { stepTempId: 'a', field: 'outputDatasetId', message: 'x' },
            { stepTempId: 'a', field: 'scriptContent', message: 'x' },
          ],
        },
        { type: 'REQUEST_ERROR_FOCUS' },
      );
      expect(next.selectedStepId).toBe('a');
      expect(next.focusRequest).toEqual({ stepTempId: 'a', field: 'scriptContent' });
    });

    it('비동기 검증 중 사용자가 고른 스텝에 오류가 있으면 그 선택을 유지한다', () => {
      const next = dispatch(
        {
          ...state,
          steps: [a, b],
          selectedStepId: 'b',
          validationErrors: [
            { stepTempId: 'a', field: 'name', message: 'x' },
            { stepTempId: 'b', field: 'name', message: 'x' },
          ],
        },
        { type: 'REQUEST_ERROR_FOCUS' },
      );
      expect(next.selectedStepId).toBe('b');
      expect(next.focusRequest).toEqual({ stepTempId: 'b', field: 'name' });
    });

    it('포커스할 칸이 없는 오류(스키마의 기타 경로)는 스텝만 선택하고 요청은 비운다', () => {
      const next = dispatch(
        {
          ...state,
          steps: [a],
          validationErrors: [{ stepTempId: 'a', field: 'loadStrategy', message: 'x' }],
        },
        { type: 'REQUEST_ERROR_FOCUS' },
      );
      expect(next.selectedStepId).toBe('a');
      expect(next.focusRequest).toBeNull();
    });

    // 하위 설정 칸 오류도 화면 순서의 첫 칸으로 포커스를 요청한다(WD-633)
    describe('하위 설정 칸 오류(WD-633)', () => {
      /** 스텝 a 에 주어진 오류 키들이 있는 상태 */
      const withErrors = (fields: string[]): PipelineEditorState => ({
        ...state,
        steps: [a],
        validationErrors: fields.map((field) => ({ stepTempId: 'a', field, message: 'x' })),
      });
      const focusFieldFor = (fields: string[]) =>
        dispatch(withErrors(fields), { type: 'REQUEST_ERROR_FOCUS' }).focusRequest?.field;

      it('API URL 만 비면 URL 칸을 요청한다', () => {
        expect(focusFieldFor([API_CUSTOM_URL_FIELD])).toBe(API_CUSTOM_URL_FIELD);
        expect(focusFieldFor([API_PATH_FIELD])).toBe(API_PATH_FIELD);
      });

      it('API 호출 칸은 URL → 헤더 → 쿼리 파라미터 → 필드 매핑 → 페이지 크기 순서로 고른다', () => {
        expect(
          focusFieldFor([
            API_PAGE_SIZE_FIELD,
            mappingTargetField(0),
            kvRowField('queryParams', 1),
            kvRowField('headers', 2),
            API_CUSTOM_URL_FIELD,
          ]),
        ).toBe(API_CUSTOM_URL_FIELD);
        expect(
          focusFieldFor([API_PAGE_SIZE_FIELD, kvRowField('queryParams', 0), kvRowField('headers', 3)]),
        ).toBe(kvRowField('headers', 3));
        expect(focusFieldFor([API_PAGE_SIZE_FIELD, kvRowField('queryParams', 4)])).toBe(
          kvRowField('queryParams', 4),
        );
        expect(focusFieldFor([API_PAGE_SIZE_FIELD, mappingRowField(1)])).toBe(
          mappingRowField(1),
        );
      });

      it('필드 매핑은 앞 행부터, 같은 행이면 응답 필드가 대상 컬럼보다 먼저다', () => {
        expect(
          focusFieldFor([mappingSourceField(2), mappingTargetField(10)]),
        ).toBe(mappingSourceField(2));
        // 행 번호는 숫자로 비교한다 — 문자열 비교면 10 이 2 보다 앞선다
        expect(
          focusFieldFor([mappingSourceField(10), mappingTargetField(2)]),
        ).toBe(mappingTargetField(2));
      });

      it('이름 오류는 하위 설정 칸보다 먼저고, 하위 설정 칸은 출력 데이터셋보다 먼저다', () => {
        expect(focusFieldFor([API_PAGE_SIZE_FIELD, 'name'])).toBe('name');
        expect(focusFieldFor(['outputDatasetId', API_PAGE_SIZE_FIELD])).toBe(
          API_PAGE_SIZE_FIELD,
        );
      });

      it('AI 분류는 프롬프트 → 출력 컬럼(0개) → 출력 컬럼 이름 순서다', () => {
        expect(focusFieldFor([AI_OUTPUT_COLUMNS_FIELD, AI_PROMPT_FIELD])).toBe(AI_PROMPT_FIELD);
        expect(focusFieldFor(['outputDatasetId', AI_OUTPUT_COLUMNS_FIELD])).toBe(AI_OUTPUT_COLUMNS_FIELD);
        expect(focusFieldFor([aiOutputColumnNameField(3), aiOutputColumnNameField(1)])).toBe(
          aiOutputColumnNameField(1),
        );
      });

      it('Python 출력 컬럼은 출력 데이터셋 아래에 있어 그보다 뒤다', () => {
        expect(focusFieldFor([pythonOutputColumnNameField(0), 'scriptContent'])).toBe('scriptContent');
        expect(focusFieldFor([pythonOutputColumnNameField(0), 'loadStrategy'])).toBe(
          pythonOutputColumnNameField(0),
        );
      });

      it('행 키는 만든 키를 그대로 다시 읽고(행 번호), 다른 형식은 읽지 않는다', () => {
        expect(MAPPING_TARGET_KEY.parse(mappingTargetField(12))).toBe(12);
        expect(MAPPING_ROW_KEY.parse(mappingTargetField(12))).toBeNull();
        expect(KV_ROW_KEY.headers.parse(kvRowField('queryParams', 1))).toBeNull();
      });

      it('sectionOf 는 API 호출 오류 키가 든 접는 절을 돌려준다', () => {
        expect(sectionOf(kvRowField('headers', 0))).toBe('headers');
        expect(sectionOf(kvRowField('queryParams', 2))).toBe('queryParams');
        expect(sectionOf(mappingSourceField(1))).toBe('fieldMappings');
        expect(sectionOf(API_PAGE_SIZE_FIELD)).toBe('pagination');
        expect(sectionOf(API_CUSTOM_URL_FIELD)).toBeNull();
        expect(sectionOf('name')).toBeNull();
      });

      it('하위 설정 칸 요청도 그 오류가 고쳐지면 지운다', () => {
        const pending = {
          ...withErrors([API_CUSTOM_URL_FIELD]),
          selectedStepId: 'a',
          focusRequest: { stepTempId: 'a', field: API_CUSTOM_URL_FIELD },
        };
        const next = dispatch(pending, {
          type: 'UPDATE_STEP',
          payload: { tempId: 'a', changes: { apiConfig: { customUrl: 'https://x' } } },
        });
        expect(next.focusRequest).toBeNull();
      });
    });

    it('CLEAR_FOCUS_REQUEST·SELECT_STEP 은 요청을 지운다', () => {
      const pending = { ...state, focusRequest: { stepTempId: 'a', field: 'name' } };
      expect(dispatch(pending, { type: 'CLEAR_FOCUS_REQUEST' }).focusRequest).toBeNull();
      expect(dispatch(pending, { type: 'SELECT_STEP', payload: { tempId: 'b' } }).focusRequest).toBeNull();
    });

    // 오류를 지우는 액션이 처리되지 않은 요청을 남기면 나중에 포커스가 뜬금없이 옮겨진다(WD-560)
    describe('요청 대상 오류가 사라지면 요청도 지운다', () => {
      const pending = {
        ...state,
        steps: [a, b],
        selectedStepId: 'a',
        validationErrors: [
          { stepTempId: 'a', field: 'name', message: 'x' },
          { stepTempId: 'b', field: 'name', message: 'x' },
        ],
        focusRequest: { stepTempId: 'a', field: 'name' },
      };

      it('UPDATE_STEP 으로 대상 스텝 오류가 지워지면 요청을 지운다', () => {
        const next = dispatch(pending, { type: 'UPDATE_STEP', payload: { tempId: 'a', changes: { name: 'n' } } });
        expect(next.focusRequest).toBeNull();
      });

      it('다른 스텝의 UPDATE_STEP 은 대상 오류가 남아 있으므로 요청을 유지한다', () => {
        const next = dispatch(pending, { type: 'UPDATE_STEP', payload: { tempId: 'b', changes: { name: 'n' } } });
        expect(next.focusRequest).toEqual({ stepTempId: 'a', field: 'name' });
      });

      it('REMOVE_STEP·SET_META 처럼 오류를 모두 지우는 액션도 요청을 지운다', () => {
        expect(dispatch(pending, { type: 'REMOVE_STEP', payload: { tempId: 'b' } }).focusRequest).toBeNull();
        expect(dispatch(pending, { type: 'SET_META', payload: { name: 'p' } }).focusRequest).toBeNull();
      });
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

    // WD-353 — 저장 응답의 새 버전이 다음 저장의 기준이 된다(재조회를 기다리면 자기 저장과 충돌)
    it('updatedAt 이 오면 기준 버전을 그 값으로 바꾸고, 없으면 기존 기준을 둔다', () => {
      state = { ...state, pipelineId: 7, isDirty: true, baseUpdatedAt: 'v1' };
      expect(dispatch(state, { type: 'MARK_SAVED', payload: { updatedAt: 'v2' } }).baseUpdatedAt).toBe('v2');
      expect(dispatch(state, { type: 'MARK_SAVED', payload: { updatedAt: null } }).baseUpdatedAt).toBeNull();
      expect(dispatch(state, { type: 'MARK_SAVED', payload: { pipelineId: 7 } }).baseUpdatedAt).toBe('v1');
      expect(dispatch(state, { type: 'MARK_SAVED' }).baseUpdatedAt).toBe('v1');
    });
  });

  describe('SET_VALIDATION_ERRORS', () => {
    it('validation errors 교체', () => {
      const errs = [{ stepTempId: 't', field: 'name', message: 'bad' }];
      const next = dispatch(state, { type: 'SET_VALIDATION_ERRORS', payload: errs });
      expect(next.validationErrors).toEqual(errs);
    });
  });

  describe('INSERT_STEP_BETWEEN', () => {
    it('두 스텝 사이에 새 스텝을 삽입하고 의존성을 재연결한다', () => {
      const a = createDefaultStep({ x: 0, y: 0 });
      const b = createDefaultStep({ x: 400, y: 0 });
      b.dependsOnTempIds = [a.tempId];
      state = { ...state, steps: [a, b] };

      const next = dispatch(state, {
        type: 'INSERT_STEP_BETWEEN',
        payload: { sourceTempId: a.tempId, targetTempId: b.tempId },
      });

      expect(next.steps).toHaveLength(3);
      const inserted = next.steps.find(
        (s) => s.tempId !== a.tempId && s.tempId !== b.tempId,
      )!;
      expect(inserted).toBeDefined();
      expect(inserted.dependsOnTempIds).toContain(a.tempId);

      const updatedB = next.steps.find((s) => s.tempId === b.tempId)!;
      expect(updatedB.dependsOnTempIds).toContain(inserted.tempId);
      expect(updatedB.dependsOnTempIds).not.toContain(a.tempId);

      expect(next.selectedStepId).toBe(inserted.tempId);
      expect(next.isDirty).toBe(true);
    });

    it('source 또는 target이 존재하지 않으면 state 변경 없음', () => {
      const a = createDefaultStep({ x: 0, y: 0 });
      state = { ...state, steps: [a] };

      const next = dispatch(state, {
        type: 'INSERT_STEP_BETWEEN',
        payload: { sourceTempId: a.tempId, targetTempId: 'missing' },
      });
      expect(next).toBe(state);

      const next2 = dispatch(state, {
        type: 'INSERT_STEP_BETWEEN',
        payload: { sourceTempId: 'missing', targetTempId: a.tempId },
      });
      expect(next2).toBe(state);
    });
  });

  describe('AUTO_LAYOUT', () => {
    it('스텝이 없으면 state 변경 없음', () => {
      const next = dispatch(state, { type: 'AUTO_LAYOUT' });
      expect(next).toBe(state);
    });

    it('스텝이 있으면 레이아웃이 적용된다', () => {
      const a = createDefaultStep({ x: 0, y: 0 });
      const b = createDefaultStep({ x: 0, y: 0 });
      b.dependsOnTempIds = [a.tempId];
      state = { ...state, steps: [a, b] };

      const next = dispatch(state, { type: 'AUTO_LAYOUT' });
      // dagre mock은 index * 100 으로 x 위치를 설정한다
      expect(next.steps[0].position.x).toBe(0);
      expect(next.steps[1].position.x).toBe(100);
    });
  });

  describe('LOAD_FROM_API', () => {
    it('API 응답에서 상태를 로드하고 의존성 매핑이 올바르다', () => {
      const payload = {
        id: 5,
        name: '로드 테스트 파이프라인',
        description: '설명',
        isActive: true,
        steps: [
          {
            name: 'step-a',
            description: 'A 설명',
            scriptType: 'SQL' as const,
            scriptContent: 'SELECT 1',
            outputDatasetId: null,
            inputDatasetIds: [],
            dependsOnStepNames: [],
            loadStrategy: 'REPLACE',
            apiConfig: undefined,
            aiConfig: undefined,
            pythonConfig: undefined,
            apiConnectionId: null,
          },
          {
            name: 'step-b',
            description: 'B 설명',
            scriptType: 'PYTHON' as const,
            scriptContent: 'print(1)',
            outputDatasetId: 10,
            inputDatasetIds: [1],
            dependsOnStepNames: ['step-a'],
            loadStrategy: 'APPEND',
            apiConfig: undefined,
            aiConfig: undefined,
            pythonConfig: { requirements: 'pandas' },
            apiConnectionId: null,
          },
        ],
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
        createdBy: '테스트 사용자',
        updatedBy: '테스트 사용자',
      };

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const next = dispatch(state, { type: 'LOAD_FROM_API', payload: payload as any });

      expect(next.name).toBe('로드 테스트 파이프라인');
      expect(next.pipelineId).toBe(5);
      expect(next.isDirty).toBe(false);
      expect(next.steps).toHaveLength(2);

      const stepA = next.steps.find((s) => s.name === 'step-a')!;
      const stepB = next.steps.find((s) => s.name === 'step-b')!;
      expect(stepA).toBeDefined();
      expect(stepB).toBeDefined();
      expect(stepB.dependsOnTempIds).toContain(stepA.tempId);
      expect(stepB.pythonConfig).toEqual({ requirements: 'pandas' });
    });

    it('API_CALL 스텝 타입도 올바르게 로드된다', () => {
      const payload = {
        id: 6,
        name: 'API 파이프라인',
        description: '',
        isActive: false,
        steps: [
          {
            name: 'api-step',
            description: '',
            scriptType: 'API_CALL' as const,
            scriptContent: '',
            outputDatasetId: null,
            inputDatasetIds: [],
            dependsOnStepNames: [],
            loadStrategy: 'REPLACE',
            apiConfig: { url: 'https://example.com', method: 'GET' },
            aiConfig: undefined,
            pythonConfig: undefined,
            apiConnectionId: 3,
          },
        ],
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
        createdBy: '테스트 사용자',
        updatedBy: '테스트 사용자',
      };

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const next = dispatch(state, { type: 'LOAD_FROM_API', payload: payload as any });
      expect(next.isActive).toBe(false);
      expect(next.steps[0].scriptType).toBe('API_CALL');
      expect(next.steps[0].apiConfig).toEqual({ url: 'https://example.com', method: 'GET' });
      expect(next.steps[0].apiConnectionId).toBe(3);
    });

    it('자동 생성 출력(outputAutoGenerated)은 편집 상태에서 null 로 두고 임시 데이터셋 id 는 표시용으로만 보관한다 (WD-222)', () => {
      const payload = {
        id: 7,
        name: '자동 생성 파이프라인',
        description: '',
        isActive: true,
        steps: [
          {
            id: 11,
            name: 'auto-step',
            description: '',
            scriptType: 'SQL' as const,
            scriptContent: 'SELECT 1 AS v',
            // 서버는 자동 생성 스텝에도 임시 데이터셋 id 를 채워 준다(화면 표시용 해석값)
            outputDatasetId: 99,
            outputAutoGenerated: true,
            inputDatasetIds: [],
            dependsOnStepNames: [],
            loadStrategy: 'REPLACE',
            apiConnectionId: null,
          },
          {
            id: 12,
            name: 'explicit-step',
            description: '',
            scriptType: 'SQL' as const,
            scriptContent: 'SELECT 2 AS v',
            outputDatasetId: 5,
            outputAutoGenerated: false,
            inputDatasetIds: [],
            dependsOnStepNames: [],
            loadStrategy: 'REPLACE',
            apiConnectionId: null,
          },
        ],
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
        createdBy: '테스트 사용자',
        updatedBy: '테스트 사용자',
      };

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const next = dispatch(state, { type: 'LOAD_FROM_API', payload: payload as any });
      expect(next.steps[0].outputDatasetId).toBeNull();
      expect(next.steps[0].autoOutputDatasetId).toBe(99);
      expect(next.steps[1].outputDatasetId).toBe(5);
      expect(next.steps[1].autoOutputDatasetId).toBeNull();
    });
  });

  describe('default case', () => {
    it('알 수 없는 액션은 state 그대로', () => {
      // @ts-expect-error - intentional unknown action
      const next = pipelineEditorReducer(state, { type: 'UNKNOWN' });
      expect(next).toBe(state);
    });
  });
  // #742 — 서버 재조회 반영(SYNC_FROM_SERVER)과 편집 취소 복원(RESTORE_SNAPSHOT)
  describe('SYNC_FROM_SERVER / RESTORE_SNAPSHOT (#742)', () => {
    const snapshotWith = (desc: string): PipelineEditorState => ({
      ...initialState,
      pipelineId: 1,
      steps: [{ ...createDefaultStep({ x: 0, y: 0 }), tempId: `new-${desc}`, name: 'sql1', description: desc }],
    });

    it('미저장 변경이 있으면 서버 재조회를 무시한다 — 편집 중 입력을 덮지 않는다', () => {
      const dirty: PipelineEditorState = { ...snapshotWith('my-edit'), isDirty: true };
      const next = dispatch(dirty, { type: 'SYNC_FROM_SERVER', payload: snapshotWith('server') });
      expect(next).toBe(dirty);
    });

    // WD-353 — 화면을 바꾸지 않은 재조회가 기준 버전만 최신으로 넘기면 다른 탭의 저장을 조용히 덮어쓴다
    it('미저장 변경이 있으면 재조회의 새 버전도 받지 않는다 — 기준은 편집을 시작한 버전', () => {
      const dirty: PipelineEditorState = { ...snapshotWith('my-edit'), isDirty: true, baseUpdatedAt: 'v1' };
      const next = dispatch(dirty, {
        type: 'SYNC_FROM_SERVER',
        payload: { ...snapshotWith('server'), baseUpdatedAt: 'v2' },
      });
      expect(next.baseUpdatedAt).toBe('v1');
    });

    it('미저장 변경이 없으면 재조회의 새 버전을 기준으로 삼는다', () => {
      const clean: PipelineEditorState = { ...snapshotWith('old'), baseUpdatedAt: 'v1' };
      const next = dispatch(clean, {
        type: 'SYNC_FROM_SERVER',
        payload: { ...snapshotWith('server'), baseUpdatedAt: 'v2' },
      });
      expect(next.baseUpdatedAt).toBe('v2');
    });

    it('미저장 변경이 없으면 서버 상태를 반영하고, 선택된 스텝은 이름으로 다시 찾아 유지한다', () => {
      const clean: PipelineEditorState = {
        ...snapshotWith('old'),
        steps: [{ ...snapshotWith('old').steps[0], tempId: 'old-id' }],
        selectedStepId: 'old-id',
      };
      const next = dispatch(clean, { type: 'SYNC_FROM_SERVER', payload: snapshotWith('server') });
      expect(next.steps[0].description).toBe('server');
      expect(next.selectedStepId).toBe('new-server');
      expect(next.isDirty).toBe(false);
    });

    it('RESTORE_SNAPSHOT 은 미저장 변경이 있어도 스냅샷으로 되돌리고 선택을 비운다', () => {
      const dirty: PipelineEditorState = { ...snapshotWith('my-edit'), isDirty: true, selectedStepId: 'new-my-edit' };
      const next = dispatch(dirty, { type: 'RESTORE_SNAPSHOT', payload: snapshotWith('saved') });
      expect(next.steps[0].description).toBe('saved');
      expect(next.isDirty).toBe(false);
      expect(next.selectedStepId).toBeNull();
    });
  });
});
