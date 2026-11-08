/**
 * 상세 조회 훅의 id 가드 회귀 테스트 (WD-667)
 *
 * WD-661 에서 상세 조회 훅이 `enabled: !!id` 대신 `id == null ? skipToken` 으로 바뀌며 0·NaN 이 걸러지지 않았다.
 * 형식 없는 값을 Number() 로 바꿔 넘기면 `/proactive/jobs/NaN/executions/NaN` 을 부르고, RUNNING 폴링까지 돌았다.
 * 이제 모든 상세 조회 훅은 양의 정수 id 일 때만 조회한다 — NaN·0·음수·소수는 조회 함수를 만들지 않는다.
 * 함께 검증: 실행 HTML 키가 실행 단건 키 접두사 아래에 있고(WD-667 #3), 실행이 끝나면 HTML 을 다시 읽으며,
 * 「지금 실행」은 실행 목록만 무효화하고 단건 실행 캐시는 건드리지 않는다(WD-667 #6). 리포트 모달용 enabled 옵션(#4).
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// vi.mock 팩토리는 파일 맨 위로 끌어올려지므로 목 객체도 vi.hoisted 로 함께 올린다
const { proactive, analytics, apiConnections } = vi.hoisted(() => ({
  proactive: {
    getExecution: vi.fn(),
    getJobExecutions: vi.fn(),
    getExecutionHtml: vi.fn(),
    getTemplate: vi.fn(),
    executeJob: vi.fn(),
  },
  analytics: {
    getQuery: vi.fn(),
    getChart: vi.fn(),
    getChartData: vi.fn(),
    getDashboard: vi.fn(),
    getDashboardShareImpact: vi.fn(),
  },
  apiConnections: { getById: vi.fn() },
}));

vi.mock('../../api/proactive', () => ({ proactiveApi: proactive }));
vi.mock('../../api/analytics', () => ({ analyticsApi: analytics }));
vi.mock('../../api/api-connections', () => ({ apiConnectionsApi: apiConnections }));

import { useChart, useChartData, useDashboard, useDashboardShareImpact, useSavedQuery } from './useAnalytics';
import { useApiConnection } from './useApiConnections';
import {
  useExecuteProactiveJob,
  useExecution,
  useExecutionHtml,
  useJobExecutions,
  useProactiveTemplate,
} from './useProactiveMessages';

/** 조회를 만들면 안 되는 id 들 — NaN(형식 없는 값의 Number())·0·음수·소수 */
const INVALID_IDS = [Number.NaN, 0, -1, 1.5];

function makeClient() {
  // 앱(main.tsx)과 같은 staleTime 30초 — 캐시가 「신선」해 다시 읽지 않는 상황을 재현한다
  return new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 30_000 } } });
}

