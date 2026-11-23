/**
 * useAIChat — 대화 이력 로딩 중 sendMessage 방어 (WD-1004).
 *
 * 이력(GET /ai/sessions/{id}/messages) 응답 전에 보내면 loadSession 의 setMessages 가 방금 보낸 글·답을 덮어쓰므로,
 * 로딩 중에는 streamAIChat 을 부르지 않고 안내 토스트만 띄우는지, 로딩이 끝나거나 새 대화로 바뀌면 다시 보내지는지 검증한다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import React from 'react';
import { toast } from 'sonner';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { aiApi, streamAIChat } from '@/api/ai';
import { client as apiClient } from '@/api/client';

import { useAIChat } from './useAIChat';

vi.mock('@/api/ai', () => ({
  aiApi: { createSession: vi.fn(), cancelChat: vi.fn(), getSessionMessages: vi.fn() },
  streamAIChat: vi.fn(),
}));
vi.mock('@/api/client', () => ({ client: { get: vi.fn() } }));
vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() } }));

const NOTICE = '대화 이력을 불러오는 중입니다. 잠시 후 다시 보내 주세요.';

/** 손으로 풀고 거절하는 promise. */
function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
  // jsdom 에는 createObjectURL 이 없다 — 이미지 첨부 blob URL 시험용 (WD-1004)
  URL.createObjectURL = vi.fn(() => 'blob:test-preview');
  URL.revokeObjectURL = vi.fn();
  vi.mocked(aiApi.createSession).mockResolvedValue({} as never);
  vi.mocked(streamAIChat).mockImplementation(() => new AbortController());
});

describe('useAIChat — 이력 로딩 중 전송 방어 (WD-1004)', () => {
  it('이력 응답 전 sendMessage 는 streamAIChat 을 부르지 않고 안내 토스트를 띄운다', async () => {
    const d = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockReturnValue(d.promise as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });

    act(() => {
      void result.current.loadSession('s1');
    });
    await act(async () => {
      await result.current.sendMessage('안녕');
    });

    expect(streamAIChat).not.toHaveBeenCalled();
    expect(toast.info).toHaveBeenCalledTimes(1);
    expect(toast.info).toHaveBeenCalledWith(NOTICE);
  });

  it('이력 응답 뒤에는 보낸다', async () => {
    const d = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockReturnValue(d.promise as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });

    act(() => {
      void result.current.loadSession('S1');
    });
    await act(async () => {
      d.resolve({ data: [] });
    });
    await act(async () => {
      await result.current.sendMessage('안녕');
    });

    expect(streamAIChat).toHaveBeenCalledTimes(1);
    expect(vi.mocked(streamAIChat).mock.calls[0][1]).toBe('S1');
  });

  it('이력 요청이 실패해도 로딩이 풀려 보낼 수 있다', async () => {
    const d = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockReturnValue(d.promise as never);
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { result } = renderHook(() => useAIChat(), { wrapper });

    act(() => {
      void result.current.loadSession('s1');
    });
    await act(async () => {
      d.reject(new Error('500'));
    });
    await act(async () => {
      await result.current.sendMessage('안녕');
    });

    expect(streamAIChat).toHaveBeenCalledTimes(1);
    err.mockRestore();
  });

  it('로딩 중 새 대화로 바꾸면 바로 보낼 수 있다', async () => {
    const d = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockReturnValue(d.promise as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });

    act(() => {
      void result.current.loadSession('s1');
    });
    act(() => result.current.startNewSession());
    await act(async () => {
      await result.current.sendMessage('안녕');
    });

    expect(streamAIChat).toHaveBeenCalledTimes(1);
    expect(vi.mocked(streamAIChat).mock.calls[0][1]).toBeNull();
  });

  it('A 로딩 중 B 를 열면 A 응답이 와도 B 응답 전까지는 막힌다', async () => {
    const a = deferred<{ data: unknown[] }>();
    const b = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockImplementation(((id: string) =>
      (id === 'A' ? a.promise : b.promise)) as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });

    act(() => {
      void result.current.loadSession('A');
    });
    act(() => {
      void result.current.loadSession('B');
    });
    await act(async () => {
      a.resolve({ data: [] });
    });
    await act(async () => {
      await result.current.sendMessage('안녕');
    });
    expect(streamAIChat).not.toHaveBeenCalled();

    await act(async () => {
      b.resolve({ data: [] });
    });
    await act(async () => {
      await result.current.sendMessage('안녕');
    });
    expect(streamAIChat).toHaveBeenCalledTimes(1);
  });
});

