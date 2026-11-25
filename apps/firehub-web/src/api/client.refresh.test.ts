/**
 * 공유 토큰 갱신(refreshAccessToken)·fetch 래퍼(authedFetch) 단위 테스트 (WD-631)
 *
 * 인터셉터와 AI 채팅이 같은 갱신 큐를 쓰므로, 갱신 결과가 호출자에 따라 섞이지 않는지 본다 —
 * 특히 테넌트 강등은 오류가 아니라 `{ demoted: true }` 결과로 모든 대기자에게 가서, 각 호출자가 자기 오류로 실패해야 한다.
 */
import axios from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { UserSwitchedError } from '../lib/api-error';
import { SESSION_EXPIRED_NOTICE, takeLoginNotice } from '../lib/login-notice';
import { hasActiveLeaveGuard } from '../lib/unsaved-changes-guard-registry';
import { authedFetch, claimUserLogout, clearSessionExpiredError, endSessionAndReloadToLogin, getAccessToken, isOtherSessionUser, readTokenSubject, refreshAccessToken, refreshAccessTokenBestEffort, resetNoticeOwnerForTest, resetSessionUserForTest, setAccessToken, TokenRefreshError } from './client';
import { publishTenantSession } from './tenant-session';

vi.mock('./refresh-lock', () => ({
  withCrossTabRefreshLock: <T,>(fn: () => Promise<T>) => fn(),
}));
vi.mock('../lib/unsaved-changes-guard-registry', () => ({ hasActiveLeaveGuard: vi.fn(() => false) }));
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

/** isUnauthorizedError 가 401 로 판정하는 모양(axios 오류 + response.status 401)의 refresh 실패 — 만료 표식(WD-1082)을 켜는 오류. */
function expiredRefreshError() {
  return Object.assign(new Error('refresh 401'), {
    isAxiosError: true,
    response: { status: 401 },
  });
}

