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
import { authedFetch, claimSessionEndRenavigation, claimUserLogout, clearSessionExpiredError, endSessionAndReloadToLogin, getAccessToken, hasSessionEndedMarker, hasSessionEndStarted, isOtherSessionUser, postRefresh, readTokenSubject, REFRESH_RACE_RECHECK_WINDOW_MS, REFRESH_ROTATED_KEY, refreshAccessToken, refreshAccessTokenBestEffort, refreshSession, resetNoticeOwnerForTest, resetSessionUserForTest, setAccessToken, TokenRefreshError } from './client';
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
    // 401 이 아닌 실패에서도 서버 로그아웃은 보내지 않는다(WD-1141 반려 2차 2) — POST /auth/logout 은 모든 기기의
    // 웹 세션을 한꺼번에 끊는 데다, 그 시점의 액세스 토큰이 이미 만료라 401 이 나 리프레시 토큰도 지우지 못한다.
    // 갱신(1) → 다음 갱신(2) 순서로 두 번만 보낸다
    expect(axios.post).toHaveBeenNthCalledWith(1, '/api/v1/auth/refresh', null, expect.anything());
    expect(axios.post).not.toHaveBeenCalledWith('/api/v1/auth/logout', null, expect.anything());
    // 세션은 끝나지만 만료 표식은 켜지지 않는다 — 401 만료가 아니라 서버 오류라서(WD-1082)
    expect(getAccessToken()).toBeNull();

    await expect(refreshAccessToken()).resolves.toEqual({ token: 'new' });
    expect(axios.post).toHaveBeenCalledTimes(2);
  });

  it('401 이 아닌 갱신 실패로는 세션 종료 표식을 켜지 않는다 — 살아 있는 서버 세션의 로그아웃을 막지 않게(WD-1141 반려 3차 3)', async () => {
    vi.spyOn(axios, 'post').mockRejectedValueOnce(new TypeError('network'));

    await expect(refreshAccessToken()).rejects.toBeInstanceOf(TypeError);

    // 표식을 켜 두면 떠나기 확인 취소로 이동이 막힌 탭에 남아, 나중에 실제로 로그아웃할 때 logout() 이
    // POST /auth/logout 을 건너뛰어 서버 리프레시 토큰이 살아남는다(공용 PC 위험 WD-276).
    // 401 만료면 서버가 이미 세션을 끝낸 경우라 표식을 켜도 그때는 보낼 POST 가 없다 — 아래 시험이 그 대조다.
    expect(hasSessionEndStarted()).toBe(false);
    // 세션 자체는 끝난다 — 로그인 화면으로의 이동은 그대로다.
    expect(getAccessToken()).toBeNull();
    expect(replace).toHaveBeenCalledWith('/login');
  });

  it('세션 종료를 시작하면 표식이 켜지고, 값이 있는 토큰을 다시 저장하면 꺼진다(WD-1141 반려 2차 4)', () => {
    // logout() 이 서버 로그아웃을 보낼 자리를 이 표식으로 가른다 — 공유 표식(hasSession) 읽어 넘겨 짐작 대신
    expect(hasSessionEndStarted()).toBe(false);
    endSessionAndReloadToLogin();
    expect(hasSessionEndStarted()).toBe(true);
    // 살아난 세션(로그인·갱신 성공)에서는 표식이 꺼진다 — 새 세션의 서버 로그아웃을 logout() 이 건너뛰지 않게
    setAccessToken('recovered');
    expect(hasSessionEndStarted()).toBe(false);
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
    // 재확인 조건용 표식이 뒤의 시험으로 새지 않게 비운다(각 시험이 켰다 끄지 않으므로 여기서 정리한다)
    localStorage.removeItem('hasSession');
    localStorage.removeItem(REFRESH_ROTATED_KEY);
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

  describe('줄 선 요청 구제 (WD-1158)', () => {
    /** best-effort 갱신을 지연 응답으로 띄우고 그 뒤에 일반 갱신 요청(줄 선 요청)을 세운다. */
    function startWithWaiter() {
      const first = deferredRefresh();
      const bestEffort = refreshAccessTokenBestEffort();
      const waiter = refreshAccessToken();
      return { first, bestEffort, waiter };
    }

    it('best-effort 일시 오류(500·네트워크) 때 줄 선 요청은 일반 갱신을 한 번 더 시도해 성공하면 새 토큰으로 재요청된다', async () => {
      const { first, bestEffort, waiter } = startWithWaiter();
      const post = vi.spyOn(axios, 'post');
      post.mockResolvedValueOnce({ data: { accessToken: 'retried', activeTenantId: 1, memberships: [] } });
      first.reject(new TypeError('network'));

      await expect(waiter).resolves.toEqual({ token: 'retried' });
      // 재시도가 성공해 상태가 새로워졌으므로 best-effort 호출자도 그 결과로 성공한다.
      await expect(bestEffort).resolves.toEqual({ token: 'retried' });
      expect(getAccessToken()).toBe('retried');
      expect(replace).not.toHaveBeenCalled();
    });

    it('재시도도 (401 아닌) 실패하면 대기열을 그 오류로 거절하고 세션은 끝내지 않으며 더 시도하지 않는다', async () => {
      const { first, bestEffort, waiter } = startWithWaiter();
      const bestEffortRejected = expect(bestEffort).rejects.toBeInstanceOf(TypeError);
      const retryError = new Error('retry 500');
      const post = vi.spyOn(axios, 'post');
      post.mockRejectedValueOnce(retryError);
      const waiterRejected = expect(waiter).rejects.toBe(retryError);
      first.reject(new TypeError('network'));

      await waiterRejected;
      await bestEffortRejected;
      // 처음 best-effort 한 번 + 재시도 한 번 — 그 이상은 없다.
      expect(post).toHaveBeenCalledTimes(2);
      // 일시 장애 두 번으로 로그아웃시키지 않는다.
      expect(replace).not.toHaveBeenCalled();
      expect(getAccessToken()).toBe('old');
      expect(hasSessionEndedMarker()).toBe(false);
    });

    it('재시도가 401 이면 세션을 끝내고 대기열을 그 오류로 거절한다', async () => {
      const { first, bestEffort, waiter } = startWithWaiter();
      const bestEffortRejected = expect(bestEffort).rejects.toBeInstanceOf(TypeError);
      const expired = expiredRefreshError();
      const post = vi.spyOn(axios, 'post');
      post.mockRejectedValueOnce(expired);
      const waiterRejected = expect(waiter).rejects.toBe(expired);
      first.reject(new TypeError('network'));

      await waiterRejected;
      await bestEffortRejected;
      expect(post).toHaveBeenCalledTimes(2);
      expect(replace).toHaveBeenCalledWith('/login');
    });

    it('잠금: 재시도가 진행 중인 동안 들어온 새 요청은 병렬 갱신 없이 재시도 결과를 받는다', async () => {
      const { first, bestEffort, waiter } = startWithWaiter();
      // 재시도의 응답을 손으로 풀 수 있게 먼저 바꿔 둔다.
      const second = deferredRefresh();
      first.reject(new TypeError('network'));
      await vi.waitFor(() => expect(axios.post).toHaveBeenCalledTimes(2));
      const late = refreshAccessToken();
      expect(axios.post).toHaveBeenCalledTimes(2);

      second.resolve({ data: { accessToken: 'retried', activeTenantId: 1, memberships: [] } });
      await expect(waiter).resolves.toEqual({ token: 'retried' });
      await expect(late).resolves.toEqual({ token: 'retried' });
      await expect(bestEffort).resolves.toEqual({ token: 'retried' });
      expect(axios.post).toHaveBeenCalledTimes(2);
    });

    it('잠금: 재시도 직후 이어진 새 요청도 정상적으로 새 갱신을 시작하고 완료된다(잠금이 정확히 한 번만 풀린다)', async () => {
      const { first, bestEffort, waiter } = startWithWaiter();
      const post = vi.spyOn(axios, 'post');
      post.mockResolvedValueOnce({ data: { accessToken: 'retried', activeTenantId: 1, memberships: [] } });
      first.reject(new TypeError('network'));
      await expect(waiter).resolves.toEqual({ token: 'retried' });
      await expect(bestEffort).resolves.toEqual({ token: 'retried' });

      // 재시도 완료 뒤 새 갱신 하나가 시작돼 진행 중일 때 — 두 번째 요청은 그 갱신에 합류해야 하고 새 post 를 만들면 안 된다.
      const before = vi.mocked(axios.post).mock.calls.length;
      const next = deferredRefresh();
      const a = refreshAccessToken();
      const b = refreshAccessToken();
      expect(vi.mocked(axios.post).mock.calls.length - before).toBe(1);
      next.resolve({ data: { accessToken: 'n', activeTenantId: 1, memberships: [] } });
      await expect(a).resolves.toEqual({ token: 'n' });
      await expect(b).resolves.toEqual({ token: 'n' });
    });

    it('줄 선 요청이 없으면 재시도하지 않는다', async () => {
      const post = vi.spyOn(axios, 'post').mockRejectedValue(new TypeError('network'));
      await expect(refreshAccessTokenBestEffort()).rejects.toBeInstanceOf(TypeError);
      expect(post).toHaveBeenCalledTimes(1);
    });

    it('best-effort 401 은 지금처럼 세션을 끝내고 대기열도 거절한다 — 재시도 없음', async () => {
      const { first, bestEffort, waiter } = startWithWaiter();
      const expired = expiredRefreshError();
      const bestEffortRejected = expect(bestEffort).rejects.toBe(expired);
      const waiterRejected = expect(waiter).rejects.toBe(expired);
      first.reject(expired);

      await waiterRejected;
      await bestEffortRejected;
      expect(axios.post).toHaveBeenCalledTimes(1);
      expect(replace).toHaveBeenCalledWith('/login');
    });

    /**
     * 구제 재시도(WD-1158)와 401 재확인(WD-1141)은 같은 안에 겹쳐 돌아야 한다 — 한쪽이 다른 쪽을 우회하면
     * 회전 경쟁의 패자 401 이 재확인 없이 세션 종료로 넘어가 이긴 탭까지 로그아웃된다(WD-1141 반려 3차 2).
     */
    describe('재시도 안의 401 재확인 (WD-1141 반려 3차 2)', () => {
      /**
       * 재확인 조건(세션 표식·회전 표식 없음)을 먼저 만든 뒤 best-effort 재시도까지 띄운다.
       *
       * <p>회전 표식은 **재시도가 401 을 받은 뒤에야** 적는다 — 재확인이 표식을 보고 재전송하려면,
       * 재확인이 표식을 먼저 읽은 자리(seen)와 그 뒤의 값을 비교해야 한다.
       */
      function startWithRecheck() {
        localStorage.removeItem(REFRESH_ROTATED_KEY);
        // 재확인은 `hasSession` 이 남아 있을 때만 돈다 — 여기서는 저장소에 세션 표식을 켜 둔다.
        localStorage.setItem('hasSession', 'true');
        const { first, bestEffort, waiter } = startWithWaiter();
        const post = vi.spyOn(axios, 'post');
        const expired = expiredRefreshError();
        // 재시도가 받을 401 — 재확인을 거친 뒤에야 세션 종료로 가야 한다.
        post.mockRejectedValueOnce(expired);
        return { first, bestEffort, waiter, post, expired };
      }

      it('구제 재시도가 401 이면 재확인을 거친 뒤에야 세션을 끝낸다 — 재시도가 재확인을 우회하지 않는다', async () => {
        const { first, bestEffort, waiter, post, expired } = startWithRecheck();
        const bestEffortRejected = expect(bestEffort).rejects.toBeInstanceOf(TypeError);
        const waiterRejected = expect(waiter).rejects.toBe(expired);
        first.reject(new TypeError('network'));

        // 구제 재시도가 401 로 끝나면 재확인이 들어간다 — 표식이 아직 그대로라 재전송 없이 기다린다.
        await vi.waitFor(() => expect(post).toHaveBeenCalledTimes(2));
        // 이긴 탭이 방금 회전했다는 표식 — 그때만 재전송한다.
        localStorage.setItem(REFRESH_ROTATED_KEY, String(Date.now()));
        await vi.waitFor(() => expect(post).toHaveBeenCalledTimes(3));

        await waiterRejected;
        await bestEffortRejected;
        // 1(원래 갱신) + 1(구제 재시도) + 1(401 재확인의 재전송) — 재전송도 401 이라 첫 401 을 그대로 전파한다.
        expect(post).toHaveBeenCalledTimes(3);
        expect(replace).toHaveBeenCalledWith('/login');
        // 401 이므로 서버가 이미 세션을 끝낸 자리 — 종료 표식을 켠다.
        expect(hasSessionEndStarted()).toBe(true);
      });

      it('재확인이 응답을 기다리는 동안 들어온 줄 선 요청은 재확인 결과로 같은 새 토큰을 받는다', async () => {
        const { first, bestEffort, waiter, post } = startWithRecheck();
        // 재전송이 쓸 응답을 손으로 풀 수 있게 먼저 바꿔 둔다 — 재확인이 네트워크 대기 중인 자리가 된다.
        // (원래 갱신의 응답은 이미 보냈으므로 이 교체가 재시도·재전송에만 걸린다.)
        const recheck = deferredRefresh();
        first.reject(new TypeError('network'));

        await vi.waitFor(() => expect(post).toHaveBeenCalledTimes(2));
        // 이긴 탭이 방금 회전했다는 표식 — 재확인이 재전송을 시작한다.
        localStorage.setItem(REFRESH_ROTATED_KEY, String(Date.now()));
        await vi.waitFor(() => expect(post).toHaveBeenCalledTimes(3));

        // 재확인이 응답을 기다리는 동안 들어온 새 요청 — 갱신 잠금이 남아 있어 대기열에만 합류해야 한다.
        const duringRecheck = refreshAccessToken();
        expect(post).toHaveBeenCalledTimes(3);

        recheck.resolve({ data: { accessToken: 'rechecked', activeTenantId: 1, memberships: [] } });

        await expect(duringRecheck).resolves.toEqual({ token: 'rechecked' });
        await expect(waiter).resolves.toEqual({ token: 'rechecked' });
        await expect(bestEffort).resolves.toEqual({ token: 'rechecked' });
        expect(post).toHaveBeenCalledTimes(3);
        expect(replace).not.toHaveBeenCalled();
        expect(hasSessionEndStarted()).toBe(false);
        expect(getAccessToken()).toBe('rechecked');
      });
    });
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

describe('claimSessionEndRenavigation (WD-1160)', () => {
  const replace = vi.fn();

  beforeEach(() => {
    resetNoticeOwnerForTest();
    clearSessionExpiredError();
    vi.mocked(hasActiveLeaveGuard).mockReturnValue(false);
    vi.stubGlobal('location', { ...window.location, pathname: '/form', replace });
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    replace.mockReset();
  });

  /** 만료 표식을 켜고 /form 에서 첫 이동을 건 상태로 만든다 */
  async function endSessionOnForm() {
    vi.spyOn(axios, 'post').mockRejectedValue(expiredRefreshError());
    await expect(refreshAccessToken()).rejects.toBeTruthy();
  }

  it('이동을 건 화면과 같고 가드가 걸려 있으면 false 이고 경로는 그대로다', async () => {
    await endSessionOnForm();
    vi.mocked(hasActiveLeaveGuard).mockReturnValue(true);
    expect(claimSessionEndRenavigation()).toBe(false);
    expect(claimSessionEndRenavigation()).toBe(false);
  });

  it('화면이 바뀌었으면 true 이고, 그 화면이 새 기준이 된다', async () => {
    await endSessionOnForm();
    vi.mocked(hasActiveLeaveGuard).mockReturnValue(true);
    vi.stubGlobal('location', { ...window.location, pathname: '/other', replace });
    expect(claimSessionEndRenavigation()).toBe(true);
    expect(claimSessionEndRenavigation()).toBe(false);
  });

  it('가드가 없으면 같은 화면이어도 true', async () => {
    await endSessionOnForm();
    expect(claimSessionEndRenavigation()).toBe(true);
  });
});

/**
 * refresh POST 재확인 (WD-1141) — 잠금 없는 환경에서 갱신 401 이 「회전 경쟁의 패자」인지, 세션이 진짜 끝났는지 확인한다.
 *
 * 서버는 refresh 토큰을 갱신마다 회전시키므로 잠금 없이 두 탭이 동시에 갱신하면 진 탭이 401 을 받고,
 * 그 401 을 그대로 세션 종료로 처리하면 storage 이벤트로 이긴 탭까지 로그아웃된다. 그래서 재전송은
 * **이긴 탭이 응답을 받은 뒤 쓴 회전 표식(REFRESH_ROTATED_KEY)이 바뀐 뒤에만** 한다 — 표식이 바뀌었다는 것은
 * 그 시점에 쿠키 잼에 새 쿠키가 들어왔다는 뜻이라, 회전 전 쿠키를 다시 보내 서버의 재사용 탐지가
 * 로그인 패밀리 전체를 폐기하는 일이 없다(리뷰 1). 표식이 바뀌지 않으면 재확인 창(800ms) 안에서만 기다렸다가
 * 첫 401 을 그대로 세션 종료로 넘긴다 — 창이 지난 뒤의 표식 변화는 쓰지 않는다.
 */
describe('postRefresh 재확인 (WD-1141)', () => {
  const replace = vi.fn();

  beforeEach(() => {
    vi.useFakeTimers();
    resetSessionUserForTest();
    resetNoticeOwnerForTest();
    clearSessionExpiredError();
    setAccessToken('old');
    vi.mocked(hasActiveLeaveGuard).mockReturnValue(true);
    vi.stubGlobal('location', { pathname: '/form', replace });
    // 잠금 없는 환경 전제를 시험이 아니라 여기서 한 번 만든다 — 이 describe 는 전부 잠금 없음이 기본이고,
    // 잠금이 있는 시험만 자기 자리에서 locks 를 넣어 다시 덮어쓴다(jsdom 에는 navigator.locks 가 없다).
    vi.stubGlobal('navigator', { ...navigator, locks: undefined });
    // 재확인 조건 — hasSession 이 남아 있어야 재확인한다(각 시험이 끝나면 지우므로 매번 새로 켠다)
    localStorage.setItem('hasSession', 'true');
    // 회전 표식은 각 시험이 직접 만들어야 한다 — 앞 시험이 남긴 표식이 있으면 재전송이 바로 붙어
    // 「표식 없이 기다리는」 경로를 시험할 수 없다(WD-1141)
    localStorage.removeItem(REFRESH_ROTATED_KEY);
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.useRealTimers();
    localStorage.removeItem('hasSession');
    localStorage.removeItem(REFRESH_ROTATED_KEY);
    replace.mockReset();
  });

  it('이긴 탭의 회전 표식이 바뀐 뒤 재전송하면 세션 종료 없이 새 토큰을 받는다', async () => {
    vi.spyOn(axios, 'post')
      .mockRejectedValueOnce(expiredRefreshError())
      .mockResolvedValueOnce({ data: { accessToken: 'new', activeTenantId: 1, memberships: [] } } as never);

    const p = postRefresh();
    await vi.advanceTimersByTimeAsync(0); // 첫 401 처리로 재전송 대기에 들어간 뒤
    expect(axios.post).toHaveBeenCalledTimes(1);

    // 이긴 탭이 응답을 받고 회전 표식을 씀 — 그때 쿠키 잼에 새 쿠키가 들어와 재전송이 안전하다
    localStorage.setItem(REFRESH_ROTATED_KEY, 'winner');
    await vi.advanceTimersByTimeAsync(REFRESH_RACE_RECHECK_WINDOW_MS);

    await expect(p).resolves.toEqual({ data: { accessToken: 'new', activeTenantId: 1, memberships: [] } });
    expect(axios.post).toHaveBeenCalledTimes(2);
    expect(replace).not.toHaveBeenCalled();
    expect(localStorage.getItem('hasSession')).toBe('true');
  });

  it('표식 읽기가 막힌 환경에서도 첫 401 을 그대로 던진다 — SecurityError 가 만료 판정을 삼키지 않는다(WD-1141 반려 3차 6)', async () => {
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post').mockRejectedValue(expired);
    // 사생활 보호 모드·저장소 차단처럼 localStorage.getItem 이 던지는 환경 — 재확인 조건을 읽는 자리다.
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('The operation is insecure.', 'SecurityError');
    });

    // 표식을 못 읽는 순간 재전송의 근거는 이미 없다 — 첫 401 을 세션 종료로 넘겨야지, 읽기 예외가 그 자리를
    // 덮어 삼키면 만료 판정·안내(WD-830)가 통째로 사라진다.
    await expect(postRefresh()).rejects.toBe(expired);
  });

  it('회전 표식이 바뀌지 않으면 재확인 창이 지날 때까지 재전송하지 않고, 지나면 첫 401 을 그대로 던진다', async () => {
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post').mockRejectedValue(expired);

    const p = postRefresh();
    const judged = expect(p).rejects.toBe(expired); // 미처리 거절을 막기 위해 핸들러를 먼저 붙인다
    await vi.advanceTimersByTimeAsync(0);
    expect(axios.post).toHaveBeenCalledTimes(1);

    // 표식이 안 바뀌었으므로(진짜 세션 종료) 창이 다 차기 전에는 한 번도 재전송하지 않는다
    await vi.advanceTimersByTimeAsync(REFRESH_RACE_RECHECK_WINDOW_MS - 1);
    expect(axios.post).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1);
    await judged;
    expect(axios.post).toHaveBeenCalledTimes(1);
    expect(replace).not.toHaveBeenCalled(); // postRefresh 는 세션 종료를 하지 않는다 — 호출자(doRefresh)가 맡는다
  });

  it('재확인 창이 지난 뒤에 표식이 바뀌어도 재전송하지 않는다 — 옛 쿠키를 다시 보내지 않는다(WD-1141 리뷰 1)', async () => {
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post').mockRejectedValue(expired);

    const p = postRefresh();
    const judged = expect(p).rejects.toBe(expired);
    await vi.advanceTimersByTimeAsync(0);
    // 창이 지난 뒤에 이긴 탭이 표식을 씀 — 이미 재전송하지 않기로 정한 뒤라 쓰지 않는다(늦은 재전송은 옛 쿠키가 될 수 있다)
    await vi.advanceTimersByTimeAsync(REFRESH_RACE_RECHECK_WINDOW_MS + 200);
    localStorage.setItem(REFRESH_ROTATED_KEY, 'too-late');
    await vi.advanceTimersByTimeAsync(REFRESH_RACE_RECHECK_WINDOW_MS);

    await judged;
    expect(axios.post).toHaveBeenCalledTimes(1);
  });

  it('첫 실패가 401 이 아니면 재확인 없이 그 오류가 나고 한 번만 보낸다', async () => {
    // 재확인은 401 에만 한다(client.ts 의 !isUnauthorizedError → 즉시 throw) — 네트워크 오류를 재확인으로 늘리지 않는다
    const networkError = new TypeError('Network Error');
    vi.spyOn(axios, 'post').mockRejectedValue(networkError);

    await expect(postRefresh()).rejects.toBe(networkError);
    expect(axios.post).toHaveBeenCalledTimes(1);
    expect(replace).not.toHaveBeenCalled();
    expect(localStorage.getItem('hasSession')).toBe('true');
  });

  it('두 번째 401 이면 세션을 끝낸다', async () => {
    takeLoginNotice(); // 앞 시험에 소비되지 않고 남은 안내를 비운다
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post').mockRejectedValue(expired);

    const p = refreshAccessToken();
    const judged = expect(p).rejects.toBe(expired); // 거절 핸들러를 먼저 붙여 대기 중 미처리 거절로 잡히지 않게 한다
    await vi.advanceTimersByTimeAsync(0);
    localStorage.setItem(REFRESH_ROTATED_KEY, 'winner'); // 표식이 바뀌어 재전송이 붙게 한 뒤
    await vi.advanceTimersByTimeAsync(REFRESH_RACE_RECHECK_WINDOW_MS);
    await judged;

    expect(axios.post).toHaveBeenCalledTimes(2);
    expect(replace).toHaveBeenCalledTimes(1);
    expect(replace).toHaveBeenCalledWith('/login');
    expect(localStorage.getItem('hasSession')).toBeNull();
    // 두 번째 401 로 끝난 세션 종료에도 WD-830 만료 안내가 남는다 — 로그인 화면에서 이유를 알려야 한다
    expect(takeLoginNotice()).toBe(SESSION_EXPIRED_NOTICE);
  });

  it('두 번째 시도가 401 이 아닌 오류로 끝나도 첫 401 이 전파되고 만료 안내가 남는다', async () => {
    takeLoginNotice(); // 앞 시험에 소비되지 않고 남은 안내를 비운다
    const expired = expiredRefreshError();
    const networkError = new TypeError('Network Error');
    // 첫 POST 는 401(재확인 대상), 두 번째 POST 는 네트워크 오류 — 두 번째 오류가 판정을 바꾸면 안 된다
    vi.spyOn(axios, 'post')
      .mockRejectedValueOnce(expired)
      .mockRejectedValueOnce(networkError);

    const p = refreshAccessToken();
    const judged = expect(p).rejects.toBe(expired); // 미처리 거절을 막기 위해 핸들러를 먼저 붙인다
    await vi.advanceTimersByTimeAsync(0);
    localStorage.setItem(REFRESH_ROTATED_KEY, 'winner'); // 표식이 바뀌어 재전송이 붙게 한 뒤
    await vi.advanceTimersByTimeAsync(REFRESH_RACE_RECHECK_WINDOW_MS);
    await judged;

    expect(axios.post).toHaveBeenCalledTimes(2);
    expect(replace).toHaveBeenCalledWith('/login');
    expect(localStorage.getItem('hasSession')).toBeNull();
    // 첫 401 로 만료 판정됐으므로 WD-830 안내가 남는다 — 두 번째 오류(네트워크)가 표식·안내를 빼앗으면 안 된다
    expect(takeLoginNotice()).toBe(SESSION_EXPIRED_NOTICE);
  });

  it('다른 패자가 먼저 재전송해 표식이 다시 바뀌면 재확인 창 안에서 한 번 더 재전송한다', async () => {
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post')
      .mockRejectedValueOnce(expired)
      .mockRejectedValueOnce(expired)
      .mockResolvedValueOnce({ data: { accessToken: 'new', activeTenantId: 1, memberships: [] } } as never);

    const p = postRefresh();
    await vi.advanceTimersByTimeAsync(0);
    localStorage.setItem(REFRESH_ROTATED_KEY, 'w1');
    await vi.advanceTimersByTimeAsync(60); // 첫 재전송이 401 로 끝난 뒤
    expect(axios.post).toHaveBeenCalledTimes(2);

    localStorage.setItem(REFRESH_ROTATED_KEY, 'w2'); // 또 다른 패자가 먼저 재전송해 회전함
    await vi.advanceTimersByTimeAsync(REFRESH_RACE_RECHECK_WINDOW_MS);

    await expect(p).resolves.toEqual({ data: { accessToken: 'new', activeTenantId: 1, memberships: [] } });
    expect(axios.post).toHaveBeenCalledTimes(3);
  });

  it('잠금이 있는 환경에서는 재확인하지 않는다', async () => {
    vi.stubGlobal('navigator', {
      ...navigator,
      locks: { request: vi.fn((_name: string, fn: () => Promise<unknown>) => fn()) },
    });
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post').mockRejectedValue(expired);

    await expect(postRefresh()).rejects.toBe(expired);
    expect(axios.post).toHaveBeenCalledTimes(1);
  });

  it('대기 중 다른 탭이 hasSession 을 지우면 회전 표식이 바뀌어도 재전송하지 않는다', async () => {
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post').mockRejectedValue(expired);

    const p = postRefresh();
    const judged = expect(p).rejects.toBe(expired); // 미처리 거절을 막기 위해 핸들러를 먼저 붙인다
    await vi.advanceTimersByTimeAsync(0); // 첫 401 처리로 재전송 대기에 들어간 뒤
    localStorage.setItem(REFRESH_ROTATED_KEY, 'winner'); // 표식은 바뀌었지만…
    localStorage.removeItem('hasSession'); // 다른 탭이 이미 세션을 끝낸 상황
    await vi.advanceTimersByTimeAsync(REFRESH_RACE_RECHECK_WINDOW_MS);
    await judged;

    expect(axios.post).toHaveBeenCalledTimes(1);
  });

  it('가드가 있는 탭에서 이동은 한 번만 건다 — 재확인 실패 뒤 refreshAccessToken 을 두 번 불러도 replace 가 2 를 넘지 않는다', async () => {
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post').mockRejectedValue(expired);
    vi.mocked(hasActiveLeaveGuard).mockReturnValue(true);

    // 첫 갱신 — 재전송까지 두 번 보낸 뒤 만료로 끝내며 로그인 이동을 한 번 건다
    const first = refreshAccessToken();
    const judged = expect(first).rejects.toBe(expired); // 미처리 거절을 막기 위해 핸들러를 먼저 붙인다
    await vi.advanceTimersByTimeAsync(0);
    localStorage.setItem(REFRESH_ROTATED_KEY, 'winner'); // 표식이 바뀌어 재전송이 붙게 한 뒤
    await vi.advanceTimersByTimeAsync(REFRESH_RACE_RECHECK_WINDOW_MS);
    await judged;
    expect(axios.post).toHaveBeenCalledTimes(2);
    expect(replace).toHaveBeenCalledTimes(1);
    expect(replace).toHaveBeenCalledWith('/login');

    // 같은 화면의 두 번째 호출 — 만료 표식·expiredNavPath(WD-1082)가 이동을 다시 건드리지 않아 확인 창이 두 번 걸리지 않는다
    await expect(refreshAccessToken()).rejects.toBe(expired);
    expect(axios.post).toHaveBeenCalledTimes(2); // HTTP 재전송 없음
    expect(replace).toHaveBeenCalledTimes(1);
  });
});

/**
 * refreshSession (WD-1141) — 비밀번호 변경 직후 갱신(completePasswordChange)이 같은 탭 갱신 큐를 거치고,
 * 그 갱신이 받은 토큰 응답 전체(memberships·mustChangePassword 포함)를 그대로 받는다.
 *
 * authApi.refresh 로 따로 보내면 같은 탭의 401 재시도 갱신·대기 큐를 우회해 두 refresh 가 회전된 쿠키를 다투게 되므로,
 * 갱신 시작·대기·세션 종료는 전부 refreshAccessToken 한 경로로 모은다.
 */
describe('refreshSession (WD-1141)', () => {
  const replace = vi.fn();

  beforeEach(() => {
    resetSessionUserForTest();
    resetNoticeOwnerForTest();
    clearSessionExpiredError();
    setAccessToken('old');
    vi.mocked(hasActiveLeaveGuard).mockReturnValue(true);
    vi.mocked(publishTenantSession).mockClear();
    vi.stubGlobal('location', { ...window.location, replace });
    // 세션 종료 확인용 표식 — 성공 시험은 걸리지 않게, 실패 시험은 재확인 대기(표식이 없어 즉시 던지는 경로) 없이
    // 첫 401 만으로 끝내기 위해 비워 둔다
    localStorage.removeItem('hasSession');
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    replace.mockReset();
    localStorage.removeItem('hasSession');
  });

  it('진행 중인 갱신에 합류하지 않고 그 갱신이 끝나기를 기다린 뒤 자기 갱신을 새로 보낸다', async () => {
    // 비밀번호를 바꾼 뒤 부르므로, 변경 전에 시작된 갱신의 응답(변경 전 세션의 토큰·쿠키)을 받으면 안 된다(WD-1141 리뷰 3)
    const beforeChange = { accessToken: 'before-change', activeTenantId: 1, memberships: [] };
    const afterChange = {
      accessToken: 'after-change',
      memberships: [{ tenantId: 1, tenantSlug: 'acme', tenantName: '워크스페이스', role: 'OWNER' }],
      mustChangePassword: false,
    };

    let resolveFirst!: (v: unknown) => void;
    const firstPost = new Promise((res) => {
      resolveFirst = res;
    });
    let resolveSecond!: (v: unknown) => void;
    const secondPost = new Promise((res) => {
      resolveSecond = res;
    });
    vi.spyOn(axios, 'post')
      .mockReturnValueOnce(firstPost as never)
      .mockReturnValueOnce(secondPost as never);

    const p1 = refreshAccessToken(); // 비밀번호 변경 전에 이미 시작된 갱신 — 표식이 켜진 채 진행 중이다
    const p2 = refreshSession(); // 진행 중인 갱신에 합류하지 않고 끝날 때까지 기다린다
    resolveFirst({ data: beforeChange });

    await expect(p1).resolves.toEqual({ token: 'before-change' });
    // 대기가 풀린 뒤 자기 갱신이 새로 시작돼 두 번째 POST 가 나간다 — 변경 전 갱신의 응답을 받지 않는다
    await vi.waitFor(() => expect(axios.post).toHaveBeenCalledTimes(2));
    resolveSecond({ data: afterChange });

    await expect(p2).resolves.toEqual(afterChange);
    expect(axios.post).toHaveBeenCalledTimes(2);
  });

  it('갱신이 실패하면 오류 그대로 던진다', async () => {
    const expired = expiredRefreshError();
    vi.spyOn(axios, 'post').mockRejectedValue(expired);

    await expect(refreshSession()).rejects.toBe(expired);
    expect(axios.post).toHaveBeenCalledTimes(1);
    expect(replace).toHaveBeenCalledTimes(1);
    expect(replace).toHaveBeenCalledWith('/login');
  });
});