describe('useAIChat — 로딩 중 보관한 AI 에게 묻기 질문 (WD-1004)', () => {
  it('로딩 중 보관한 질문은 이력이 온 뒤 그 대화로 한 번만 보낸다', async () => {
    const d = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockReturnValue(d.promise as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });

    act(() => {
      void result.current.loadSession('S1');
    });
    act(() => result.current.sendAfterHistoryLoaded('묻기 질문'));
    expect(streamAIChat).not.toHaveBeenCalled();

    await act(async () => {
      d.resolve({ data: [] });
    });
    expect(streamAIChat).toHaveBeenCalledTimes(1);
    expect(vi.mocked(streamAIChat).mock.calls[0][0]).toBe('묻기 질문');
    expect(vi.mocked(streamAIChat).mock.calls[0][1]).toBe('S1');
  });

  it('로딩 중이 아니면 바로 보낸다', async () => {
    const { result } = renderHook(() => useAIChat(), { wrapper });
    await act(async () => {
      result.current.sendAfterHistoryLoaded('바로');
    });
    expect(streamAIChat).toHaveBeenCalledTimes(1);
  });

  it('로딩 중 새 대화를 시작하면 보관한 질문은 보내지 않는다', async () => {
    const d = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockReturnValue(d.promise as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });

    act(() => {
      void result.current.loadSession('S1');
    });
    act(() => result.current.sendAfterHistoryLoaded('묻기 질문'));
    act(() => result.current.startNewSession());
    await act(async () => {
      d.resolve({ data: [] });
    });
    expect(streamAIChat).not.toHaveBeenCalled();
  });

  it('로딩 중 다른 대화 B 를 열면 보관한 질문은 B 로도 보내지 않는다', async () => {
    const a = deferred<{ data: unknown[] }>();
    const b = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockImplementation(((id: string) =>
      (id === 'A' ? a.promise : b.promise)) as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });

    act(() => {
      void result.current.loadSession('A');
    });
    act(() => result.current.sendAfterHistoryLoaded('묻기 질문'));
    act(() => {
      void result.current.loadSession('B');
    });
    await act(async () => {
      a.resolve({ data: [] });
    });
    await act(async () => {
      b.resolve({ data: [] });
    });
    expect(streamAIChat).not.toHaveBeenCalled();
  });

  it('같은 대화의 이력 요청이 실패(500)해도 보관한 질문은 그 대화로 보낸다', async () => {
    const d = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockReturnValue(d.promise as never);
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { result } = renderHook(() => useAIChat(), { wrapper });

    act(() => {
      void result.current.loadSession('S1');
    });
    act(() => result.current.sendAfterHistoryLoaded('묻기 질문'));
    await act(async () => {
      d.reject(new Error('500'));
    });
    expect(streamAIChat).toHaveBeenCalledTimes(1);
    expect(vi.mocked(streamAIChat).mock.calls[0][1]).toBe('S1');
    err.mockRestore();
  });
});

describe('useAIChat — 응답 중단 취소를 기다리는 사이 대화가 바뀜 (WD-1004)', () => {
  it('취소를 기다리는 동안 다른 대화를 열면 보내지 않고 안내한다', async () => {
    const onEvents: ((e: { type: string; sessionId?: string }) => void)[] = [];
    vi.mocked(streamAIChat).mockImplementation((_m, _s, _f, onEvent) => {
      onEvents.push(onEvent as never);
      return new AbortController();
    });
    const cancel = deferred<unknown>();
    vi.mocked(aiApi.cancelChat).mockReturnValue(cancel.promise as never);
    const history = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockReturnValue(history.promise as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });

    // 첫 질문을 보내 스트리밍 중에 중단한다 — 서버 취소가 아직 가는 중
    await act(async () => {
      await result.current.sendMessage('첫 질문');
    });
    act(() => onEvents[0]({ type: 'init', sessionId: 'sess-a' }));
    act(() => result.current.stopStreaming());

    // 취소를 기다리는 sendMessage 를 시작한 뒤, 그 사이 대화 B 를 연다
    let sent!: Promise<boolean>;
    act(() => {
      sent = result.current.sendMessage('A');
    });
    act(() => {
      void result.current.loadSession('sess-b');
    });
    await act(async () => {
      cancel.resolve({});
      await sent;
    });

    // 옛 대화로 나가지 않는다 — 처음 한 번만 불렸다
    expect(streamAIChat).toHaveBeenCalledTimes(1);
    // 보내지 못했음을 알려 입력창이 글을 돌려줄 수 있다 (WD-1004)
    expect(await sent).toBe(false);
    expect(toast.info).toHaveBeenCalledWith('대화가 바뀌어 보내지 않았습니다. 다시 보내 주세요.');
  });
});

describe('useAIChat — sendMessage 의 보냄 여부 반환 (WD-1004)', () => {
  it('로딩 중 가드에서는 false, 스트리밍을 시작하면 true 를 돌려준다', async () => {
    const d = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockReturnValue(d.promise as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });

    act(() => {
      void result.current.loadSession('s1');
    });
    let blocked: boolean | undefined;
    await act(async () => {
      blocked = await result.current.sendMessage('안녕');
    });
    expect(blocked).toBe(false);

    await act(async () => {
      d.resolve({ data: [] });
    });
    let sent: boolean | undefined;
    await act(async () => {
      sent = await result.current.sendMessage('안녕');
    });
    expect(sent).toBe(true);
  });
});

