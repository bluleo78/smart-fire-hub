import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';

import { aiClassifyCredentialApi } from '../api/settings';
import type { AgentType } from '../lib/ai-credential';
import { extractApiError } from '../lib/api-error';
import type { AiClassifyCredentialResponse } from '../types/settings';
import type { AiCredentialEndpoints, UseAiCredentialFormResult } from './useAiCredentialForm';
import { useAiCredentialForm } from './useAiCredentialForm';

/** 서버 `AiCredentialService.MSG_CLASSIFY_MODEL_REQUIRED` 와 같은 문구 — 화면이 먼저 막는다. */
export const CLASSIFY_MODEL_REQUIRED = '분류 모델을 선택하세요';

export interface UseAiClassifyFormResult {
  /** 분류 자격증명 폼(채팅과 같은 상태 기계). `setAgentType` 은 모델도 함께 비우도록 감싼 것이다. */
  cred: UseAiCredentialFormResult;
  /** 탭이 한 번이라도 열렸는가 — 열리기 전에는 조회하지 않는다. */
  activated: boolean;
  activate: () => void;
  /** 폼을 보여줄 상태인가 — 서버에 설정이 있거나, 미설정에서 "분류 전용 설정하기"를 눌렀다. */
  editing: boolean;
  startEditing: () => void;
  /**
   * 미설정 편집을 접는다(로컬 입력 폐기). 서버 상태는 바뀌지 않는다.
   *
   * <b>저장 후 재조회가 실패한 상태(`cred.staleNotice !== null`)에서는 아무것도 하지 않는다</b> —
   * 그때는 분류 전용 설정이 실제로 저장돼 쓰이는 중인데 `cred.configured` 만 아직 false 라, 접으면
   * "AI 에이전트 설정을 사용 중입니다." 배너가 떠서 거짓말이 된다(`save()` 주석과 같은 이유).
   * 되돌릴 로컬 입력도 없다(방금 쓴 값이 서버 값이다). 화면도 이때는 "취소"를 내주지 않는다.
   */
  cancelEditing: () => void;
  model: string;
  setModel: (value: string) => void;
  modelError: string | null;
  hasUnsavedInput: boolean;
  isSaving: boolean;
  isClearing: boolean;
  save: () => Promise<boolean>;
  /** "분류 전용 설정 해제" — DELETE 후 서버 상태로 다시 시드한다. */
  clear: () => Promise<void>;
}

/**
 * AI 분류 전용 공급자(#707) 탭 상태. **페이지(`SettingsPage`)가 소유한다** — Radix `TabsContent`
 * 가 비활성 탭을 언마운트하므로 탭이 상태를 가지면 탭 전환이 미저장 편집을 죽인다.
 *
 * 자격증명 부분은 `useAiCredentialForm` 을 분류 엔드포인트로 그대로 쓰고, 문서 밖의 모델만 여기서
 * 들고 있다: 조회 응답(`onResponse`)에서 받아 두고, PUT 때 `{...자격증명, model}` 로 싣는다.
 */
