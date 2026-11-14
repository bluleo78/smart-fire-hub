import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';
import {
  appendSearchNotice,
  CAPABILITIES_FAILURE_RETRY_MS,
  CAPABILITIES_SUCCESS_TTL_MS,
  guardSearch,
  isSearchFullyDisabled,
  normalizeCapabilities,
  resetPlatformDbCapabilitiesCache,
  resolveSearchMode,
  SEARCH_DISABLED_NOTICE,
  SEARCH_PAUSED_NOTICE,
} from './platform-db-api.js';

/**
 * platform-db-api.ts 테스트(WD-495).
 * 기능 상태 조회의 프로세스 캐시(성공 5분·실패 30초)·실패 시 켜짐 간주, 모드 전환 규칙과 검색 공통 가드를 검증한다.
 */

const BASE_URL = 'http://localhost:8080/api/v1';
const PATH = '/api/v1/platform-db/capabilities';

describe('platformDbApi.getPlatformDbCapabilities (FireHubApiClient 경유)', () => {
  beforeEach(() => {
    nock.cleanAll();
    resetPlatformDbCapabilitiesCache();
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    nock.cleanAll();
    vi.restoreAllMocks();
  });

  it('Tibero(둘 다 꺼짐) 응답을 그대로 돌려주고 프로세스에 캐시한다 — 다른 사용자 클라이언트도 다시 묻지 않는다', async () => {
    const scope = nock(BASE_URL).get('/platform-db/capabilities').once().reply(200, {
      vectorSearch: false,
      trigramSearch: false,
    });

    const first = await new FireHubApiClient(BASE_URL, 'tok', 1).getPlatformDbCapabilities();
    // 다른 사용자·테넌트로 만든 새 클라이언트 — 설치본 단위 값이라 캐시를 공유해야 한다.
    const second = await new FireHubApiClient(BASE_URL, 'tok', 2, 7).getPlatformDbCapabilities();

    expect(first).toEqual({ vectorSearch: false, trigramSearch: false, vectorStoreAvailable: true });
    expect(second).toEqual(first);
    expect(scope.isDone()).toBe(true);
    expect(nock.pendingMocks()).toHaveLength(0);
  });

  it('동시에 여러 번 물어도 한 번만 조회한다', async () => {
    const scope = nock(BASE_URL).get('/platform-db/capabilities').once().reply(200, {
      vectorSearch: true,
      trigramSearch: false,
    });
    const client = new FireHubApiClient(BASE_URL, 'tok', 1);

    const [a, b] = await Promise.all([client.getPlatformDbCapabilities(), client.getPlatformDbCapabilities()]);

    expect(a).toEqual({ vectorSearch: true, trigramSearch: false, vectorStoreAvailable: true });
    expect(b).toEqual(a);
    expect(scope.isDone()).toBe(true);
  });

  it('조회 실패 시 던지지 않고 모두 켜짐으로 간주하며, 짧은 간격 뒤에는 다시 조회한다', async () => {
    nock(BASE_URL).get('/platform-db/capabilities').reply(500, { message: 'boom' });
    const client = new FireHubApiClient(BASE_URL, 'tok', 1);
    const now = Date.now();
    const clock = vi.spyOn(Date, 'now').mockReturnValue(now);

    // 실패 → 켜짐(PG 기본) — 기존 동작 유지.
    expect(await client.getPlatformDbCapabilities()).toEqual({ vectorSearch: true, trigramSearch: true, vectorStoreAvailable: true });

    // 재시도 간격 안에서는 실패를 기억해 다시 묻지 않는다 — 준비해 둔 응답 목이 소비되지 않아야 한다.
    const retryScope = nock(BASE_URL).get('/platform-db/capabilities').reply(200, {
      vectorSearch: false,
      trigramSearch: false,
    });
    clock.mockReturnValue(now + CAPABILITIES_FAILURE_RETRY_MS - 1);
    expect(await client.getPlatformDbCapabilities()).toEqual({ vectorSearch: true, trigramSearch: true, vectorStoreAvailable: true });
    expect(retryScope.isDone()).toBe(false);

    // 간격이 지나면 다시 조회해 실제 값을 받는다.
    clock.mockReturnValue(now + CAPABILITIES_FAILURE_RETRY_MS + 1);
    expect(await client.getPlatformDbCapabilities()).toEqual({ vectorSearch: false, trigramSearch: false, vectorStoreAvailable: true });
    expect(retryScope.isDone()).toBe(true);
  });

  it('성공 값은 TTL(1분) 동안만 캐시하고, 지나면 다시 조회한다', async () => {
    nock(BASE_URL).get('/platform-db/capabilities').once().reply(200, { vectorSearch: true, trigramSearch: true });
    const client = new FireHubApiClient(BASE_URL, 'tok', 1);
    const now = Date.now();
    const clock = vi.spyOn(Date, 'now').mockReturnValue(now);

    expect(await client.getPlatformDbCapabilities()).toEqual({ vectorSearch: true, trigramSearch: true, vectorStoreAvailable: true });

    // TTL 직전까지는 캐시를 쓴다 — 준비해 둔 다음 응답 목이 소비되지 않아야 한다.
    const refetch = nock(BASE_URL).get('/platform-db/capabilities').reply(200, {
      vectorSearch: false,
      trigramSearch: false,
    });
    clock.mockReturnValue(now + CAPABILITIES_SUCCESS_TTL_MS - 1);
    expect(await client.getPlatformDbCapabilities()).toEqual({ vectorSearch: true, trigramSearch: true, vectorStoreAvailable: true });
    expect(refetch.isDone()).toBe(false);

    // TTL 이 지나면 다시 조회해 바뀐 값을 따라간다.
    clock.mockReturnValue(now + CAPABILITIES_SUCCESS_TTL_MS + 1);
    expect(await client.getPlatformDbCapabilities()).toEqual({ vectorSearch: false, trigramSearch: false, vectorStoreAvailable: true });
    expect(refetch.isDone()).toBe(true);
  });

  it('요청 경로는 /api/v1/platform-db/capabilities 이고 Internal 토큰·대행 헤더를 싣는다', async () => {
    const scope = nock('http://localhost:8080')
      .get(PATH)
      .matchHeader('authorization', 'Internal tok')
      .reply(200, { vectorSearch: true, trigramSearch: true });

    await new FireHubApiClient(BASE_URL, 'tok', 1).getPlatformDbCapabilities();

    expect(scope.isDone()).toBe(true);
  });
});

