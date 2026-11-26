/**
 * 스마트 작업 만들기 훅 — 기본 오류 처리가 서버 사유를 보여주고 silentError 로 끌 수 있다 (WD-1156 리뷰 후속 지적 1)
 *
 * `useCreateProactiveJob` 는 mutate() 만 쓰는 호출처가 늘면 기본 onError 가 유일한 실패 안내다.
 * 고치기 전에는 `toast.error('작업 생성에 실패했습니다.')` 가 서버가 말한 409·403 사유를 덮어써,
 * 사용자는 「이미 사용 중인 이름입니다」 대신 보편 문구만 보았다(지적 1).
 * silentError 를 넘긴 호출처(현재 상세 페이지)는 화면이 토스트를 직접 다루므로 기본 토스트가 뜨지 않아야 한다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));
vi.mock('sonner', () => ({ toast: toastMock }));

const createJobMock = vi.fn();
vi.mock('../../api/proactive', () => ({
  proactiveApi: { createJob: (data: unknown) => createJobMock(data) },
}));

import { useCreateProactiveJob } from './useProactiveMessages';

/** 기본 오류 토스트의 fallback — 알 수 없는 오류일 때만 보여야 한다 */
const FALLBACK_MSG = '작업 생성에 실패했습니다.';
/** 서버가 말한 사유(409 이름 겹침 등) — 이게 먼저 보여야 한다 */
const SERVER_MSG = '이미 사용 중인 이름입니다.';

/** axios 오류처럼 보이게 만든 서버 응답 오류 — extractApiError 가 response.data.message 를 읽는다 */
function serverError(message: string) {
  return Object.assign(new Error('request failed'), {
    isAxiosError: true,
    response: { data: { message } },
  });
}

/** 테스트마다 새 QueryClient 로 캐시·뮤테이션이 다른 테스트로 새지 않게 한다 */
function wrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

const NEW_JOB = { name: '주간 화재 현황', prompt: '주간 화재 현황을 정리해줘' };

describe('useCreateProactiveJob — 기본 오류 처리 (WD-1156 리뷰 후속 지적 1)', () => {
  beforeEach(() => {
    toastMock.error.mockReset();
    toastMock.success.mockReset();
    createJobMock.mockReset();
  });

  it('silentError 없이 실패하면 서버 사유를 토스트로 보여주고 fallback 은 안 뜬다', async () => {
    createJobMock.mockRejectedValue(serverError(SERVER_MSG));

    const { result } = renderHook(() => useCreateProactiveJob(), { wrapper: wrapper() });
    result.current.mutate(NEW_JOB);

    await waitFor(() => expect(toastMock.error).toHaveBeenCalledTimes(1));
    expect(toastMock.error).toHaveBeenCalledWith(SERVER_MSG);
    expect(toastMock.error).not.toHaveBeenCalledWith(FALLBACK_MSG);
  });

  it('서버가 이유를 말하지 않은 오류면 fallback 을 보여준다', async () => {
    createJobMock.mockRejectedValue(new Error('network down'));

    const { result } = renderHook(() => useCreateProactiveJob(), { wrapper: wrapper() });
    result.current.mutate(NEW_JOB);

    await waitFor(() => expect(toastMock.error).toHaveBeenCalledTimes(1));
    expect(toastMock.error).toHaveBeenCalledWith(FALLBACK_MSG);
  });

  it('silentError 를 넘긴 호출처는 기본 토스트를 띄우지 않는다 — 화면이 직접 처리하기 때문이다', async () => {
    createJobMock.mockRejectedValue(serverError(SERVER_MSG));

    const { result } = renderHook(() => useCreateProactiveJob({ silentError: true }), {
      wrapper: wrapper(),
    });
    result.current.mutate(NEW_JOB);

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(toastMock.error).not.toHaveBeenCalled();
  });
});