/** header.<base64url(JSON{sub})>.sig 모양의 JWT (WD-1122) */
function jwt(sub: unknown) {
  const payload = btoa(JSON.stringify({ sub })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `header.${payload}.sig`;
}

describe('refreshAccessToken (WD-631)', () => {
  const replace = vi.fn();

  beforeEach(() => {
    resetSessionUserForTest();
    vi.mocked(hasActiveLeaveGuard).mockReturnValue(true);
    vi.mocked(publishTenantSession).mockClear();
    setAccessToken('old');
    resetNoticeOwnerForTest();
    clearSessionExpiredError();
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

  it('401 만료로 끝난 뒤의 갱신은 같은 오류를 그대로 거절하고 갱신 요청을 다시 보내지 않는다(WD-1082)', async () => {
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post').mockRejectedValue(expired);

    await expect(refreshAccessToken()).rejects.toBe(expired);
    // 표식이 남아 두 번째 호출은 HTTP 없이 저장한 오류만 받는다
    await expect(refreshAccessToken()).rejects.toBe(expired);
    expect(axios.post).toHaveBeenCalledTimes(1);
  });

  it('값이 있는 토큰을 다시 저장하면 만료 표식이 풀려 다시 갱신을 보낼 수 있다(WD-1082)', async () => {
    // 401 만료로 끝나게 해 표식을 켠다
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post')
      .mockRejectedValueOnce(expired)
      .mockResolvedValue({ data: { accessToken: 'new', activeTenantId: 1, memberships: [] } } as never);

    await expect(refreshAccessToken()).rejects.toBe(expired);
    await expect(refreshAccessToken()).rejects.toBe(expired);
    expect(axios.post).toHaveBeenCalledTimes(1);

    // 값이 있는 토큰이 저장되면 표식이 풀려 다시 갱신할 수 있어야 한다 — 로그인·회복이 갱신을 영구히 막지 않게.
    // clearSessionExpiredError() 를 부르지 않는다 — setAccessToken(<값>) 이 표식을 비우는 계약 자체를 시험한다(WD-1082).
    setAccessToken('recovered');

    await expect(refreshAccessToken()).resolves.toEqual({ token: 'new' });
    expect(axios.post).toHaveBeenCalledTimes(2);
  });

  it('네트워크 오류로 실패한 갱신은 만료 표식을 켜지 않는다 — 다음 갱신은 다시 요청을 보낸다(WD-1082)', async () => {
    vi.spyOn(axios, 'post')
      .mockRejectedValueOnce(new TypeError('network'))
      .mockResolvedValue({ data: { accessToken: 'new', activeTenantId: 1, memberships: [] } } as never);

    await expect(refreshAccessToken()).rejects.toBeInstanceOf(TypeError);
    await expect(refreshAccessToken()).resolves.toEqual({ token: 'new' });
    expect(axios.post).toHaveBeenCalledTimes(2);
  });

  it('다른 탭이 다시 로그인해 표식을 비우면 다시 HTTP 갱신을 보낼 수 있다(WD-1082 반려)', async () => {
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post')
      .mockRejectedValueOnce(expired)
      .mockResolvedValue({ data: { accessToken: 'new', activeTenantId: 1, memberships: [] } } as never);

    // 401 만료로 끝나게 해 표식을 켠다
    await expect(refreshAccessToken()).rejects.toBe(expired);
    await expect(refreshAccessToken()).rejects.toBe(expired);
    expect(axios.post).toHaveBeenCalledTimes(1);

    // 다른 탭이 다시 로그인하면 AuthContext 의 storage 핸들러가 이 표식을 비운다 —
    // 비워지지 않으면 이 탭은 공유 refresh 쿠키로 되살릴 수 있는 세션까지 영구히 갱신하지 못한다(WD-1082 반려)
    clearSessionExpiredError();

    await expect(refreshAccessToken()).resolves.toEqual({ token: 'new' });
    expect(axios.post).toHaveBeenCalledTimes(2);
  });

  it('만료 표식은 HTTP 갱신만 막고, 화면을 옮긴 뒤의 요청에만 로그인 이동을 다시 건다(WD-1082 반려)', async () => {
    takeLoginNotice(); // 앞 시험에 소비되지 않고 남은 안내를 비운다
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post').mockRejectedValue(expired);
    vi.stubGlobal('location', { pathname: '/data/datasets', replace });

    // 첫 갱신 401 — 표식을 켜고 지금 화면에서 로그인 화면으로 이동한다
    await expect(refreshAccessToken()).rejects.toBe(expired);
    expect(axios.post).toHaveBeenCalledTimes(1);
    expect(replace).toHaveBeenCalledTimes(1);
    expect(replace).toHaveBeenLastCalledWith('/login');

    // 같은 화면의 배경 요청 — HTTP 갱신도 하지 않고 이동도 다시 하지 않는다(떠나기 확인 창을 되풀이해 띄우지 않는다)
    await expect(refreshAccessToken()).rejects.toBe(expired);
    expect(axios.post).toHaveBeenCalledTimes(1);
    expect(replace).toHaveBeenCalledTimes(1);

    // 사용자가 화면을 옮긴 뒤의 요청 — 갱신은 막되 로그인 이동은 다시 한다(가드를 푼 탭이 죽은 화면에 남지 않는다)
    vi.stubGlobal('location', { pathname: '/pipelines', replace });
    await expect(refreshAccessToken()).rejects.toBe(expired);
    expect(axios.post).toHaveBeenCalledTimes(1);
    expect(replace).toHaveBeenCalledTimes(2);
    expect(replace).toHaveBeenLastCalledWith('/login');

    // 안내는 첫 종료 때 맡긴 만료 안내가 그대로다 — 되풀이 이동이 안내를 바꾸지 않는다
    expect(takeLoginNotice()).toBe(SESSION_EXPIRED_NOTICE);
  });

  describe('WD-1122', () => {
    const okResponse = (token: string) => ({ data: { accessToken: token, activeTenantId: 1, memberships: [] } }) as never;

    it('readTokenSubject — sub 를 base64url 로 읽고 못 읽으면 null', () => {
      expect(readTokenSubject(jwt('42'))).toBe('42');
      // base64url 문자(-, _)가 나오는 payload — '???>>>' 는 표준 base64 로 '+' '/' 가 든다
      expect(readTokenSubject(jwt('???>>>~~~'))).toBe('???>>>~~~');
      expect(readTokenSubject('mock-jwt-access-token')).toBeNull();
      expect(readTokenSubject('a.!!!.b')).toBeNull();
      expect(readTokenSubject(jwt(42))).toBeNull();
      expect(readTokenSubject(jwt(undefined))).toBeNull();
    });

    it('다른 사용자의 토큰으로 갱신되면 새 토큰을 쓰지 않고 / 로 하드 이동한다', async () => {
      localStorage.setItem('hasSession', 'true');
      setAccessToken(jwt('1'));
      const d = deferredRefresh();
      const a = refreshAccessToken();
      const b = refreshAccessToken();
      d.resolve(okResponse(jwt('2')));

      await expect(a).rejects.toBeInstanceOf(UserSwitchedError);
      await expect(b).rejects.toBeInstanceOf(UserSwitchedError);
      expect(getAccessToken()).toBeNull();
      expect(replace).toHaveBeenCalledTimes(1);
      expect(replace).toHaveBeenCalledWith('/');
      expect(localStorage.getItem('hasSession')).toBe('true');
      expect(publishTenantSession).not.toHaveBeenCalled();
      localStorage.removeItem('hasSession');
    });

    it('같은 사용자의 토큰이면 그대로 되살아난다', async () => {
      setAccessToken(jwt('1'));
      vi.spyOn(axios, 'post').mockResolvedValue(okResponse(jwt('1')));
      await expect(refreshAccessToken()).resolves.toEqual({ token: jwt('1') });
      expect(replace).not.toHaveBeenCalled();
    });

    it('디코드 못 하는 토큰이면 비교하지 않는다', async () => {
      setAccessToken('mock-a');
      vi.spyOn(axios, 'post').mockResolvedValue(okResponse(jwt('2')));
      await expect(refreshAccessToken()).resolves.toEqual({ token: jwt('2') });
      expect(replace).not.toHaveBeenCalled();
    });

    it('다른 사용자 감지 뒤 같은 화면의 다음 갱신은 HTTP 갱신을 보내지 않고, 가드가 걸려 있으면 다시 이동하지 않는다', async () => {
      vi.stubGlobal('location', { pathname: '/form', replace });
      setAccessToken(jwt('1'));
      const post = vi.spyOn(axios, 'post').mockResolvedValue(okResponse(jwt('2')));
      await expect(refreshAccessToken()).rejects.toBeInstanceOf(UserSwitchedError);
      expect(replace).toHaveBeenCalledTimes(1);

      await expect(refreshAccessToken()).rejects.toBeInstanceOf(UserSwitchedError);
      expect(post).toHaveBeenCalledTimes(1);
      expect(replace).toHaveBeenCalledTimes(1);

      // 가드가 풀리면 / 로 다시 이동한다
      vi.mocked(hasActiveLeaveGuard).mockReturnValue(false);
      await expect(refreshAccessToken()).rejects.toBeInstanceOf(UserSwitchedError);
      expect(post).toHaveBeenCalledTimes(1);
      expect(replace).toHaveBeenCalledTimes(2);
      expect(replace).toHaveBeenLastCalledWith('/');
    });

    it('가드가 없으면 같은 화면의 요청도 로그인 이동을 다시 건다(①), 가드가 있으면 걸지 않는다', async () => {
      const expired = expiredRefreshError();
      vi.spyOn(axios, 'post').mockRejectedValue(expired);
      vi.stubGlobal('location', { pathname: '/data', replace });
      await expect(refreshAccessToken()).rejects.toBe(expired);
      expect(replace).toHaveBeenCalledTimes(1);

      vi.mocked(hasActiveLeaveGuard).mockReturnValue(true);
      await expect(refreshAccessToken()).rejects.toBe(expired);
      expect(replace).toHaveBeenCalledTimes(1);

      vi.mocked(hasActiveLeaveGuard).mockReturnValue(false);
      await expect(refreshAccessToken()).rejects.toBe(expired);
      expect(replace).toHaveBeenCalledTimes(2);
      expect(replace).toHaveBeenLastCalledWith('/login');
      expect(axios.post).toHaveBeenCalledTimes(1);
    });

    it('401 뒤에는 재시도 없이 바로 굳는다 — 두 번째 호출은 refresh 를 보내지 않는다(WD-1122)', async () => {
      takeLoginNotice(); // 앞 시험에 남은 안내를 비운다
      vi.stubGlobal('location', { pathname: '/data', replace });
      const expired = expiredRefreshError();
      const post = vi.spyOn(axios, 'post').mockRejectedValue(expired);
      await expect(refreshAccessToken()).rejects.toBe(expired);
      expect(replace).toHaveBeenCalledWith('/login');
      await expect(refreshAccessToken()).rejects.toBe(expired);
      await expect(refreshAccessToken()).rejects.toBe(expired);
      expect(post).toHaveBeenCalledTimes(1);
      // 만료 안내는 그대로 맡겨 둔다 — 로그인 화면에서 안내가 보여야 한다
      expect(takeLoginNotice()).toBe(SESSION_EXPIRED_NOTICE);
    });

    it('다른 탭이 hasSession 을 켜도(storage 핸들러) 사용자 바뀜 표식은 남아 HTTP 갱신을 보내지 않는다, 일반 만료 표식은 비워진다(WD-1122)', async () => {
      setAccessToken(jwt('1'));
      const post = vi.spyOn(axios, 'post').mockResolvedValue(okResponse(jwt('2')));
      await expect(refreshAccessToken()).rejects.toBeInstanceOf(UserSwitchedError);
      expect(post).toHaveBeenCalledTimes(1);

      clearSessionExpiredError({ keepSwitched: true });
      await expect(refreshAccessToken()).rejects.toBeInstanceOf(UserSwitchedError);
      expect(post).toHaveBeenCalledTimes(1);

      // 일반 만료 표식은 같은 호출로 비워져 갱신이 다시 나간다
      clearSessionExpiredError();
      post.mockRejectedValue(expiredRefreshError());
      await expect(refreshAccessToken()).rejects.toBeTruthy();
      expect(post).toHaveBeenCalledTimes(2);
      clearSessionExpiredError({ keepSwitched: true });
      post.mockResolvedValue(okResponse('mock-ok'));
      await expect(refreshAccessToken()).resolves.toEqual({ token: 'mock-ok' });
      expect(post).toHaveBeenCalledTimes(3);
    });

    it('떠나기를 막는 것(스트리밍 등)이 걸려 있으면 같은 화면의 요청이 이동을 되풀이하지 않는다', async () => {
      const expired = expiredRefreshError();
      vi.spyOn(axios, 'post').mockRejectedValue(expired);
      vi.stubGlobal('location', { pathname: '/chat', replace });
      vi.mocked(hasActiveLeaveGuard).mockReturnValue(false);
      await expect(refreshAccessToken()).rejects.toBe(expired);
      expect(replace).toHaveBeenCalledTimes(1);
      vi.mocked(hasActiveLeaveGuard).mockReturnValue(true);
      await expect(refreshAccessToken()).rejects.toBe(expired);
      await expect(refreshAccessToken()).rejects.toBe(expired);
      expect(replace).toHaveBeenCalledTimes(1);
    });

    it('isOtherSessionUser — 둘 다 디코드되고 다를 때만 true', () => {
      setAccessToken(jwt('1'));
      expect(isOtherSessionUser(jwt('2'))).toBe(true);
      expect(isOtherSessionUser(jwt('1'))).toBe(false);
      expect(isOtherSessionUser('not-a-jwt')).toBe(false);
      resetSessionUserForTest();
      expect(isOtherSessionUser(jwt('2'))).toBe(false);
    });

    it('UserSwitchedError 는 한국어 안내 문구를 가진다', () => {
      expect(new UserSwitchedError().message).toBe('다른 사용자로 로그인되어 화면을 다시 엽니다.');
    });
  });
});

describe('refreshAccessTokenBestEffort (WD-1132)', () => {
  const replace = vi.fn();

  beforeEach(() => {
    setAccessToken('old');
    resetNoticeOwnerForTest();
    clearSessionExpiredError();
    vi.stubGlobal('location', { ...window.location, replace });
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    replace.mockReset();
  });

  it('진행 중인 갱신이 있으면 합류하지 않고 끝나길 기다린 뒤 새로 한 번 더 갱신한다', async () => {
    const first = deferredRefresh();
    const inFlight = refreshAccessToken();
    const bestEffort = refreshAccessTokenBestEffort();
    // 두 번째 refresh 는 서버 변경 뒤 상태를 돌려준다 — 첫 갱신이 끝난 뒤에야 시작돼야 한다.
    const second = deferredRefresh();
    expect(axios.post).toHaveBeenCalledTimes(1);

    first.resolve({ data: { accessToken: 'stale', activeTenantId: 1, memberships: [] } });
    await expect(inFlight).resolves.toEqual({ token: 'stale' });
    await vi.waitFor(() => expect(axios.post).toHaveBeenCalledTimes(2));
    second.resolve({ data: { accessToken: 'fresh', activeTenantId: 1, memberships: [] } });

    await expect(bestEffort).resolves.toEqual({ token: 'fresh' });
    expect(getAccessToken()).toBe('fresh');
  });

  it('401 이 아닌 실패는 세션을 끝내지 않고 거절만 한다 — 다음 갱신은 다시 보낼 수 있다', async () => {
    vi.spyOn(axios, 'post').mockRejectedValue(new TypeError('network'));
    await expect(refreshAccessTokenBestEffort()).rejects.toBeInstanceOf(TypeError);
    expect(replace).not.toHaveBeenCalled();
    expect(getAccessToken()).toBe('old');

    vi.spyOn(axios, 'post').mockResolvedValue({ data: { accessToken: 'new', activeTenantId: 1, memberships: [] } });
    await expect(refreshAccessToken()).resolves.toEqual({ token: 'new' });
  });

  it('401(refresh 토큰 무효)이면 평소처럼 세션을 끝낸다', async () => {
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post').mockRejectedValue(expired);
    await expect(refreshAccessTokenBestEffort()).rejects.toBe(expired);
    expect(replace).toHaveBeenCalledWith('/login');
  });

  it('테넌트 강등이면 { demoted: true } 를 돌려준다', async () => {
    vi.spyOn(axios, 'post').mockResolvedValue({ data: { accessToken: 'new', activeTenantId: null, memberships: [] } });
    await expect(refreshAccessTokenBestEffort()).resolves.toEqual({ demoted: true });
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
    resetNoticeOwnerForTest();
    // 이 파일의 다른 시험에서 켠 만료 표식이 여기의 갱신을 막지 않게 — 표식은 시험 간에 남지 않는다(WD-1082 반려)
    clearSessionExpiredError();
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

describe('endSessionAndReloadToLogin (WD-830 리뷰)', () => {
  const replace = vi.fn();
  const PW_NOTICE = '비밀번호가 변경되어 새 비밀번호로 다시 로그인해야 합니다';

  beforeEach(() => {
    resetNoticeOwnerForTest();
    clearSessionExpiredError();
    sessionStorage.clear();
    vi.stubGlobal('location', { ...window.location, replace });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    replace.mockReset();
  });

  it('늦은 자동 종료는 먼저 맡긴 안내를 덮지 않지만 이동은 다시 한다 — 떠나기 확인 취소 뒤에도 막히지 않게', () => {
    endSessionAndReloadToLogin(SESSION_EXPIRED_NOTICE);
    endSessionAndReloadToLogin();
    expect(replace).toHaveBeenCalledTimes(2);
    expect(takeLoginNotice()).toBe(SESSION_EXPIRED_NOTICE);
  });

  it('사용자가 누른 로그아웃은 앞선 자동 종료의 만료 안내를 비운다', () => {
    endSessionAndReloadToLogin(SESSION_EXPIRED_NOTICE);
    endSessionAndReloadToLogin(undefined, { userInitiated: true });
    expect(replace).toHaveBeenCalledTimes(2);
    expect(takeLoginNotice()).toBeNull();
  });

  it('사용자 로그아웃을 먼저 맡기면 서버 응답을 기다리는 동안의 자동 종료가 그 안내를 바꾸지 않는다', () => {
    claimUserLogout(PW_NOTICE);
    endSessionAndReloadToLogin(SESSION_EXPIRED_NOTICE);
    expect(takeLoginNotice()).toBe(PW_NOTICE);
  });

  it('자동 종료 뒤 refresh 가 성공하면 안내 주인이 풀려 뒤의 만료가 안내를 쓴다', () => {
    endSessionAndReloadToLogin();
    setAccessToken('recovered');
    endSessionAndReloadToLogin(SESSION_EXPIRED_NOTICE);
    expect(takeLoginNotice()).toBe(SESSION_EXPIRED_NOTICE);
  });

  it('사용자 로그아웃 안내는 늦은 refresh 성공으로 풀리지 않는다', () => {
    claimUserLogout(PW_NOTICE);
    setAccessToken('late');
    endSessionAndReloadToLogin(SESSION_EXPIRED_NOTICE);
    expect(takeLoginNotice()).toBe(PW_NOTICE);
  });

  it('사용자 로그아웃 뒤 늦은 자동 종료는 만료 안내를 남기지 않는다', () => {
    endSessionAndReloadToLogin(undefined, { userInitiated: true });
    endSessionAndReloadToLogin(SESSION_EXPIRED_NOTICE);
    expect(takeLoginNotice()).toBeNull();
  });
});
