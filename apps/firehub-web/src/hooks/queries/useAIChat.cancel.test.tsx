/**
 * useAIChat — 응답 중단 시 서버 취소 + 같은 세션 동시 실행 거절 안내 (WD-57).
 *
 * 브라우저 fetch abort 만으로는 서버(ai-agent)가 작업을 계속한다(의도된 설계). 그래서 stopStreaming 이
 * 서버 취소 API 를 부르는지, 다음 전송이 그 취소가 끝난 뒤에 나가는지, 서버가 같은 세션의 이전 응답 처리 중이라
 * 거절(session_busy)했을 때 처리되지 않은 메시지를 대화로 남기지 않고 안내만 하는지를 검증한다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import React from 'react';
import { toast } from 'sonner';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { aiApi, streamAIChat } from '@/api/ai';
import type { AIStreamEvent } from '@/types/ai';

import { useAIChat } from './useAIChat';

vi.mock('@/api/ai', () => ({
  aiApi: { createSession: vi.fn(), cancelChat: vi.fn() },
  streamAIChat: vi.fn(),
}));
vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

type OnEvent = (e: AIStreamEvent) => void;

/** streamAIChat 호출마다 이벤트 콜백을 붙잡아 테스트가 서버 이벤트를 흘려 넣게 한다. */
const streams: { sessionId: string | null; onEvent: OnEvent; controller: AbortController }[] = [];

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
  streams.length = 0;
  vi.mocked(aiApi.createSession).mockResolvedValue({} as never);
  vi.mocked(streamAIChat).mockImplementation((_msg, sessionId, _files, onEvent) => {
    const controller = new AbortController();
    streams.push({ sessionId, onEvent, controller });
    return controller;
  });
});

describe('useAIChat — 응답 중단 시 서버 취소 (WD-57)', () => {
  it('스트리밍 중 중단하면 init 으로 받은 세션 id 로 서버 취소 API 를 부른다', async () => {
    vi.mocked(aiApi.cancelChat).mockResolvedValue({} as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });

    await act(async () => {
      await result.current.sendMessage('긴 답을 해줘');
    });
    act(() => {
      streams[0].onEvent({ type: 'init', sessionId: 'sess-57' });
      streams[0].onEvent({ type: 'text', content: '부분' });
    });
    act(() => result.current.stopStreaming());

    expect(streams[0].controller.signal.aborted).toBe(true);
    expect(aiApi.cancelChat).toHaveBeenCalledWith('sess-57');
    // 화면 중단은 그대로 — 부분 응답 보존, 입력 가능
    expect(result.current.isStreaming).toBe(false);
    expect(result.current.messages.map(m => m.content)).toEqual(['긴 답을 해줘', '부분']);
  });

  it('취소 API 가 실패해도 화면 중단은 유지되고 다음 전송이 막히지 않는다', async () => {
    vi.mocked(aiApi.cancelChat).mockRejectedValue(new Error('500'));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { result } = renderHook(() => useAIChat(), { wrapper });

    await act(async () => {
      await result.current.sendMessage('첫 질문');
    });
    act(() => streams[0].onEvent({ type: 'init', sessionId: 'sess-fail' }));
    act(() => result.current.stopStreaming());
    expect(result.current.isStreaming).toBe(false);

    await act(async () => {
      await result.current.sendMessage('다음 질문');
    });
    expect(streams).toHaveLength(2);
    warn.mockRestore();
  });

  it('다음 전송은 직전 취소 요청이 끝난 뒤에 나간다(늦은 취소가 새 실행을 멈추지 않게)', async () => {
    let resolveCancel!: () => void;
    vi.mocked(aiApi.cancelChat).mockReturnValue(
      new Promise(resolve => {
        resolveCancel = () => resolve({} as never);
      }) as never,
    );
    const { result } = renderHook(() => useAIChat(), { wrapper });

    await act(async () => {
      await result.current.sendMessage('첫 질문');
    });
    act(() => streams[0].onEvent({ type: 'init', sessionId: 'sess-order' }));
    act(() => result.current.stopStreaming());

    let sent!: Promise<void>;
    act(() => {
      sent = result.current.sendMessage('A');
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(streams).toHaveLength(1); // 취소가 끝나기 전엔 보내지 않는다

    await act(async () => {
      resolveCancel();
      await sent;
    });
    expect(streams).toHaveLength(2);
    expect(streams[1].sessionId).toBe('sess-order');
  });

  it('스트리밍 중이 아니면 중단해도 서버 취소를 부르지 않는다', () => {
    const { result } = renderHook(() => useAIChat(), { wrapper });
    act(() => result.current.stopStreaming());
    expect(aiApi.cancelChat).not.toHaveBeenCalled();
  });
});

describe('useAIChat — 같은 세션 동시 실행 거절 안내 (WD-57)', () => {
  it('session_busy 오류면 토스트로 안내하고, 처리되지 않은 메시지를 대화에 남기지 않는다', async () => {
    const { result } = renderHook(() => useAIChat(), { wrapper });

    await act(async () => {
      await result.current.sendMessage('A');
    });
    act(() =>
      streams[0].onEvent({ type: 'error', code: 'session_busy', message: '이전 응답을 처리 중입니다. 잠시 후 다시 시도해 주세요.' }),
    );

    expect(toast.error).toHaveBeenCalledWith('이전 응답을 처리 중입니다. 잠시 후 다시 시도해 주세요.');
    expect(result.current.messages).toEqual([]);
    expect(result.current.isStreaming).toBe(false);
  });

  it('일반 오류는 기존처럼 사용자 메시지와 오류 안내를 대화에 남긴다', async () => {
    const { result } = renderHook(() => useAIChat(), { wrapper });

    await act(async () => {
      await result.current.sendMessage('B');
    });
    act(() => streams[0].onEvent({ type: 'error', message: '무언가 실패' }));

    expect(toast.error).not.toHaveBeenCalled();
    expect(result.current.messages.map(m => m.content)).toEqual(['B', '오류: 무언가 실패']);
  });
});
