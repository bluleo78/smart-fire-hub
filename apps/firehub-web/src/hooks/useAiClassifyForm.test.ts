/**
 * useAiClassifyForm 단위 테스트(#707).
 *
 * 이 훅이 소유한 "탭이 폼을 펼칠지(`editing`)"와 "모델 오류 문구(`modelError`)"의 수명이
 * 틀리면 사용자가 화면을 <b>오해</b>한다 — 분류 전용 설정이 실제로 저장됐는데 "AI 에이전트
 * 설정을 사용 중입니다." 배너가 뜨거나, 모델을 비운 채 유형을 바꿨는데 옛 오류가 남는다.
 * 두 결정만 여기서 고정한다(자격증명 상태 기계 자체는 `useAiCredentialForm.test.ts`).
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { aiClassifyCredentialApi } from '../api/settings';
import type { AiClassifyCredentialResponse } from '../types/settings';
import { CLASSIFY_MODEL_REQUIRED, useAiClassifyForm } from './useAiClassifyForm';

vi.mock('../api/settings', () => ({
  // 분류 엔드포인트만 쓴다. `settingsApi` 도 최소로 둔다 — `useAiCredentialForm` 의 기본
  // 엔드포인트 상수가 이 모듈을 참조하기 때문(호출은 하지 않는다).
  aiClassifyCredentialApi: {
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    probe: vi.fn(),
  },
  settingsApi: {
    getAiCredential: vi.fn(),
    putAiCredential: vi.fn(),
    probeAiCredential: vi.fn(),
  },
}));

// 토스트는 이 훅의 계약이 아니다 — jsdom 에서 sonner 가 DOM 을 건드리지 않게 막는다.
vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

const mockedGet = vi.mocked(aiClassifyCredentialApi.get);
const mockedPut = vi.mocked(aiClassifyCredentialApi.put);

/** GET 응답 픽스처. 기본값은 "분류 전용 설정이 없는 테넌트"다. */
function makeResponse(overrides: Partial<AiClassifyCredentialResponse> = {}): AiClassifyCredentialResponse {
  return {
    agentType: 'sdk',
    payload: {},
    secretFieldNames: [],
    configured: false,
    model: '',
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedGet.mockResolvedValue({ data: makeResponse() } as never);
  mockedPut.mockResolvedValue({} as never);
});

/** 탭을 열고(=조회 시작) 최초 조회가 끝날 때까지 기다린다. */
async function renderActivated() {
  const view = renderHook(() => useAiClassifyForm());
  act(() => view.result.current.activate());
  await waitFor(() => expect(view.result.current.cred.isLoading).toBe(false));
  return view;
}

