/**
 * useEmbeddingSettingsForm 단위 테스트(#713).
 *
 * 겨냥하는 계약:
 * - 서버 설정은 <b>최초 성공 로드 때 한 번만</b> 폼에 시드한다. 백그라운드 재조회(창 포커스·무효화)가
 *   다른 값을 돌려줘도 편집 중인 입력과 dirty 를 덮어쓰지 않는다.
 * - 조회가 실패한 뒤 재시도로 처음 성공한 로드는 시드돼야 한다(실패를 "이미 시드함"으로 세지 않는다).
 *
 * 재조회 응답은 일부러 첫 응답과 <b>다른 값</b>으로 둔다 — 같은 값이면 react-query structural sharing 이
 * 같은 참조를 돌려줘 효과가 아예 돌지 않으므로, 수정 전 코드에서도 통과하는 공허한 테스트가 된다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { embeddingApi, type EmbeddingConfigView } from '../api/embedding';
import { EMBEDDING_CONFIG_KEY } from './queries/useEmbeddingSettings';
import { useEmbeddingSettingsForm } from './useEmbeddingSettingsForm';

vi.mock('../api/embedding', () => ({
  embeddingApi: {
    getStatus: vi.fn(),
    reindexAll: vi.fn(),
    getConfig: vi.fn(),
    testConfig: vi.fn(),
    getImpact: vi.fn(),
    saveConfig: vi.fn(),
  },
}));

// 토스트는 이 훅의 계약이 아니다 — jsdom 에서 sonner 가 DOM 을 건드리지 않게 막는다.
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

const mocked = vi.mocked(embeddingApi);

const view = (model: string, baseUrl = 'http://h:11434'): EmbeddingConfigView => ({
  configured: true,
  provider: 'OLLAMA',
  model,
  baseUrl,
  dimension: 1024,
  apiKeyMasked: '',
});

// axios 응답 모양만 흉내 낸다(훅은 .data 만 읽는다).
const res = <T,>(data: T) => ({ data }) as never;

function setup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client: queryClient }, children);
  const hook = renderHook(() => useEmbeddingSettingsForm(), { wrapper });
  return { queryClient, hook };
}

beforeEach(() => {
  vi.clearAllMocks();
  mocked.getStatus.mockResolvedValue(
    res({
      configured: true,
      model: 'bge-m3',
      dimension: 1024,
      datasets: { total: 0, embedded: 0 },
      documentChunks: { total: 0, embedded: 0 },
      job: null,
    }),
  );
});

describe('useEmbeddingSettingsForm — 서버 설정 시드', () => {
  it('백그라운드 재조회가 다른 값을 돌려줘도 편집 중인 입력과 dirty 를 유지한다', async () => {
    mocked.getConfig
      .mockResolvedValueOnce(res(view('bge-m3')))
      .mockResolvedValue(res(view('server-changed', 'http://other:11434')));
    const { queryClient, hook } = setup();
    await waitFor(() => expect(hook.result.current.form.model).toBe('bge-m3'));

    act(() => hook.result.current.setField({ model: 'my-edit' }));
    expect(hook.result.current.hasChanges).toBe(true);

    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: EMBEDDING_CONFIG_KEY });
    });
    // 재조회는 실제로 새 값을 받았다(테스트가 공허하지 않다는 증거).
    await waitFor(() => expect(hook.result.current.config?.model).toBe('server-changed'));

    expect(hook.result.current.form.model).toBe('my-edit');
    expect(hook.result.current.form.baseUrl).toBe('http://h:11434');
    expect(hook.result.current.hasChanges).toBe(true);
  });

  it('조회 실패 뒤 재시도로 처음 성공한 로드는 폼에 시드한다', async () => {
    mocked.getConfig.mockRejectedValueOnce(new Error('boom')).mockResolvedValue(res(view('bge-m3')));
    const { hook } = setup();
    await waitFor(() => expect(hook.result.current.isError).toBe(true));

    act(() => hook.result.current.retryLoad());

    await waitFor(() => expect(hook.result.current.form.model).toBe('bge-m3'));
    expect(hook.result.current.hasChanges).toBe(false);
  });
});
