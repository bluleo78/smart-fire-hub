/**
 * 공유 토큰 갱신(refreshAccessToken)·fetch 래퍼(authedFetch) 단위 테스트 (WD-631)
 *
 * 인터셉터와 AI 채팅이 같은 갱신 큐를 쓰므로, 갱신 결과가 호출자에 따라 섞이지 않는지 본다 —
 * 특히 테넌트 강등은 오류가 아니라 `{ demoted: true }` 결과로 모든 대기자에게 가서, 각 호출자가 자기 오류로 실패해야 한다.
 */
import axios from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { authedFetch, getAccessToken, refreshAccessToken, setAccessToken, TokenRefreshError } from './client';

vi.mock('./refresh-lock', () => ({
  withCrossTabRefreshLock: <T,>(fn: () => Promise<T>) => fn(),
}));
vi.mock('./tenant-session', () => ({ publishTenantSession: vi.fn() }));

/** 지연 응답을 손으로 풀 수 있는 refresh 목 */
function deferredRefresh() {
  let resolve!: (v: unknown) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  vi.spyOn(axios, 'post').mockReturnValue(promise as never);
  return { resolve, reject };
}

describe('refreshAccessToken (WD-631)', () => {
  const replace = vi.fn();

  beforeEach(() => {
    setAccessToken('old');
    vi.stubGlobal('location', { ...window.location, replace });
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    replace.mockReset();
  });

  it('동시 호출은 refresh 를 한 번만 보내고 같은 새 토큰을 받는다', async () => {
    const d = deferredRefresh();
    const a = refreshAccessToken();
    const b = refreshAccessToken();
    d.resolve({ data: { accessToken: 'new', activeTenantId: 1, memberships: [] } });

    await expect(a).resolves.toEqual({ token: 'new' });
    await expect(b).resolves.toEqual({ token: 'new' });
    expect(axios.post).toHaveBeenCalledTimes(1);
    expect(getAccessToken()).toBe('new');
  });

  it('테넌트 강등이면 시작한 쪽·대기한 쪽 모두 오류가 아니라 { demoted: true } 를 받고 세션은 끝내지 않는다', async () => {
    const d = deferredRefresh();
    const a = refreshAccessToken();
    const b = refreshAccessToken();
    d.resolve({ data: { accessToken: 'demoted', activeTenantId: null, memberships: [] } });

    await expect(a).resolves.toEqual({ demoted: true });
    await expect(b).resolves.toEqual({ demoted: true });
    expect(replace).not.toHaveBeenCalled();
  });

  it('갱신 실패면 모두 그 오류로 실패하고 세션을 끝낸다(로그인 화면으로)', async () => {
    const d = deferredRefresh();
    const a = refreshAccessToken();
    const b = refreshAccessToken();
    const failure = new Error('refresh 401');
    d.reject(failure);

    await expect(a).rejects.toBe(failure);
    await expect(b).rejects.toBe(failure);
    expect(replace).toHaveBeenCalledWith('/login');
    expect(getAccessToken()).toBeNull();
  });
});

/** 본문 읽기(text)를 추적할 수 있는 지정 상태 응답 */
function fakeResponse(status: number) {
  const text = vi.fn(async () => '{}');
  return { response: { ok: status < 400, status, text } as unknown as Response, text };
}

describe('authedFetch (WD-631)', () => {
  const fetchMock = vi.fn();
  const replace = vi.fn();

  beforeEach(() => {
    setAccessToken('old');
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('location', { ...window.location, replace });
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    fetchMock.mockReset();
    replace.mockReset();
  });

  /** refresh 응답을 정한다 */
  function refreshReturns(activeTenantId: number | null) {
    vi.spyOn(axios, 'post').mockResolvedValue({ data: { accessToken: 'new', activeTenantId, memberships: [] } } as never);
  }

  it('401 이 아니면 첫 응답을 그대로 돌려주고 갱신하지 않는다 — 기존 헤더에 토큰을 덧붙인다', async () => {
    const ok = fakeResponse(200);
    fetchMock.mockResolvedValueOnce(ok.response);
    const post = vi.spyOn(axios, 'post');

    const result = await authedFetch('/x', { method: 'POST', headers: { 'Content-Type': 'application/json' } });

    expect(result).toEqual({ response: ok.response, demoted: false });
    expect(post).not.toHaveBeenCalled();
    const headers = fetchMock.mock.calls[0][1].headers as Headers;
    expect(headers.get('Authorization')).toBe('Bearer old');
    expect(headers.get('Content-Type')).toBe('application/json');
  });

  it('401 → 갱신 → 새 토큰·같은 본문·같은 신호로 한 번 다시 보내고, 첫 401 본문은 읽지 않는다', async () => {
    const first = fakeResponse(401);
    const ok = fakeResponse(200);
    fetchMock.mockResolvedValueOnce(first.response).mockResolvedValueOnce(ok.response);
    refreshReturns(1);
    const signal = new AbortController().signal;

    const result = await authedFetch('/x', { method: 'POST', body: 'b', signal });

    expect(result).toEqual({ response: ok.response, demoted: false });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect((fetchMock.mock.calls[1][1].headers as Headers).get('Authorization')).toBe('Bearer new');
    expect(fetchMock.mock.calls[1][1].body).toBe('b');
    expect(fetchMock.mock.calls[1][1].signal).toBe(signal);
    expect(first.text).not.toHaveBeenCalled();
  });

  it('재시도도 401 이면 그 응답을 돌려주고 더 보내지 않는다', async () => {
    const second = fakeResponse(401);
    fetchMock.mockResolvedValueOnce(fakeResponse(401).response).mockResolvedValueOnce(second.response);
    refreshReturns(1);

    const result = await authedFetch('/x');

    expect(result).toEqual({ response: second.response, demoted: false });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(axios.post).toHaveBeenCalledTimes(1);
  });

  it('테넌트 강등이면 다시 보내지 않고 첫 401 응답과 demoted 를 돌려준다', async () => {
    const first = fakeResponse(401);
    fetchMock.mockResolvedValueOnce(first.response);
    refreshReturns(null);

    const result = await authedFetch('/x');

    expect(result).toEqual({ response: first.response, demoted: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('갱신 실패는 TokenRefreshError 로 감싸 reject 하고, 네트워크 실패는 그대로 reject 한다', async () => {
    fetchMock.mockResolvedValueOnce(fakeResponse(401).response);
    vi.spyOn(axios, 'post').mockRejectedValue(new Error('refresh 401'));
    await expect(authedFetch('/x')).rejects.toBeInstanceOf(TokenRefreshError);

    const networkError = new TypeError('Failed to fetch');
    fetchMock.mockRejectedValueOnce(networkError);
    await expect(authedFetch('/x')).rejects.toBe(networkError);
  });
});
