/**
 * useDataStoreCapabilities 테넌트 격리 회귀 테스트 (WD-80)
 *
 * 저장소 기능은 테넌트마다 다르다(PG ↔ Tibero). staleTime 이 무한이라 캐시 키가 테넌트와 무관하면, 로그아웃 후 다른 테넌트로
 * 다시 로그인한 같은 SPA 세션이 이전 테넌트의 기능 판정(검색 탭·GEOMETRY 노출)을 그대로 쓴다. 활성 테넌트가 바뀌면 다시
 * 조회해야 하고, 테넌트가 없으면(로그인 전) 조회하지 않아야 한다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const getCapabilitiesMock = vi.fn();
vi.mock('../../api/dataStore', () => ({
  getDataStoreCapabilities: () => getCapabilitiesMock(),
}));

// 활성 테넌트만 바꿔 가며 렌더한다 — 나머지 인증 상태는 이 훅이 읽지 않는다.
let activeTenantId: number | null = 1;
vi.mock('../useAuth', () => ({
  useAuth: () => ({ activeTenantId }),
}));

import { useDataStoreCapabilities, useDataStoreFeatures } from './useDataStoreCapabilities';

const PG = { backend: 'POSTGRESQL', geometry: true, rowSearch: true };
const TIBERO = { backend: 'TIBERO', geometry: false, rowSearch: false };

describe('useDataStoreCapabilities — 테넌트별 캐시 (WD-80)', () => {
  beforeEach(() => {
    getCapabilitiesMock.mockReset();
    activeTenantId = 1;
  });

  function setup() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    return { client, ...renderHook(() => useDataStoreCapabilities(), { wrapper }) };
  }

  it('활성 테넌트가 바뀌면 다시 조회해 새 테넌트의 기능을 돌려준다', async () => {
    getCapabilitiesMock.mockResolvedValueOnce(PG).mockResolvedValueOnce(TIBERO);
    const { result, rerender, client } = setup();
    await waitFor(() => expect(result.current.data).toEqual(PG));

    // 로그아웃 → 다른 테넌트로 재로그인(페이지 리로드 없음)
    activeTenantId = 2;
    rerender();

    await waitFor(() => expect(result.current.data).toEqual(TIBERO));
    expect(getCapabilitiesMock).toHaveBeenCalledTimes(2);
    client.clear();
  });

  it('활성 테넌트가 없으면 조회하지 않는다', async () => {
    activeTenantId = null;
    const { result, client } = setup();
    await Promise.resolve();
    expect(getCapabilitiesMock).not.toHaveBeenCalled();
    expect(result.current.data).toBeUndefined();
    client.clear();
  });
});

/**
 * useDataStoreFeatures 기본값 규칙 (WD-80)
 *
 * 호출부(ColumnDialog·SchemaBuilder·DatasetDetailPage)가 같은 규칙을 쓰도록 모은 판정이다. GEOMETRY 는 미지원 응답일 때만 숨기고,
 * 행 검색은 조회 중에만 undefined(검색 탭 본문 보류), 실패·로그인 전에는 PG 기본값 true 다.
 */
describe('useDataStoreFeatures — 기능 판정 기본값 (WD-80)', () => {
  beforeEach(() => {
    getCapabilitiesMock.mockReset();
    activeTenantId = 1;
  });

  function setupFeatures() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    return { client, ...renderHook(() => useDataStoreFeatures(), { wrapper }) };
  }

  it('조회 중이면 행 검색은 undefined, GEOMETRY 는 지원으로 둔다', () => {
    getCapabilitiesMock.mockReturnValue(new Promise(() => {}));
    const { result, client } = setupFeatures();
    expect(result.current).toEqual({ geometrySupported: true, rowSearch: undefined });
    client.clear();
  });

  it('조회 실패면 PG 기본값(모두 지원)이다', async () => {
    getCapabilitiesMock.mockRejectedValue(new Error('network'));
    const { result, client } = setupFeatures();
    await waitFor(() => expect(result.current.rowSearch).toBe(true));
    expect(result.current.geometrySupported).toBe(true);
    client.clear();
  });

  it('활성 테넌트가 없으면(조회 안 함) PG 기본값이다', () => {
    activeTenantId = null;
    const { result, client } = setupFeatures();
    expect(result.current).toEqual({ geometrySupported: true, rowSearch: true });
    client.clear();
  });

  it('응답 값을 그대로 따른다 — PG 는 지원, Tibero 는 미지원', async () => {
    getCapabilitiesMock.mockResolvedValueOnce(PG).mockResolvedValueOnce(TIBERO);
    const { result, rerender, client } = setupFeatures();
    await waitFor(() => expect(result.current).toEqual({ geometrySupported: true, rowSearch: true }));
    activeTenantId = 2;
    rerender();
    await waitFor(() => expect(result.current).toEqual({ geometrySupported: false, rowSearch: false }));
    client.clear();
  });
});
