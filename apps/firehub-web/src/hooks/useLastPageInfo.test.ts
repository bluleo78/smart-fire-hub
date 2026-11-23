import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { useLastPageInfo } from './useLastPageInfo';

type Info = { totalPages: number; totalElements: number } | undefined;

/** 마지막 성공 쪽 정보(totalPages·totalElements) 기억 훅 — 다음 쪽 실패 때 쪽 이동을 남기되 조건이 바뀌면 비운다 (WD-982) */
describe('useLastPageInfo', () => {
  it('값이면 기억·반환하고 undefined 면 마지막 값, 조회 조건(page 제외)이 바뀌면 비운다', () => {
    const { result, rerender } = renderHook(
      ({ data, key }: { data: Info; key: string }) => useLastPageInfo(data, { search: key, page: 3 }),
      { initialProps: { data: { totalPages: 3, totalElements: 30 } as Info, key: 'a' } },
    );
    expect(result.current).toEqual({ totalPages: 3, totalElements: 30 });
    rerender({ data: undefined, key: 'a' });
    expect(result.current).toEqual({ totalPages: 3, totalElements: 30 });
    rerender({ data: undefined, key: 'b' });
    expect(result.current).toEqual({ totalPages: 0, totalElements: undefined });
    rerender({ data: { totalPages: 5, totalElements: 50 }, key: 'b' });
    expect(result.current).toEqual({ totalPages: 5, totalElements: 50 });
    rerender({ data: { totalPages: 2, totalElements: 11 }, key: 'b' });
    expect(result.current).toEqual({ totalPages: 2, totalElements: 11 });
  });

  it('totalElements 도 실패(undefined) 때 마지막 값을 유지한다', () => {
    const { result, rerender } = renderHook(
      ({ data }: { data: Info }) => useLastPageInfo(data, { search: 'a', page: 1 }),
      { initialProps: { data: { totalPages: 4, totalElements: 37 } as Info } },
    );
    rerender({ data: undefined });
    expect(result.current.totalElements).toBe(37);
  });

  it('page 만 다르면 기억을 유지하고 다른 필드가 다르면 비운다', () => {
    const { result, rerender } = renderHook(
      ({ data, params }: { data: Info; params: Record<string, unknown> }) => useLastPageInfo(data, params),
      { initialProps: { data: { totalPages: 4, totalElements: 40 } as Info, params: { search: 'a', page: 0 } as Record<string, unknown> } },
    );
    rerender({ data: undefined, params: { search: 'a', page: 1 } });
    expect(result.current).toEqual({ totalPages: 4, totalElements: 40 });
    rerender({ data: undefined, params: { search: 'b', page: 1 } });
    expect(result.current).toEqual({ totalPages: 0, totalElements: undefined });
  });

  it('조회 조건이 바뀐 그 렌더에 데이터가 오면 그 값을 쓴다', () => {
    const { result, rerender } = renderHook(
      ({ data, key }: { data: Info; key: string }) => useLastPageInfo(data, { search: key, page: 3 }),
      { initialProps: { data: { totalPages: 3, totalElements: 30 } as Info, key: 'a' } },
    );
    rerender({ data: { totalPages: 7, totalElements: 66 }, key: 'b' });
    expect(result.current).toEqual({ totalPages: 7, totalElements: 66 });
  });
});