describe('useAiClassifyForm', () => {
  /**
   * <b>변종: 저장 성공 시 `setEditingUnconfigured(false)` 를 그대로 둔다.</b> 그러면 저장 뒤
   * 재조회가 실패했을 때 `configured` 가 아직 false 라 폼이 접히고, 분류 전용 설정이 실제로
   * 쓰였는데도 "AI 에이전트 설정을 사용 중입니다." 배너가 뜬다.
   */
  it('저장은 성공하고 재조회가 실패해도 폼이 접히지 않는다(채팅 설정 사용 중 배너 금지)', async () => {
    const view = await renderActivated();
    act(() => view.result.current.startEditing());
    act(() => view.result.current.setModel('claude-sonnet-5'));

    // 저장 직후의 재조회만 실패시킨다.
    mockedGet.mockRejectedValueOnce(new Error('network down'));
    let saved: boolean | undefined;
    await act(async () => {
      saved = await view.result.current.save();
    });

    expect(saved).toBe(true);
    expect(mockedPut).toHaveBeenCalledOnce();
    // "썼지만 다시 못 읽었다" 분기에 실제로 들어왔는지 고정한다.
    expect(view.result.current.cred.configured).toBe(false);
    expect(view.result.current.cred.staleNotice).not.toBeNull();
    // 그래도 폼은 펼친 채여야 한다 — 미설정 배너를 그리면 거짓말이다.
    expect(view.result.current.editing).toBe(true);
  });

  /**
   * <b>변종: `cancelEditing` 이 staleNotice 를 보지 않고 항상 접는다.</b> 그러면 저장 후 재조회가
   * 실패한 화면에서 "취소"를 누른 사용자가 위 테스트가 막아 둔 바로 그 거짓 배너를 보게 된다 —
   * 분류 전용 설정이 저장돼 쓰이는 중인데 "AI 에이전트 설정을 사용 중입니다."
   */
  it('저장 후 재조회 실패 상태에서는 cancelEditing 이 폼을 접지도 입력을 되돌리지도 않는다', async () => {
    const view = await renderActivated();
    act(() => view.result.current.startEditing());
    act(() => view.result.current.setModel('claude-sonnet-5'));

    mockedGet.mockRejectedValueOnce(new Error('network down'));
    await act(async () => {
      await view.result.current.save();
    });
    expect(view.result.current.cred.staleNotice).not.toBeNull();

    act(() => view.result.current.cancelEditing());

    expect(view.result.current.editing).toBe(true);
    // 방금 저장한 값이 화면에 그대로 남아야 한다 — savedModel('') 로 되돌리면 저장된 설정을 지운
    // 것처럼 보인다.
    expect(view.result.current.model).toBe('claude-sonnet-5');
    expect(view.result.current.cred.staleNotice).not.toBeNull();
  });

  /** 평소(저장 전) 취소는 그대로 접고 입력을 버린다 — 위 가드가 기능을 통째로 죽이지 않았는지. */
  it('저장하지 않은 편집에서는 cancelEditing 이 폼을 접고 입력을 버린다', async () => {
    const view = await renderActivated();
    act(() => view.result.current.startEditing());
    act(() => view.result.current.setModel('claude-sonnet-5'));
    expect(view.result.current.editing).toBe(true);

    act(() => view.result.current.cancelEditing());

    expect(view.result.current.editing).toBe(false);
    expect(view.result.current.model).toBe('');
  });

  /** 재조회가 성공하면 서버 `configured` 가 폼을 계속 펼친다(위 수정의 부작용 없음 확인). */
  it('저장 후 재조회가 성공하면 서버 configured 로 폼이 유지된다', async () => {
    const view = await renderActivated();
    act(() => view.result.current.startEditing());
    act(() => view.result.current.setModel('claude-sonnet-5'));

    mockedGet.mockResolvedValueOnce({
      data: makeResponse({ configured: true, model: 'claude-sonnet-5' }),
    } as never);
    await act(async () => {
      await view.result.current.save();
    });

    expect(view.result.current.cred.configured).toBe(true);
    expect(view.result.current.editing).toBe(true);
    expect(view.result.current.hasUnsavedInput).toBe(false);
  });

  /**
   * <b>변종: `setAgentType` 이 모델만 비우고 `modelError` 를 남긴다.</b> 유형을 바꾸면 모델
   * 입력 자체가 사라지는데 "분류 모델을 선택하세요" 오류만 남아 떠 있다.
   */
  it('에이전트 유형을 바꾸면 모델과 함께 모델 오류도 사라진다', async () => {
    const view = await renderActivated();
    act(() => view.result.current.startEditing());

    // 모델 공백으로 저장 → 화면이 먼저 막고 오류를 세운다.
    let saved: boolean | undefined;
    await act(async () => {
      saved = await view.result.current.save();
    });
    expect(saved).toBe(false);
    expect(mockedPut).not.toHaveBeenCalled();
    expect(view.result.current.modelError).toBe(CLASSIFY_MODEL_REQUIRED);

    act(() => view.result.current.cred.setAgentType('opencode'));

    expect(view.result.current.model).toBe('');
    expect(view.result.current.modelError).toBeNull();
  });
});
