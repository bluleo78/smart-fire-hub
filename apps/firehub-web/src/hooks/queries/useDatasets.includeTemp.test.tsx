/**
 * 데이터셋 목록 훅의 임시(TEMP) 포함 여부 전달 테스트 (WD-754)
 *
 * 서버 GET /datasets 는 includeTemp 기본값 false 로 임시 데이터셋을 숨긴다.
 * - useAllDatasets(파이프라인 입력·출력, 트리거 감시 대상 등 선택 목록)는 다른 단계의 임시 결과를 입력으로 읽어야 하므로
 *   모든 페이지 요청에 includeTemp=true 를 실어야 한다 — 빠지면 임시 데이터셋을 쓰는 스텝이 빈 칸으로 보인다.
 * - useDatasets 는 호출처가 넘긴 includeTemp 를 그대로 전달한다(목록 화면의 「임시 데이터셋 보기」).
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const getDatasetsMock = vi.fn();
vi.mock('../../api/datasets', () => ({
  datasetsApi: { getDatasets: (params: unknown) => getDatasetsMock(params) },
  categoriesApi: {},
}));

import { useAllDatasets, useDatasets } from './useDatasets';

/** 테스트마다 새 QueryClient 로 감싸 캐시가 다른 테스트로 새지 않게 한다 */
function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

/** 페이지 응답 모양만 갖춘 가짜 응답 — totalPages 로 몇 페이지를 순회할지 정한다 */
function pageResponse(page: number, totalPages: number) {
  return { data: { content: [{ id: page + 1 }], page, size: 100, totalElements: totalPages, totalPages } };
}

describe('데이터셋 목록 훅 — includeTemp 전달 (WD-754)', () => {
  beforeEach(() => {
    getDatasetsMock.mockReset();
  });

  it('useAllDatasets 는 모든 페이지 요청에 includeTemp=true 를 싣는다', async () => {
    // 두 페이지짜리 목록 — 첫 페이지뿐 아니라 이어지는 페이지 요청에도 실려야 한다
    getDatasetsMock.mockImplementation((params: { page: number }) => Promise.resolve(pageResponse(params.page, 2)));

    const { result } = renderHook(() => useAllDatasets(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(getDatasetsMock).toHaveBeenCalledTimes(2);
    for (const [params] of getDatasetsMock.mock.calls) {
      expect(params).toMatchObject({ includeTemp: true });
    }
    expect(result.current.data?.map((d) => d.id)).toEqual([1, 2]);
  });

  it('useDatasets 는 넘겨받은 includeTemp 를 그대로 전달하고, 넘기지 않으면 싣지 않는다', async () => {
    getDatasetsMock.mockImplementation(() => Promise.resolve(pageResponse(0, 1)));

    const on = renderHook(() => useDatasets({ page: 0, size: 10, includeTemp: true }), { wrapper: wrapper() });
    await waitFor(() => expect(on.result.current.isSuccess).toBe(true));
    expect(getDatasetsMock).toHaveBeenLastCalledWith({ page: 0, size: 10, includeTemp: true });

    // 기본(목록 화면·홈 최근 데이터셋) — 서버 기본값 false 에 맡긴다
    const off = renderHook(() => useDatasets({ page: 0, size: 5 }), { wrapper: wrapper() });
    await waitFor(() => expect(off.result.current.isSuccess).toBe(true));
    expect(getDatasetsMock).toHaveBeenLastCalledWith({ page: 0, size: 5 });
  });
});
