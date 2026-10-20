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
import type { MappingResponse } from '@/types/mapping';

import { useSaveMapping } from './useMapping';
import { mappingUsageQueryKey } from './useOntologyElement';

vi.mock('@/api/mapping', () => ({
  mappingApi: { saveMapping: vi.fn() },
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