function wrapperFor(client: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

/** 훅을 그리고 대기 중인 조회가 돌 시간을 준 뒤 정리한다 */
async function renderAndFlush<T>(hook: () => T, client = makeClient()) {
  const view = renderHook(hook, { wrapper: wrapperFor(client) });
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
  return { view, client };
}

const allMocks = () => [...Object.values(proactive), ...Object.values(analytics), ...Object.values(apiConnections)];

beforeEach(() => {
  for (const m of allMocks()) {
    m.mockReset();
    m.mockResolvedValue({ data: null });
  }
});

describe('상세 조회 훅 — 양의 정수 id 만 조회한다 (WD-667)', () => {
  it.each(INVALID_IDS)('id=%s 이면 어떤 상세 조회도 요청하지 않는다', async (bad) => {
    await renderAndFlush(() => {
      useExecution(bad, 1);
      useExecution(1, bad);
      useJobExecutions(bad);
      useExecutionHtml(bad, 1);
      useExecutionHtml(1, bad);
      useProactiveTemplate(bad);
      useApiConnection(bad);
      useSavedQuery(bad);
      useChart(bad);
      useChartData(bad);
      useDashboard(bad);
      useDashboardShareImpact(bad);
    });
    for (const m of allMocks()) expect(m).not.toHaveBeenCalled();
  });

  it('양의 정수 id 면 조회한다(대조군)', async () => {
    await renderAndFlush(() => {
      useExecution(7, 3);
      useSavedQuery(2);
    });
    expect(proactive.getExecution).toHaveBeenCalledWith(7, 3);
    expect(analytics.getQuery).toHaveBeenCalledWith(2);
  });
});

describe('useExecution enabled 옵션 (WD-667 #4)', () => {
  it('enabled=false 면(닫힌 리포트 모달) 실행을 조회하지 않는다', async () => {
    await renderAndFlush(() => useExecution(7, 3, { enabled: false }));
    expect(proactive.getExecution).not.toHaveBeenCalled();
  });
});

describe('실행 HTML 캐시 (WD-667 #3)', () => {
  it('작업의 실행 접두사 무효화가 HTML 캐시까지 닿는다', async () => {
    proactive.getExecutionHtml.mockResolvedValue({ data: '<p>x</p>' });
    const { client } = await renderAndFlush(() => useExecutionHtml(7, 3));
    expect(proactive.getExecutionHtml).toHaveBeenCalledTimes(1);

    await act(async () => {
      await client.invalidateQueries({ queryKey: ['proactive', 'executions', 7] });
    });
    expect(proactive.getExecutionHtml).toHaveBeenCalledTimes(2);
  });

  it('실행이 RUNNING 에서 끝나면 HTML 을 다시 읽는다', async () => {
    proactive.getExecution.mockResolvedValueOnce({ data: { id: 3, status: 'RUNNING' } });
    proactive.getExecution.mockResolvedValue({ data: { id: 3, status: 'COMPLETED' } });
    proactive.getExecutionHtml.mockResolvedValue({ data: '' });
    const client = makeClient();
    // 실행 중에 연 모달·뷰어가 빈 HTML 을 캐시에 남긴 상황
    const { view } = await renderAndFlush(() => {
      const exec = useExecution(7, 3);
      useExecutionHtml(7, 3);
      return exec;
    }, client);
    expect(proactive.getExecutionHtml).toHaveBeenCalledTimes(1);

    // 폴링이 다음 조회에서 COMPLETED 를 받는다
    await act(async () => {
      await client.refetchQueries({ queryKey: ['proactive', 'executions', 7, 3], exact: true });
    });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(view.result.current.data?.status).toBe('COMPLETED');
    expect(proactive.getExecutionHtml).toHaveBeenCalledTimes(2);
  });
});

describe('실행 중에 받아 둔 빈 HTML (WD-667 #3)', () => {
  it('다른 화면(리포트 보기)이 실행 중에 캐시한 빈 HTML 은, 완료된 실행을 처음 여는 화면에서 다시 읽는다', async () => {
    const client = makeClient();
    // 리포트 보기가 실행 중에 열려 빈 HTML 을 남겼다 — 실행 단건 캐시는 없다(뷰어는 실행을 조회하지 않는다)
    client.setQueryData(['proactive', 'executions', 7, 3, 'html'], { data: '' });
    proactive.getExecution.mockResolvedValue({ data: { id: 3, status: 'COMPLETED' } });
    proactive.getExecutionHtml.mockResolvedValue({ data: '<p>완료</p>' });

    await renderAndFlush(() => {
      useExecution(7, 3);
      return useExecutionHtml(7, 3);
    }, client);
    expect(proactive.getExecutionHtml).toHaveBeenCalledTimes(1);
  });

  it('내용이 있는 HTML 은 staleTime 동안 다시 읽지 않는다(대조군)', async () => {
    const client = makeClient();
    client.setQueryData(['proactive', 'executions', 7, 3, 'html'], { data: '<p>완료</p>' });
    await renderAndFlush(() => useExecutionHtml(7, 3), client);
    expect(proactive.getExecutionHtml).not.toHaveBeenCalled();
  });
});

describe('지금 실행 무효화 범위 (WD-667 #6)', () => {
  it('실행 목록·작업만 무효화하고 단건 실행·HTML 캐시는 건드리지 않는다', async () => {
    const client = makeClient();
    client.setQueryData(['proactive', 'executions', 7, 'list', { limit: 20, offset: 0 }], []);
    client.setQueryData(['proactive', 'executions', 7, 3], { id: 3, status: 'COMPLETED' });
    client.setQueryData(['proactive', 'executions', 7, 3, 'html'], { data: '<p>x</p>' });
    proactive.executeJob.mockResolvedValue({ data: {} });

    const { view } = await renderAndFlush(() => useExecuteProactiveJob(), client);
    await act(async () => {
      await view.result.current.mutateAsync(7);
    });

    const state = (key: unknown[]) => client.getQueryState(key)?.isInvalidated;
    expect(state(['proactive', 'executions', 7, 'list', { limit: 20, offset: 0 }])).toBe(true);
    expect(state(['proactive', 'executions', 7, 3])).toBe(false);
    expect(state(['proactive', 'executions', 7, 3, 'html'])).toBe(false);
  });
});