export function useAiClassifyForm(): UseAiClassifyFormResult {
  const [activated, setActivated] = useState(false);
  const [editingUnconfigured, setEditingUnconfigured] = useState(false);
  const [model, setModelState] = useState('');
  const [savedModel, setSavedModel] = useState('');
  const [modelError, setModelError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [isClearing, setIsClearing] = useState(false);

  // 서버가 확정한 모델로 다시 시드한다 — 최초 조회·저장 후 재조회·해제 후 재조회 모두 이 경로다.
  // 훅이 이 콜백의 예외를 "조회 실패"로 보고하므로, 대입 말고 아무것도 하지 않는다.
  const onResponse = useCallback((data: AiClassifyCredentialResponse) => {
    setModelState(data.model ?? '');
    setSavedModel(data.model ?? '');
    setModelError(null);
  }, []);

  // put 이 읽을 최신 모델 — ref 로 들고 있어 api 어댑터를 모델 입력마다 다시 만들지 않는다.
  // (다시 만들면 useAiCredentialForm 의 load/save/probe 콜백이 키 입력마다 새로 생긴다.)
  // 커밋 직후(레이아웃 단계) 갱신하므로 이후의 저장 클릭은 항상 화면에 보이는 모델을 싣는다.
  const modelRef = useRef(model);
  useLayoutEffect(() => {
    modelRef.current = model;
  }, [model]);

  // 모델을 ref 로 읽으므로 어댑터는 한 번만 만든다.
  const api = useMemo<AiCredentialEndpoints<AiClassifyCredentialResponse>>(
    () => ({
      get: () => aiClassifyCredentialApi.get(),
      put: (data) => aiClassifyCredentialApi.put({ ...data, model: modelRef.current }),
      probe: (data) => aiClassifyCredentialApi.probe(data),
    }),
    [],
  );

  const base = useAiCredentialForm<AiClassifyCredentialResponse>({
    api,
    enabled: activated,
    onResponse,
    loadErrorMessage: 'AI 분류 설정을 불러오지 못했습니다.',
  });
  const {
    agentType,
    setAgentType: baseSetAgentType,
    reset: baseReset,
    reload,
    save: baseSave,
    staleNotice,
  } = base;

  // 유형을 바꾸면 모델도 비운다 — 유형마다 모델 형식이 달라(opencode 는 공급자/모델) 옛 값이 새
  // 유형에서 형식 위반이 된다.
  const setAgentType = useCallback(
    (next: AgentType) => {
      if (next !== agentType) {
        setModelState('');
        // 모델 입력 자체가 새로 시작하므로 옛 모델 오류도 함께 지운다 — 남기면 사용자가 건드리지도
        // 않은 새 입력칸 아래 "분류 모델을 선택하세요" 가 떠 있게 된다.
        setModelError(null);
      }
      baseSetAgentType(next);
    },
    [agentType, baseSetAgentType],
  );
  // base 는 매 렌더 새 객체라 메모할 이유가 없다 — setAgentType 만 감싼 사본을 넘긴다.
  const cred: UseAiCredentialFormResult = { ...base, setAgentType };

  const editing = base.configured || editingUnconfigured;
  const hasUnsavedInput = editing && (base.hasUnsavedInput || model !== savedModel);

  const setModel = useCallback((value: string) => {
    setModelState(value);
    setModelError(null);
  }, []);

  const save = useCallback(async (): Promise<boolean> => {
    if (model.trim() === '') {
      setModelError(CLASSIFY_MODEL_REQUIRED);
      return false;
    }
    setIsSaving(true);
    try {
      // 저장 성공 시 editingUnconfigured 를 접지 않는다 — 접으면 저장 직후 재조회가 실패했을 때
      // (base.configured 가 아직 false) 폼이 사라지고 "AI 에이전트 설정을 사용 중입니다." 배너가
      // 뜬다. 분류 전용 설정은 실제로 쓰였으므로 거짓말이 된다. 재조회가 성공하면
      // base.configured 가 true 가 되어 editing 이 그대로 유지되고, 접는 일은 clear()/
      // cancelEditing() 만 한다.
      return await baseSave();
    } finally {
      setIsSaving(false);
    }
  }, [model, baseSave]);

  const clear = useCallback(async () => {
    setIsClearing(true);
    try {
      await aiClassifyCredentialApi.delete();
      toast.success('분류 전용 설정을 해제했습니다.');
      setEditingUnconfigured(false);
      await reload();
    } catch (err) {
      toast.error(extractApiError(err, '설정 해제에 실패했습니다.'));
    } finally {
      setIsClearing(false);
    }
  }, [reload]);

  const cancelEditing = useCallback(() => {
    // 저장은 됐는데 재조회가 실패한 상태면 아무것도 하지 않는다 — 접으면 실제로 쓰이는 분류 전용
    // 설정을 "미설정"으로 그리게 되고, 되돌릴 로컬 입력도 없다(savedModel 은 저장 전 값이라
    // 되돌리면 방금 저장한 값을 화면에서 지워 버린다).
    if (staleNotice !== null) return;
    baseReset();
    setModelState(savedModel);
    setModelError(null);
    setEditingUnconfigured(false);
  }, [baseReset, savedModel, staleNotice]);

  // 훅 호출은 반환 객체 리터럴 안에 두지 않는다(rules-of-hooks).
  const activate = useCallback(() => setActivated(true), []);
  const startEditing = useCallback(() => setEditingUnconfigured(true), []);

  return {
    cred,
    activated,
    activate,
    editing,
    startEditing,
    cancelEditing,
    model,
    setModel,
    modelError,
    hasUnsavedInput,
    isSaving,
    isClearing,
    save,
    clear,
  };
}