describe('platformDbApi 저장소 일시 중단 캐시 (WD-724)', () => {
  beforeEach(() => resetPlatformDbCapabilitiesCache());
  afterEach(() => {
    nock.cleanAll();
    vi.restoreAllMocks();
    resetPlatformDbCapabilitiesCache();
  });

  it('일시 중단 응답은 짧게만(실패와 같은 간격) 기억해 복구를 빨리 따라간다', async () => {
    nock(BASE_URL)
      .get('/platform-db/capabilities')
      .once()
      .reply(200, { vectorSearch: true, trigramSearch: true, vectorStoreAvailable: false });
    const client = new FireHubApiClient(BASE_URL, 'tok', 1);
    const now = Date.now();
    const clock = vi.spyOn(Date, 'now').mockReturnValue(now);
    expect((await client.getPlatformDbCapabilities()).vectorStoreAvailable).toBe(false);

    const recovered = nock(BASE_URL)
      .get('/platform-db/capabilities')
      .reply(200, { vectorSearch: true, trigramSearch: true, vectorStoreAvailable: true });
    clock.mockReturnValue(now + CAPABILITIES_FAILURE_RETRY_MS + 1);
    expect((await client.getPlatformDbCapabilities()).vectorStoreAvailable).toBe(true);
    expect(recovered.isDone()).toBe(true);
  });
});

describe('normalizeCapabilities', () => {
  it('명시적 false 만 꺼짐으로 본다 — 값이 빠졌거나 이상하면 켜짐(PG 기본)', () => {
    expect(normalizeCapabilities({ vectorSearch: false, trigramSearch: true })).toEqual({
      vectorSearch: false,
      trigramSearch: true,
      vectorStoreAvailable: true,
    });
    // 저장소 가용성도 명시적 false 만 일시 중단(WD-724)
    expect(normalizeCapabilities({ vectorSearch: true, trigramSearch: true, vectorStoreAvailable: false })).toEqual({
      vectorSearch: true,
      trigramSearch: true,
      vectorStoreAvailable: false,
    });
    expect(normalizeCapabilities({ mocked: true })).toEqual({ vectorSearch: true, trigramSearch: true, vectorStoreAvailable: true });
    expect(normalizeCapabilities(null)).toEqual({ vectorSearch: true, trigramSearch: true, vectorStoreAvailable: true });
    expect(normalizeCapabilities('x')).toEqual({ vectorSearch: true, trigramSearch: true, vectorStoreAvailable: true });
  });
});

describe('isSearchFullyDisabled', () => {
  it('둘 다 꺼졌을 때만 true', () => {
    expect(isSearchFullyDisabled({ vectorSearch: false, trigramSearch: false })).toBe(true);
    expect(isSearchFullyDisabled({ vectorSearch: false, trigramSearch: true })).toBe(false);
    expect(isSearchFullyDisabled({ vectorSearch: true, trigramSearch: false })).toBe(false);
    expect(isSearchFullyDisabled({ vectorSearch: true, trigramSearch: true })).toBe(false);
  });

  it('값이 빠진 객체(목 등)는 켜짐으로 본다', () => {
    expect(isSearchFullyDisabled({ mocked: true } as never)).toBe(false);
  });
});

