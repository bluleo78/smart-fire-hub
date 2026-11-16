/**
 * useAIChat — 화면 컨텍스트에 캐시된 이름 붙이기 (WD-759).
 *
 * 상세 화면 훅이 이미 채운 TanStack Query 캐시(훅과 같은 키 함수로 채움)에서만 이름을 읽어
 * streamAIChat 의 screenContext 로 넘기는지, 캐시에 없으면 예전처럼 ID 만 넘기는지를 검증한다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { aiApi, streamAIChat } from '@/api/ai';

import { useAIChat } from './useAIChat';
import { dashboardDetailKey } from './useAnalytics';
import { datasetDetailKey } from './useDatasets';
import { pipelineDetailKey } from './usePipelines';

vi.mock('@/api/ai', () => ({
  aiApi: { createSession: vi.fn(), cancelChat: vi.fn() },
  streamAIChat: vi.fn(),
}));
vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

let qc: QueryClient;

function wrapper({ children }: { children: React.ReactNode }) {
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

/** 경로를 바꾸고 메시지를 하나 보낸 뒤 streamAIChat 에 넘긴 screenContext(8번째 인자)를 돌려준다. */
async function sendFrom(path: string): Promise<string | undefined> {
  window.history.pushState({}, '', path);
  const { result } = renderHook(() => useAIChat(), { wrapper });
  await act(async () => {
    await result.current.sendMessage('질문');
  });
  const call = vi.mocked(streamAIChat).mock.calls.at(-1);
  return call?.[7];
}

beforeEach(() => {
  vi.clearAllMocks();
  qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.mocked(aiApi.createSession).mockResolvedValue({} as never);
  vi.mocked(streamAIChat).mockImplementation(() => new AbortController());
});

afterEach(() => {
  window.history.pushState({}, '', '/');
});

describe('useAIChat — 화면 컨텍스트 이름 (WD-759)', () => {
  // 상세 훅과 같은 키 함수로 캐시를 채우고, 그 화면에서 보낸 질문의 screenContext 에 이름이 붙는지 본다
  it.each([
    ['데이터셋', datasetDetailKey(19), '/data/datasets/19', '[현재 화면] 데이터셋 상세 (ID: 19, 이름: 「티베로 회사 연혁」)', '티베로 회사 연혁'],
    ['파이프라인', pipelineDetailKey(7), '/pipelines/7', '[현재 화면] 파이프라인 편집 (ID: 7, 이름: 「매일 적재」)', '매일 적재'],
    ['대시보드', dashboardDetailKey(3), '/analytics/dashboards/3', '[현재 화면] 대시보드 편집 (ID: 3, 이름: 「운영 현황」)', '운영 현황'],
  ] as const)('%s 상세 캐시의 이름을 붙인다', async (_label, key, path, expected, name) => {
    qc.setQueryData(key, { name });
    expect(await sendFrom(path)).toBe(expected);
  });

  it('캐시에 없으면 예전처럼 ID 만 넘긴다(새 요청 없이)', async () => {
    // 다른 id 의 캐시는 섞이지 않는다
    qc.setQueryData(datasetDetailKey(20), { id: 20, name: '다른 데이터셋' });
    expect(await sendFrom('/data/datasets/19')).toBe('[현재 화면] 데이터셋 상세 (ID: 19)');
    expect(qc.getQueryCache().find({ queryKey: datasetDetailKey(19) })).toBeUndefined();
  });
});
