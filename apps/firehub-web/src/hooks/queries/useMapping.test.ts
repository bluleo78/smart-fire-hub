/**
 * WD-129: 매핑 저장이 지식 모델 삭제 확인 창의 매핑 사용처 캐시를 무효화하는지 검증한다.
 * 사용자가 "매핑에서 먼저 빼 주세요" 안내를 따라 매핑을 고쳐 저장한 뒤 편집기로 돌아왔을 때, 낡은 "사용 중" 캐시가
 * 확인 버튼을 계속 막으면 되돌릴 길이 없다(서버 409 와 달리 화면이 스스로 풀지 못한다).
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import React from 'react';
import { describe, expect, it, vi } from 'vitest';

import { mappingApi } from '@/api/mapping';
import type { BindOntologyResponse, MappingResponse } from '@/types/mapping';

import { useBindOntology, useSaveMapping } from './useMapping';
import { mappingUsageQueryKey } from './useOntologyElement';

vi.mock('@/api/mapping', () => ({
  mappingApi: { saveMapping: vi.fn(), bindOntology: vi.fn() },
}));

describe('useSaveMapping', () => {
  it('저장에 성공하면 모든 온톨로지의 매핑 사용처 캐시를 무효화한다', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    // 바인딩된 온톨로지 id 를 저장 훅은 모른다 — 서로 다른 두 온톨로지의 사용처가 모두 무효화돼야 한다.
    queryClient.setQueryData(mappingUsageQueryKey(1), { entityTypes: {}, properties: {}, relations: {} });
    queryClient.setQueryData(mappingUsageQueryKey(2), { entityTypes: {}, properties: {}, relations: {} });
    const saved: MappingResponse = {
      datasetId: 5,
      ontologyId: 1,
      spec: { entities: [], relations: [] },
      status: 'draft',
    };
    vi.mocked(mappingApi.saveMapping).mockResolvedValue({ data: saved } as Awaited<
      ReturnType<typeof mappingApi.saveMapping>
    >);

    const wrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: queryClient }, children);
    const { result } = renderHook(() => useSaveMapping(5), { wrapper });

    await act(async () => {
      await result.current.mutateAsync({ entities: [], relations: [] });
    });

    expect(queryClient.getQueryState(mappingUsageQueryKey(1))?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(mappingUsageQueryKey(2))?.isInvalidated).toBe(true);
  });
});

/**
 * WD-184: 다른 모델로 다시 연결하면 백엔드가 이전 모델 기준 매핑을 지운다. 캐시된 옛 매핑이 새 모델 아래에 남아 보이지 않게
 * 매핑·사용처 캐시를 무효화하고, 결과를 돌려주어 호출부가 삭제 안내를 띄울 수 있어야 한다.
 */
describe('useBindOntology', () => {
  function setup(response: BindOntologyResponse | undefined) {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(['datasets', 5, 'ontology-binding'], { datasetId: 5, ontologyId: 1 });
    queryClient.setQueryData(['datasets', 5, 'mapping'], { datasetId: 5, ontologyId: 1 });
    queryClient.setQueryData(mappingUsageQueryKey(1), { entityTypes: {}, properties: {}, relations: {} });
    vi.mocked(mappingApi.bindOntology).mockResolvedValue({ data: response } as Awaited<
      ReturnType<typeof mappingApi.bindOntology>
    >);
    const wrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: queryClient }, children);
    const { result } = renderHook(() => useBindOntology(5), { wrapper });
    return { queryClient, result };
  }

  it('매핑이 지워지면 바인딩·매핑·사용처 캐시를 모두 무효화하고 결과를 돌려준다', async () => {
    const response: BindOntologyResponse = {
      datasetId: 5, ontologyId: 2, mappingRemoved: true, previousOntologyId: 1, previousOntologyName: '건물',
    };
    const { queryClient, result } = setup(response);
    let returned: BindOntologyResponse | null = null;
    await act(async () => {
      returned = await result.current.mutateAsync(2);
    });
    expect(returned).toEqual(response);
    expect(queryClient.getQueryState(['datasets', 5, 'ontology-binding'])?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(['datasets', 5, 'mapping'])?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(mappingUsageQueryKey(1))?.isInvalidated).toBe(true);
  });

  it('지운 매핑이 없으면 매핑 캐시는 그대로 두고, 본문이 없으면 null 을 돌려준다', async () => {
    const { queryClient, result } = setup(undefined);
    let returned: BindOntologyResponse | null | undefined;
    await act(async () => {
      returned = await result.current.mutateAsync(1);
    });
    expect(returned).toBeNull();
    expect(queryClient.getQueryState(['datasets', 5, 'ontology-binding'])?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(['datasets', 5, 'mapping'])?.isInvalidated).toBe(false);
  });
});