describe('useAIChat — 버린 보관 질문 안내 (WD-1004)', () => {
  const DISCARD = '대화가 바뀌어 「AI 에게 물어보기」 질문을 보내지 않았습니다.';
  const REPLACE = '앞서 보관한 「AI 에게 물어보기」 질문 대신 새 질문을 보냅니다.';

  it('보관 중 새 대화를 시작하면 버렸다고 알린다', async () => {
    const d = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockReturnValue(d.promise as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });
    act(() => {
      void result.current.loadSession('S1');
    });
    act(() => result.current.sendAfterHistoryLoaded('질문'));
    act(() => result.current.startNewSession());
    expect(toast.info).toHaveBeenCalledWith(DISCARD);
  });

  it('보관 중 다른 대화를 열면 버렸다고 알린다', async () => {
    const d = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockReturnValue(d.promise as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });
    act(() => {
      void result.current.loadSession('S1');
    });
    act(() => result.current.sendAfterHistoryLoaded('질문'));
    act(() => {
      void result.current.loadSession('S2');
    });
    expect(toast.info).toHaveBeenCalledWith(DISCARD);
  });

  it('보관 중 두 번째 질문이 오면 대신한다고 알린다', async () => {
    const d = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages).mockReturnValue(d.promise as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });
    act(() => {
      void result.current.loadSession('S1');
    });
    act(() => result.current.sendAfterHistoryLoaded('첫 질문'));
    expect(toast.info).not.toHaveBeenCalled();
    act(() => result.current.sendAfterHistoryLoaded('둘째 질문'));
    expect(toast.info).toHaveBeenCalledWith(REPLACE);
  });

  it('보관한 질문이 없으면 알리지 않는다', async () => {
    const { result } = renderHook(() => useAIChat(), { wrapper });
    act(() => result.current.startNewSession());
    expect(toast.info).not.toHaveBeenCalled();
  });
});

describe('useAIChat — 이미지 내려받기가 보내기를 막지 않음 (WD-1004)', () => {
  it('이미지 blob 이 끝나지 않아도 보낼 수 있고, blob 이 온 뒤 새 글은 남고 첨부에 previewUrl 이 들어간다', async () => {
    const blob = deferred<{ data: Blob }>();
    vi.mocked(apiClient.get).mockReturnValue(blob.promise as never);
    vi.mocked(aiApi.getSessionMessages).mockResolvedValue({
      data: [
        {
          id: 'm1',
          role: 'USER',
          content: '사진',
          attachments: [{ id: 7, mimeType: 'image/png', fileName: 'a.png' }],
        },
      ],
    } as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });

    // loadSession 은 blob 을 기다리면 끝나지 않으므로 await 하지 않고 응답 처리만 흘려보낸다
    await act(async () => {
      void result.current.loadSession('S1');
    });
    // blob 이 아직 안 왔어도 로딩이 풀려 보내진다
    await act(async () => {
      await result.current.sendMessage('새 글');
    });
    expect(streamAIChat).toHaveBeenCalledTimes(1);
    expect(toast.info).not.toHaveBeenCalled();

    await act(async () => {
      blob.resolve({ data: new Blob(['x']) });
    });
    // 보낸 글은 스트림이 끝나기 전까지 pendingUserMessage 에 있다 — blob 도착이 그것을 지우지 않는다
    expect(result.current.pendingUserMessage).toBe('새 글');
    expect(result.current.messages.map((m) => m.id)).toEqual(['m1']);
    const hist = result.current.messages.find((m) => m.id === 'm1');
    expect(hist?.attachments?.[0].previewUrl).toBe('blob:test-preview');
  });
});

describe('useAIChat — 같은 대화를 다시 열어도 보관 질문 유지 (WD-1004)', () => {
  it('보관 중 같은 대화로 loadSession 을 다시 부르면 토스트 없이 이력 뒤 한 번 보낸다', async () => {
    const d1 = deferred<{ data: unknown[] }>();
    const d2 = deferred<{ data: unknown[] }>();
    vi.mocked(aiApi.getSessionMessages)
      .mockReturnValueOnce(d1.promise as never)
      .mockReturnValueOnce(d2.promise as never);
    const { result } = renderHook(() => useAIChat(), { wrapper });
    act(() => {
      void result.current.loadSession('S1');
    });
    act(() => result.current.sendAfterHistoryLoaded('질문'));
    act(() => {
      void result.current.loadSession('S1');
    });
    expect(toast.info).not.toHaveBeenCalled();
    await act(async () => {
      d1.resolve({ data: [] });
      d2.resolve({ data: [] });
    });
    expect(toast.info).not.toHaveBeenCalled();
    expect(streamAIChat).toHaveBeenCalledTimes(1);
    expect(vi.mocked(streamAIChat).mock.calls[0][0]).toBe('질문');
  });
});