describe('resolveSearchMode', () => {
  const on = { vectorSearch: true, trigramSearch: true };
  const vectorOff = { vectorSearch: false, trigramSearch: true };
  const trigramOff = { vectorSearch: true, trigramSearch: false };

  it('둘 다 켜져 있으면 요청 모드를 그대로 둔다(생략도 생략 그대로)', () => {
    expect(resolveSearchMode(undefined, on)).toEqual({ mode: undefined });
    expect(resolveSearchMode('SEMANTIC', on)).toEqual({ mode: 'SEMANTIC' });
  });

  it('벡터 꺼짐: 생략·HYBRID·SEMANTIC 은 KEYWORD 로 바꾸고 안내한다, KEYWORD 는 그대로', () => {
    for (const m of [undefined, 'HYBRID', 'SEMANTIC'] as const) {
      const r = resolveSearchMode(m, vectorOff);
      expect(r.mode).toBe('KEYWORD');
      expect(r.notice).toContain('벡터');
    }
    expect(resolveSearchMode('KEYWORD', vectorOff)).toEqual({ mode: 'KEYWORD' });
  });

  it('트라이그램 꺼짐: 생략·HYBRID·KEYWORD 는 SEMANTIC 으로 바꾸고 안내한다, SEMANTIC 은 그대로', () => {
    for (const m of [undefined, 'HYBRID', 'KEYWORD'] as const) {
      const r = resolveSearchMode(m, trigramOff);
      expect(r.mode).toBe('SEMANTIC');
      expect(r.notice).toContain('키워드 검색이 꺼져');
    }
    expect(resolveSearchMode('SEMANTIC', trigramOff)).toEqual({ mode: 'SEMANTIC' });
  });
});

describe('guardSearch', () => {
  const source = (vectorSearch: boolean, trigramSearch: boolean) => async () => ({ vectorSearch, trigramSearch });

  it('둘 다 꺼짐: disabled 와 검색 꺼짐 안내문', async () => {
    expect(await guardSearch(source(false, false), 'HYBRID')).toEqual({
      disabled: true,
      notice: SEARCH_DISABLED_NOTICE,
    });
  });

  it('하나만 꺼짐: 켜진 단일 모드와 전환 안내', async () => {
    const vOff = await guardSearch(source(false, true), undefined);
    expect(vOff).toMatchObject({ disabled: false, mode: 'KEYWORD' });
    expect(vOff.notice).toContain('벡터');
    const tOff = await guardSearch(source(true, false), 'KEYWORD');
    expect(tOff).toMatchObject({ disabled: false, mode: 'SEMANTIC' });
    expect(tOff.notice).toContain('키워드 검색이 꺼져');
  });

  it('저장소 일시 중단(WD-724): 지원 여부와 무관하게 disabled 와 일시 중단 안내문', async () => {
    const down = async () => ({ vectorSearch: true, trigramSearch: true, vectorStoreAvailable: false });
    expect(await guardSearch(down, 'HYBRID')).toEqual({ disabled: true, notice: SEARCH_PAUSED_NOTICE });
    // 설치본 꺼짐이 먼저다 — 둘 다 꺼진 설치본은 저장소 상태와 상관없이 꺼짐 안내
    const offAndDown = async () => ({ vectorSearch: false, trigramSearch: false, vectorStoreAvailable: false });
    expect(await guardSearch(offAndDown, 'HYBRID')).toEqual({ disabled: true, notice: SEARCH_DISABLED_NOTICE });
  });

  it('둘 다 켜짐: 요청 모드 그대로, 안내 없음', async () => {
    expect(await guardSearch(source(true, true), undefined)).toEqual({ disabled: false, mode: undefined });
    expect(await guardSearch(source(true, true), 'SEMANTIC')).toEqual({ disabled: false, mode: 'SEMANTIC' });
  });
});

describe('appendSearchNotice', () => {
  const base = { content: [{ type: 'text' as const, text: '[]' }] };

  it('안내가 있으면 첫 블록은 두고 끝에 텍스트 블록을 붙인다', () => {
    expect(appendSearchNotice(base, '바꿨습니다').content).toEqual([
      { type: 'text', text: '[]' },
      { type: 'text', text: '바꿨습니다' },
    ]);
  });

  it('안내가 없으면 결과를 그대로 돌려준다', () => {
    expect(appendSearchNotice(base, undefined)).toBe(base);
  });
});
