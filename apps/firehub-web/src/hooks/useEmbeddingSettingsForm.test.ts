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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { embeddingApi, type EmbeddingConfigView } from '../api/embedding';
import { getPlatformDbCapabilities } from '../api/platformDb';
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

// 플랫폼 DB 기능 상태(WD-495) — 기본은 PG(둘 다 켜짐). 벡터 꺼짐 케이스에서만 바꾼다.
vi.mock('../api/platformDb', () => ({ getPlatformDbCapabilities: vi.fn() }));
// 기능 상태 조회는 로그인 뒤에만 한다 — 훅 단위 테스트에서는 로그인 상태로 둔다.
vi.mock('./useAuth', () => ({ useAuth: () => ({ isAuthenticated: true }) }));

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
  hosting: 'EXTERNAL',
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
  vi.mocked(getPlatformDbCapabilities).mockResolvedValue({ vectorSearch: true, trigramSearch: true });
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

/** S3 §5-5 — 임베딩 공급자 호스팅 위치. 서버와 같은 규칙(전송 대상이 바뀌면 선언 유지 안 함)으로 화면이 먼저 강등한다. */
describe('useEmbeddingSettingsForm — 호스팅 위치', () => {
  const selfHosted = (): EmbeddingConfigView => ({ ...view('bge-m3'), hosting: 'SELF_HOSTED' });

  it('저장된 호스팅을 시드하고 저장 요청에 명시한다', async () => {
    mocked.getConfig.mockResolvedValue(res(selfHosted()));
    mocked.testConfig.mockResolvedValue(res({ dimension: 1024 }));
    mocked.getImpact.mockResolvedValue(res({ chunks: 0, datasets: 0, rowSearchIndexes: 0 }));
    mocked.saveConfig.mockResolvedValue(res(selfHosted()));
    const { hook } = setup();
    await waitFor(() => expect(hook.result.current.form.hosting).toBe('SELF_HOSTED'));
    expect(hook.result.current.canKeepSavedSelfHosted).toBe(true);

    act(() => hook.result.current.setField({ model: 'bge-m3-v2' }));
    await act(() => hook.result.current.handleSave());
    expect(mocked.saveConfig).toHaveBeenCalledWith(expect.objectContaining({ hosting: 'SELF_HOSTED' }));
  });

  it('Base URL 을 바꾸면 자체 호스팅을 외부로 되돌리고 알린다', async () => {
    mocked.getConfig.mockResolvedValue(res(selfHosted()));
    const { hook } = setup();
    await waitFor(() => expect(hook.result.current.form.hosting).toBe('SELF_HOSTED'));

    act(() => hook.result.current.setField({ baseUrl: 'https://api.openai.com' }));
    expect(hook.result.current.form.hosting).toBe('EXTERNAL');
    expect(hook.result.current.hostingDemoted).toBe(true);
    expect(hook.result.current.canKeepSavedSelfHosted).toBe(false);
  });

  it('끝 슬래시만 다른 Base URL 은 같은 목적지라 유지한다', async () => {
    mocked.getConfig.mockResolvedValue(res(selfHosted()));
    const { hook } = setup();
    await waitFor(() => expect(hook.result.current.form.hosting).toBe('SELF_HOSTED'));

    act(() => hook.result.current.setField({ baseUrl: 'http://h:11434/' }));
    expect(hook.result.current.form.hosting).toBe('SELF_HOSTED');
    expect(hook.result.current.hostingDemoted).toBe(false);
  });

  it('호스팅만 바꿔도 미저장 변경이다', async () => {
    mocked.getConfig.mockResolvedValue(res(view('bge-m3')));
    const { hook } = setup();
    await waitFor(() => expect(hook.result.current.form.model).toBe('bge-m3'));
    act(() => hook.result.current.setField({ hosting: 'SELF_HOSTED' }));
    expect(hook.result.current.hasChanges).toBe(true);
  });
});

// WD-495: 벡터 검색이 꺼진 설치본은 임베딩 탭이 안내만 보인다 — 현황 진행률 폴링(3초)이 계속 돌면 안 된다.
// 현황은 일부러 「진행 중」(RUNNING·미완료)으로 둔다 — 완료 상태면 수정 전에도 한 번 뒤 폴링이 멈춰 공허한 테스트가 된다.
describe('useEmbeddingSettingsForm — 플랫폼 DB 벡터 꺼짐 시 현황 폴링', () => {
  const running = {
    configured: true,
    model: 'bge-m3',
    dimension: 1024,
    datasets: { total: 10, embedded: 2 },
    documentChunks: { total: 100, embedded: 10 },
    job: { status: 'RUNNING', lastError: null, updatedAt: '2026-11-11T00:00:00Z' },
  };

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    mocked.getConfig.mockResolvedValue(res(view('bge-m3')));
    mocked.getStatus.mockResolvedValue(res(running));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('벡터가 꺼지면 현황을 되풀이해 조회하지 않는다', async () => {
    vi.mocked(getPlatformDbCapabilities).mockResolvedValue({ vectorSearch: false, trigramSearch: false });
    const { queryClient, hook } = setup();
    await waitFor(() =>
      expect(queryClient.getQueryData(['platform-db', 'capabilities'])).toEqual({
        vectorSearch: false,
        trigramSearch: false,
      }),
    );
    // 기능 상태가 오기 전(켜짐 간주)에 한 번 나갈 수는 있다. 그 뒤로는 폴링 간격을 여러 번 지나도 늘지 않아야 한다.
    const before = mocked.getStatus.mock.calls.length;
    expect(before).toBeLessThanOrEqual(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(mocked.getStatus.mock.calls.length).toBe(before);
    // 설정 조회·폼은 그대로 동작한다(저장·dirty 흐름은 현황과 무관).
    await waitFor(() => expect(hook.result.current.form.model).toBe('bge-m3'));
    act(() => hook.result.current.setField({ model: 'my-edit' }));
    expect(hook.result.current.hasChanges).toBe(true);
  });

  it('대조: 벡터가 켜진 PG 설치본은 진행 중 현황을 계속 폴링한다', async () => {
    const { hook } = setup();
    await waitFor(() => expect(hook.result.current.status).toBeDefined());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(mocked.getStatus.mock.calls.length).toBeGreaterThanOrEqual(3);
  });
});
