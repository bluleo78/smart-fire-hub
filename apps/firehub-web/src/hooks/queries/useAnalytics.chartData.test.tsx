/**
 * useChartData 자동 새로고침 주기 회귀 테스트 (#778)
 *
 * 대시보드 자동 새로고침은 위젯별 useChartData 의 refetchInterval 이 담당한다.
 * 과거엔 렌더마다 다른 지터 주기를 계산해, 주기보다 자주 다시 렌더되면 TanStack 이 타이머를 계속
 * 재시작해서 자동 새로고침이 영영 발화하지 않았다. 잦은 재렌더 속에서도 주기마다 재조회되는지 검증한다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getChartDataMock = vi.fn();
vi.mock('../../api/analytics', () => ({
  analyticsApi: { getChartData: (...args: unknown[]) => getChartDataMock(...args) },
}));

import { useChartData } from './useAnalytics';

describe('useChartData — 자동 새로고침 주기 (#778)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    getChartDataMock.mockReset();
    getChartDataMock.mockResolvedValue({ data: { chart: null, queryResult: null } });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('주기(5초)보다 자주(1초마다) 다시 렌더돼도 주기마다 재조회한다', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    const { rerender } = renderHook(() => useChartData(1, { refetchInterval: 5_000 }), { wrapper });

    // 최초 조회
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(getChartDataMock).toHaveBeenCalledTimes(1);

    // 13초 동안 1초마다 재렌더 — 지터 최대(+10%)여도 5.5초·11초에 두 번 발화해야 한다
    for (let i = 0; i < 13; i += 1) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1_000);
      });
      rerender();
    }
    expect(getChartDataMock.mock.calls.length).toBeGreaterThanOrEqual(3);
    client.clear();
  });
});
