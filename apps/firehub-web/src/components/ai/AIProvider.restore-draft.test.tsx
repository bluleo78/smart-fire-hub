import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AIProvider, useAI } from './AIProvider';

// 현재 대화 id 를 시험에서 바꿀 수 있는 가짜 useAIChat (WD-1050)
const chat = vi.hoisted(() => ({
  sessionId: 'S1' as string | null,
  ref: { current: 'S1' as string | null },
}));

vi.mock('../../hooks/queries/useAIChat', () => ({
  useAIChat: () => ({
    messages: [],
    isStreaming: false,
    isThinking: false,
    isUploading: false,
    isLoadingHistory: false,
    streamingMessage: null,
    pendingUserMessage: null,
    currentSessionId: chat.sessionId,
    currentSessionIdRef: chat.ref,
    sendMessage: vi.fn(),
    sendAfterHistoryLoaded: vi.fn(),
    stopStreaming: vi.fn(),
    startNewSession: vi.fn(),
    loadSession: vi.fn(),
    handleSessionDeleted: vi.fn(),
    contextTokens: null,
    isCompacting: false,
    compactionStartedAt: null,
  }),
}));

vi.mock('../../hooks/useCanvasState', () => ({
  useCanvasState: () => ({ addWidget: vi.fn(), resetCanvas: vi.fn() }),
}));

/** 현재 대화를 바꾼다 — 렌더 값과 동기 ref 를 함께 맞춘다 */
function setSession(id: string | null) {
  chat.sessionId = id;
  chat.ref.current = id;
}

describe('AIProvider restoreDraft (WD-1050)', () => {
  beforeEach(() => {
    setSession('S1');
  });

  it('openAI 와 setRestoreDraft 를 같은 배치에서 해도 초안이 남는다', () => {
    const { result } = renderHook(() => useAI(), { wrapper: AIProvider });
    act(() => {
      result.current.openAI();
      result.current.setRestoreDraft('글');
    });
    expect(result.current.isOpen).toBe(true);
    expect(result.current.restoreDraft).toBe('글');
  });

  it('패널이 닫히면 초안이 비워지고 다시 열어도 null 이다', () => {
    const { result } = renderHook(() => useAI(), { wrapper: AIProvider });
    act(() => result.current.openAI());
    act(() => result.current.setRestoreDraft('글'));
    expect(result.current.restoreDraft).toBe('글');
    act(() => result.current.closeAI());
    expect(result.current.restoreDraft).toBeNull();
    act(() => result.current.openAI());
    expect(result.current.restoreDraft).toBeNull();
  });

  it('대화가 바뀌면 초안이 비워지고 원래 대화로 돌아와도 null 이다', () => {
    const { result, rerender } = renderHook(() => useAI(), { wrapper: AIProvider });
    act(() => result.current.setRestoreDraft('글'));
    expect(result.current.restoreDraft).toBe('글');
    setSession('S2');
    rerender();
    expect(result.current.restoreDraft).toBeNull();
    setSession('S1');
    rerender();
    expect(result.current.restoreDraft).toBeNull();
  });

  it('대화가 바뀌는 같은 배치에 새 대화로 묶인 초안이 들어오면 남는다 (WD-1050)', () => {
    const { result, rerender } = renderHook(() => useAI(), { wrapper: AIProvider });
    // 훅은 ref 를 동기로 먼저 바꾸고 렌더는 나중에 따라온다 — 그 사이에 초안이 새 대화 S2 로 묶인다
    // 일부러 ref 만 먼저 바꾼다(렌더 값은 아직 S1) — setSession 을 쓰지 않는 이유
    chat.ref.current = 'S2';
    act(() => result.current.setRestoreDraft('새 대화 글'));
    chat.sessionId = 'S2';
    rerender();
    expect(result.current.restoreDraft).toBe('새 대화 글');
  });
});
