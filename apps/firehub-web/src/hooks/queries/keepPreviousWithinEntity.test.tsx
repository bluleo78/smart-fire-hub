/**
 * keepPreviousWithinEntity·useJobExecutions 의 placeholderData 가드 단위 시험 (WD-965)
 *
 * E2E 는 작업 상세 페이지가 탭을 key={jobId} 로 재마운트해 이 가드를 직접 검증할 수 없다 — 여기서 훅 단위로 지킨다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { proactive } = vi.hoisted(() => ({ proactive: { getJobExecutions: vi.fn() } }));
vi.mock('../../api/proactive', () => ({ proactiveApi: proactive }));

import { keepPreviousWithinEntity } from './keepPreviousWithinEntity';
import { useJobExecutions } from './useProactiveMessages';

describe('keepPreviousWithinEntity — keyIndex', () => {
  it('기본(keyIndex 1)은 queryKey[1] 이 같을 때만 이전 데이터를 유지한다', () => {
    const cb = keepPreviousWithinEntity<string[]>(5);
    expect(cb(['a'], { queryKey: ['x', 5, 'y'] })).toEqual(['a']);
    expect(cb(['a'], { queryKey: ['x', 6, 'y'] })).toBeUndefined();
  });

  it('keyIndex 2 는 queryKey[2] 를 비교한다', () => {
    const cb = keepPreviousWithinEntity<string[]>(5, 2);
    expect(cb(['a'], { queryKey: ['x', 'y', 5] })).toEqual(['a']);
    expect(cb(['a'], { queryKey: ['x', 5, 6] })).toBeUndefined();
    expect(cb(['a'], undefined)).toBeUndefined();
  });
});

describe('useJobExecutions placeholderData (WD-965)', () => {
  beforeEach(() => {
    proactive.getJobExecutions.mockReset();
  });

  /**
   * 시험이 직접 푸는 응답 — promise 를 쿼리 함수가 불리기 전에 미리 만든다(리뷰 후속).
   * 쿼리 함수 안에서 release 를 채우면, 조회가 아직 시작되지 않았을 때 release() 가 undefined 를 불러 시험이 흔들린다.
   */
  function deferred<T>(value: T) {
    let release!: () => void;
    const promise = new Promise<T>((r) => {
      release = () => r(value);
    });
    return { promise, release };
  }

  const wrapper = () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };

  it('같은 작업에서 limit 이 늘면 이전 데이터를 유지하고 isPlaceholderData 가 참이다', async () => {
    const more = deferred({ data: [{ id: 1 }, { id: 2 }] });
    proactive.getJobExecutions.mockImplementation((_id: number, params?: { limit?: number }) =>
      params?.limit === 40 ? more.promise : Promise.resolve({ data: [{ id: 1 }] }),
    );
    const { result, rerender } = renderHook(({ limit }) => useJobExecutions(1, { limit, offset: 0 }), {
      wrapper: wrapper(),
      initialProps: { limit: 20 },
    });
    await waitFor(() => expect(result.current.data).toEqual([{ id: 1 }]));
    rerender({ limit: 40 });
    expect(result.current.data).toEqual([{ id: 1 }]);
    expect(result.current.isPlaceholderData).toBe(true);
    await act(async () => more.release());
    await waitFor(() => expect(result.current.isPlaceholderData).toBe(false));
  });

  it('작업이 바뀌면 새 작업 응답 전에 이전 작업 데이터를 보이지 않는다', async () => {
    // 2번 작업 응답은 시험이 끝에서 풀 때까지 붙잡는다 (영영 안 끝나는 promise 는 정리 단계를 막는다)
    const job2 = deferred({ data: [{ id: 22 }] });
    proactive.getJobExecutions.mockImplementation((id: number) =>
      id === 1 ? Promise.resolve({ data: [{ id: 11 }] }) : job2.promise,
    );
    const { result, rerender } = renderHook(({ jobId }) => useJobExecutions(jobId, { limit: 20, offset: 0 }), {
      wrapper: wrapper(),
      initialProps: { jobId: 1 },
    });
    await waitFor(() => expect(result.current.data).toEqual([{ id: 11 }]));
    rerender({ jobId: 2 });
    expect(result.current.data).toBeUndefined();
    expect(result.current.isPlaceholderData).toBe(false);
    await act(async () => job2.release());
    await waitFor(() => expect(result.current.data).toEqual([{ id: 22 }]));
  });
});
