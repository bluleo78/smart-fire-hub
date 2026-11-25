import axios from 'axios';

import { isUnauthorizedError, UserSwitchedError } from '../lib/api-error';
import { clearLoginNotice, SESSION_EXPIRED_NOTICE, setLoginNotice } from '../lib/login-notice';
import { hasActiveLeaveGuard } from '../lib/unsaved-changes-guard-registry';
import type { TokenResponse } from '../types/auth';
import { withCrossTabRefreshLock } from './refresh-lock';
import { publishTenantSession } from './tenant-session';

let accessToken: string | null = null;

/**
 * 이 탭이 쥔 사용자 id(JWT sub) (WD-1122). 갱신 결과의 사용자와 비교해, 만료된 탭이 다른 탭의 **다른 사용자** 쿠키로
 * 되살아나 앞 사용자의 캐시·폼을 든 채 새 사용자로 동작하는 것을 막는다. 디코드 못 하는 토큰은 기억하지 않는다.
 */
let sessionUserId: string | null = null;

/**
 * JWT payload 의 sub(문자열)를 읽는다 (WD-1122). base64url(`-`·`_`·패딩 없음)을 처리하고, 어떤 실패든 null —
 * 디코드 못 하는 토큰으로 비교·리로드 고리를 만들지 않으려는 것이다.
 */
export function readTokenSubject(token: string): string | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
    const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
    const payload: unknown = JSON.parse(new TextDecoder().decode(bytes));
    if (typeof payload !== 'object' || payload === null) return null;
    const sub = (payload as { sub?: unknown }).sub;
    return typeof sub === 'string' ? sub : null;
  } catch {
    return null;
  }
}

/**
 * 이 토큰이 이 탭의 사용자가 아닌 **다른 사용자**의 것인지 (WD-1122). 두 sub 가 모두 디코드되고 서로 다를 때만 true —
 * 디코드 못 하면 비교하지 않는다(리로드 고리 방지). AuthContext 의 비밀번호 변경 갱신처럼 refreshAccessToken 을
 * 거치지 않는 갱신 경로가 같은 판정을 쓰게 한다.
 */
export function isOtherSessionUser(token: string): boolean {
  const sub = readTokenSubject(token);
  return sessionUserId !== null && sub !== null && sub !== sessionUserId;
}

/**
 * 다른 사용자의 토큰을 받았을 때 이 탭의 세션을 버리고 `/` 로 하드 이동한다(WD-1122).
 * runRefresh 와 AuthContext 의 비밀번호 변경 갱신이 같은 처리를 하게 한 곳에 둔다 — 메모리 토큰 비움,
 * 사용자 바뀜 표식·이동 화면 기록(뒤의 같은 화면 요청은 refreshAccessToken 의 같은 규칙으로 처리), 하드 이동.
 * 첫 감지는 만료 흐름과 같이 늘 한 번 이동한다(가드가 있으면 확인 창도 이때 한 번). 대기 큐 거절은 호출자 몫이다.
 */
export function abandonForSwitchedUser(): UserSwitchedError {
  const switched = new UserSwitchedError();
  accessToken = null;
  sessionExpiredError = switched;
  expiredNavPath = window.location.pathname;
  window.location.replace('/');
  return switched;
}

/** 시험 전용 — 모듈 수준 사용자 id 가 시험 사이에 남지 않게 푼다(WD-1122). */
export function resetSessionUserForTest() {
  sessionUserId = null;
}

export function setAccessToken(token: string | null) {
  // 값이 있는 토큰이 저장되면 세션이 살아난 것 — 남은 만료 표식부터 비운다(WD-1082)
  if (token) {
    clearSessionExpiredError();
    const sub = readTokenSubject(token);
    if (sub !== null) sessionUserId = sub;
  }
  accessToken = token;
  // 자동 종료가 떠나기 확인 취소로 멈춘 뒤 refresh 가 성공했으면 세션이 살아난 것이다 — 뒤의 진짜 만료가 안내를 쓸 수 있게
  // 자동 종료의 안내 주인을 푼다. 사용자 로그아웃이 맡은 안내는 풀지 않는다 — 늦은 성공이 로그아웃 안내를 덮지 않게(WD-830 리뷰).
  if (token && noticeOwner === 'auto') noticeOwner = null;
}

export function getAccessToken(): string | null {
  return accessToken;
}

/** 로그인 세션 표식 — 이 값이 있을 때만 부팅 refresh 를 시도하고, 지우면 다른 탭이 storage 이벤트로 로그아웃을 안다(#565). */
export const AUTH_FLAG_KEY = 'hasSession';

/**
 * 로그인 화면 안내를 누가 맡았는지 — 'auto'(refresh 실패·다른 탭 로그아웃) / 'user'(사용자가 누른 로그아웃) / null (WD-830).
 * 이동은 막지 않고 안내만 지킨다 — 떠나기 확인(useUnsavedChangesGuard)을 취소해 페이지가 남아도 다음 로그아웃·만료가 다시
 * 로그인 화면으로 가야 하고(WD-830 리뷰), 리로드 전 늦게 401 을 받은 요청이 먼저 맡긴 안내를 덮지 않아야 한다.
 * 페이지가 바뀌면(리로드) 모듈과 함께 초기화되므로 따로 풀지 않는다.
 */
let noticeOwner: 'auto' | 'user' | null = null;

/**
 * 세션이 401 만료로 끝난 뒤 남은 첫 만료 오류 (WD-1082).
 *
 * <p>세션을 끝낸 뒤에도 화면(떠나기 확인 취소)에 남으면, 뒤에 붙는 조회가 다시 401 을 받아 새 갱신 사이클을
 * 시작하고 그 실패가 다시 endSessionAndReloadToLogin 을 불러 확인 창을 되풀이한다. refresh 401 은 되돌릴 수
 * 없으므로(쿠키가 이미 죽었다) 같은 만료를 갱신으로 확인할 필요는 없다 — 첫 오류를 그대로 돌려준다.
 * 표식은 **HTTP 갱신만** 막는다 — 로그인 이동은 남긴다(아래 refreshAccessToken). 이동까지 막으면 첫 이동을
 * 떠나기 확인으로 취소한 탭이 가드를 푼 뒤에도 /login 으로 가지 못하고 죽은 화면에 남는다(WD-830 위반).
 * 값이 있는 토큰이 저장되면(로그인·회복) 세션이 살아난 것이므로 비운다(WD-830 의 뒤의 만료가 안내를 쓸 수 있게).
 * 네트워크·서버 오류로 실패한 갱신은 표식을 켜지 않는다 — 그때는 오늘처럼 다시 시도한다.
 */
let sessionExpiredError: unknown = null;

/**
 * 만료 이동을 마지막으로 건 화면(pathname) (WD-1082 반려).
 *
 * <p>표식이 켜진 뒤의 갱신 거절은 로그인 이동을 다시 건다 — 첫 이동이 떠나기 확인으로 취소돼 탭이 남아 있어도,
 * 사용자가 화면을 옮길 때는 반드시 로그인 화면으로 가야 하기 때문이다(WD-830 「이동은 늘 다시 한다」).
 * 다만 **화면이 그대로인 배경 요청**이 이동을 되풀이해 건드리면 확인 창이 다시 떠 취소한 사용자를 계속 괴롭힌다
 * (WD-1082 완료 기준 — 확인 창은 처음 한 번). 그래서 마지막으로 이동을 건 화면을 기억해 두고, 그 화면의
 * 요청에는 이동을 다시 건드리지 않는다. 화면이 바뀌면(사용자의 이동) 다시 건다.
 */
let expiredNavPath: string | null = null;

/** 시험 전용 — 모듈 수준 안내 주인이 시험 사이에 남아 뒤 시험의 안내 단언이 어긋나지 않게 푼다(WD-830 리뷰). */
export function resetNoticeOwnerForTest() {
  noticeOwner = null;
}

/**
 * 만료 표식과 그 이동 기록을 비운다 (WD-1082 반려).
 *
 * <p>세션이 살아났을 때 부른다 — 이 탭의 로그인(`setAccessToken(<값>)`)과, 다른 탭이 다시 로그인해
 * 공유 refresh 쿠키가 살아난 경우(`AuthContext` 의 storage 이벤트 핸들러)가 그때다. 표식이 남아 있으면
 * 이 탭은 HTTP 갱신을 다시 시도하지 못해, 되살릴 수 있는 세션을 죽은 채로 두고 /login 으로만 가게 된다.
 *
 * <p>keepSwitched (WD-1122): 다른 탭의 로그인은 이 탭에 「다른 사용자 감지」 표식이 켜진 바로 그 원인일 수 있다.
 * 그때 표식까지 지우면 이 탭이 다른 사용자의 쿠키로 다시 갱신해 앞 사용자의 캐시·폼을 든 채 새 사용자로 동작하게 되므로,
 * storage 핸들러는 true 로 불러 UserSwitchedError 표식만 남긴다(일반 만료 표식만 비운다).
 */
export function clearSessionExpiredError({ keepSwitched = false }: { keepSwitched?: boolean } = {}) {
  if (keepSwitched && sessionExpiredError instanceof UserSwitchedError) return;
  sessionExpiredError = null;
  expiredNavPath = null;
}

/**
 * 사용자 로그아웃을 시작했음을 먼저 맡긴다 — 서버 로그아웃 응답을 기다리는 동안 다른 요청의 401 이 「만료」 안내로 이동해
 * 사용자 로그아웃의 안내(예: 비밀번호 변경 뒤 다시 로그인)를 덮지 않게, 기다리기 전에 부른다(WD-830 리뷰).
 */
export function claimUserLogout(notice?: string) {
  noticeOwner = 'user';
  if (notice) setLoginNotice(notice);
  else clearLoginNotice();
}

/**
 * 세션을 끝내고 로그인 화면으로 **하드 리로드**한다 — 로그아웃·다른 탭 로그아웃·refresh 실패가 모두 이 길로 간다(WD-276).
 *
 * <p>상태만 비우고 라우터로 보내면 두 가지가 남는다 — ① react-query 캐시(staleTime 30초 동안 재요청 없이
 * 그려진다)라 같은 브라우저에서 다음 사람이 로그인하면 앞 사람이 받아 둔 데이터(권한 없는 화면 포함)가
 * 보이고, ② ProtectedRoute 가 로그아웃 순간의 주소를 딥링크(state.from, #675)로 실어 보내 다음 사람이
 * 앞 사람이 보던 화면으로 바로 이동한다. 워크스페이스 전환(AuthProvider 의 selectTenant)과 같은
 * 이유로 리로드 한 번이 캐시·401 재시도 큐·열린 SSE·history state 를 전부 비운다.
 *
 * <p>notice 는 로그인 화면에 한 번 보일 안내(WD-830). 없으면 남아 있던 낡은 안내를 비운다.
 * 안내는 먼저 맡긴 쪽을 지키고(규칙은 noticeOwner) 이동은 매번 한다. userInitiated(사용자가 누른 로그아웃)는 늘 안내를 정한다 —
 * 앞선 자동 종료의 「만료」 안내가 직접 로그아웃 뒤에 남지 않게(WD-830 리뷰).
 */
export function endSessionAndReloadToLogin(notice?: string, { userInitiated = false }: { userInitiated?: boolean } = {}) {
  if (userInitiated) {
    claimUserLogout(notice);
  } else if (noticeOwner === null) {
    noticeOwner = 'auto';
    if (notice) setLoginNotice(notice);
    else clearLoginNotice();
  }
  // 이미 맡긴 안내는 덮지 않되 이동은 늘 다시 한다 — 떠나기 확인을 취소한 뒤의 로그아웃·만료가 조용히 막히지 않게
  accessToken = null;
  localStorage.removeItem(AUTH_FLAG_KEY);
  // replace — 로그아웃 직전 화면을 history 에 남기지 않는다. 남기면 다음 사람이 「뒤로」로 그 주소를 다시 열어
  // 딥링크로 되살리거나 bfcache 로 앞 사람 화면을 그대로 복원할 수 있다.
  window.location.replace('/login');
}

export const client = axios.create({
  baseURL: '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true,
});

client.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

let isRefreshing = false;

/**
 * 토큰 갱신 결과 (WD-631).
 * - `{ token }` — 갱신 성공, 새 액세스 토큰
 * - `{ demoted: true }` — 갱신은 됐지만 테넌트가 강등(activeTenantId=null)돼 원래 요청을 다시 보내면 안 된다
 *
 * 강등을 오류가 아닌 결과로 돌려주는 이유: 강등 시 무엇으로 실패할지는 **각 호출자**가 정해야 한다. 갱신을 시작한
 * 요청의 오류로 큐 전체를 실패시키면 axios 대기 요청이 채팅의 AIChatHttpError 를, 채팅 대기 요청이 AxiosError 를 받게 된다.
 */
export type RefreshResult = { token: string; demoted?: false } | { demoted: true };

let failedQueue: Array<{
  resolve: (result: RefreshResult) => void;
  reject: (error: unknown) => void;
}> = [];

const MAX_QUEUE_SIZE = 100;

/** 대기 요청을 모두 같은 갱신 결과(새 토큰 또는 강등)로 완료한다 */
function resolveQueue(result: RefreshResult) {
  failedQueue.forEach((prom) => prom.resolve(result));
  failedQueue = [];
}

/** 대기 요청을 모두 갱신 실패 오류로 실패시킨다 */
function rejectQueue(error: unknown) {
  failedQueue.forEach((prom) => prom.reject(error));
  failedQueue = [];
}

/**
 * 액세스 토큰을 갱신한다 — axios 응답 인터셉터와 fetch 로 직접 보내는 AI 채팅 스트림(streamAIChat, WD-631)이 **같은** 경로를 쓴다.
 *
 * <p>공유하는 이유: 갱신을 따로 두면 탭 사이 잠금·동시 갱신 큐를 우회하는 두 번째 refresh 가 생기고, 회전된 refresh 토큰을
 * 두 요청이 다투다 진 쪽 401 이 세션 종료(모든 탭 로그아웃)가 된다(WD-438 과 같은 모양).
 *
 * - 이미 다른 요청이 갱신 중이면 큐에서 기다렸다가 같은 결과를 받는다(대기 수 상한을 넘으면 'Too many queued requests' 로 실패).
 * - 갱신 성공 → 새 토큰을 저장하고 테넌트 상태를 AuthContext 로 흘린 뒤 `{ token }`.
 * - 갱신은 됐지만 테넌트가 강등 → 세션은 살아 있으므로 끝내지 않고 `{ demoted: true }` — 호출자가 자기 오류로 실패한다.
 * - 갱신 실패 → 대기 요청을 모두 그 오류로 실패시키고 세션을 끝낸다(로그인 화면으로 하드 리로드).
 */
export function refreshAccessToken(): Promise<RefreshResult> {
  // 만료로 세션을 이미 끝낸 뒤다 — 같은 만료를 갱신으로 다시 확인할 일이 없어 HTTP 갱신 요청을 만들지 않는다(WD-1082).
  // 다만 로그인 이동은 남긴다(WD-830 「이동은 늘 다시 한다」) — 가드로 첫 이동이 막힌 탭이 가드를 푼 뒤
  // 화면을 옮길 때 비로소 로그인 화면으로 가게 하려는 것이다(위 expiredNavPath · 아래 sessionExpiredError 주석).
  if (sessionExpiredError !== null) {
    const path = window.location.pathname;
    // 화면이 바뀌었거나, 떠나기를 막는 것(dirty 가드·스트리밍/업로드 중 beforeunload)이 풀렸으면 이동을 다시 건다(WD-1122) —
    // 같은 화면이어도 모두 풀렸다면 첫 이동이 취소된 채 죽은 화면에 남은 탭이므로 확인 창을 되풀이할 위험이 없다.
    // 하나라도 걸려 있으면 다시 걸지 않는다.
    if (path !== expiredNavPath || !hasActiveLeaveGuard()) {
      expiredNavPath = path;
      if (sessionExpiredError instanceof UserSwitchedError) window.location.replace('/');
      else endSessionAndReloadToLogin(SESSION_EXPIRED_NOTICE);
    }
    return Promise.reject(sessionExpiredError);
  }
  if (isRefreshing) {
    if (failedQueue.length >= MAX_QUEUE_SIZE) {
      return Promise.reject(new Error('Too many queued requests'));
    }
    return new Promise<RefreshResult>((resolve, reject) => {
      failedQueue.push({ resolve, reject });
    });
  }
  return runRefresh();
}

/**
 * 서버가 내 멤버십을 바꾼 뒤(소유권 이전 등, WD-1132) 새 상태를 읽으려는 **최선 노력** 갱신.
 *
 * <p>{@link refreshAccessToken} 과 다른 점 두 가지.
 * (1) 진행 중인 갱신에 합류하지 않는다 — 그 갱신은 서버 변경 <b>이전</b> 멤버십을 읽었을 수 있어 전환기 라벨이 낡는다.
 *     진행 중인 갱신이 끝나기를 기다린 뒤 같은 잠금(isRefreshing)·대기열 아래에서 **새로** 한 번 갱신한다.
 * (2) 401(refresh 토큰 무효)이 아닌 실패(네트워크·5xx)는 세션을 끝내지 않고 호출자에게 거절만 한다 — 이미 성공한 작업 뒤의 부가 갱신이라
 *     일시 장애로 로그인 화면으로 쫓아내면 안 된다. 401 은 평소처럼 세션을 끝낸다.
 */
export async function refreshAccessTokenBestEffort(): Promise<RefreshResult> {
  // 진행 중인 갱신이 끝나기를 기다린다(합류한 결과는 버린다). 대기열이 가득 차 즉시 거절돼도 무한히 돌지 않게 횟수를 제한한다.
  for (let i = 0; isRefreshing && i < 5; i += 1) {
    await refreshAccessToken().catch(() => undefined);
  }
  if (isRefreshing) {
    throw new Error('Refresh already in progress');
  }
  // 만료로 세션을 이미 끝낸 뒤라면 평소 경로(이동·거절)를 그대로 따른다.
  if (sessionExpiredError !== null) {
    return refreshAccessToken();
  }
  return runRefresh({ bestEffort: true });
}

/** 실제 갱신 한 번 — refreshAccessToken 이 갱신 중이 아닐 때만 부른다. bestEffort 면 401 이 아닌 실패에 세션을 끝내지 않는다. */
async function runRefresh(options: { bestEffort?: boolean } = {}): Promise<RefreshResult> {
  isRefreshing = true;
  try {
    let data: TokenResponse;
    let tenantId: number | null;
    try {
      // 다른 탭의 갱신과 겹치지 않게 탭 사이 잠금 안에서 보낸다(WD-438) — 겹치면 진 쪽 401 이 모든 탭 로그아웃이 된다.
      ({ data } = await withCrossTabRefreshLock(() =>
        axios.post<TokenResponse>('/api/v1/auth/refresh', null, {
          withCredentials: true,
        }),
      ));

      // 다른 사용자의 토큰이면 새 토큰을 쓰지 않는다(WD-1122) — 저장·테넌트 게시·대기 요청 재개보다 먼저 판정한다.
      // 둘 다 디코드된 때만 비교하고, 로그아웃(hasSession 삭제)은 하지 않고 `/` 로 하드 이동해 캐시·폼을 폐기한다.
      if (isOtherSessionUser(data.accessToken)) {
        const switched = abandonForSwitchedUser();
        rejectQueue(switched);
        throw switched;
      }

      tenantId = data.activeTenantId ?? null;
      setAccessToken(data.accessToken);
      // 새 토큰의 테넌트 상태를 AuthContext 로 흘린다. refresh 는 멤버십/테넌트 상태를 재검증해
      // 정지된 경우 activeTenantId=null 로 **강등**하는데, 그 신호를 여기서 버리면 UI 는 이유를
      // 모른 채 전 API 403 루프에 빠진다(tenant-session.ts 주석 참조).
      publishTenantSession({
        activeTenantId: tenantId,
        memberships: data.memberships ?? [],
        // 강등 직후 선택 화면이 정지 사유를 보여줄 수 있게 함께 흘린다(WD-281). 빠뜨리면 백그라운드
        // refresh 가 컨텍스트의 목록을 비워 「참여 중인 워크스페이스가 없습니다」로 되돌아간다.
        unavailableWorkspaces: data.unavailableWorkspaces ?? [],
      });
    } catch (refreshError) {
      // 다른 사용자 감지는 위에서 이미 큐·표식·이동을 끝냈다 — 로그아웃 경로(hasSession 삭제)로 흘리지 않는다(WD-1122)
      if (refreshError instanceof UserSwitchedError) throw refreshError;
      rejectQueue(refreshError);
      // 401 만료인지 한 번 판정해 표식과 안내에 함께 쓴다 — 같은 오류를 두 번 판정하지 않게.
      const isSessionExpired = isUnauthorizedError(refreshError);
      // 401 만료로 세션을 끝낸 뒤의 첫 오류를 남긴다 — 이후 갱신을 막아 확인 창을 되풀이하지 않게(WD-1082). 네트워크·서버 오류는 켜지 않는다.
      // 이동을 건 화면도 함께 남긴다 — 같은 화면의 배경 요청은 뒤에 이동을 다시 건드리지 않게(아래 refreshAccessToken).
      // 탭 사이 잠금(Web Locks)이 없는 환경(평문 http 배포 등)의 401 도 잠금 있는 환경과 똑같이 표식을 켠다 — 되확인·재시도는 없다(WD-1122).
      // 회전 경쟁에서 진 탭의 401 이 이미 hasSession 을 지워(아래 endSessionAndReloadToLogin) 이긴 탭까지 로그아웃시키므로,
      // 다시 보내 되살리는 길은 없다. 잠금 없는 배포에서 회전 경쟁의 패자 탭이 세션을 끝내는 것은 WD-1122 이전부터의 동작이며 이번 범위 밖이다.
      if (isSessionExpired) {
        sessionExpiredError = refreshError;
        expiredNavPath = window.location.pathname;
      }
      // 최선 노력 갱신(WD-1132)의 일시 장애(401 아님)는 세션을 끝내지 않고 거절만 한다.
      if (options.bestEffort && !isSessionExpired) {
        throw refreshError;
      }
      // WD-830: 401 만 세션 만료로 안내한다(근거는 login-notice.ts)
      endSessionAndReloadToLogin(isSessionExpired ? SESSION_EXPIRED_NOTICE : undefined);
      throw refreshError;
    }

    // 강등됐다면 대기 중인 요청을 재시도하지 않는다. 테넌트 없는 토큰으로 보내면 GUC 가 비어
    // 전부 403 이 확정이고, 그 사이 게이트가 다시 그려질 뿐이다 — 확실히 실패할 요청 뭉치를
    // 보내 403 폭탄과 그만큼의 에러 토스트를 만드는 대신 여기서 끊는다(각 호출자가 자기 오류로 실패).
    const result: RefreshResult = tenantId === null ? { demoted: true } : { token: data.accessToken };
    resolveQueue(result);
    return result;
  } finally {
    isRefreshing = false;
  }
}

/**
 * authedFetch 가 401 뒤 토큰 갱신에 실패했음을 나타낸다 — fetch 자체의 실패(네트워크·중단)와 호출자가 구분할 수 있게 감싼다.
 * 원래 오류는 cause 에 둔다.
 */
export class TokenRefreshError extends Error {
  constructor(cause: unknown) {
    super('Token refresh failed', { cause });
    this.name = 'TokenRefreshError';
  }
}

/**
 * 액세스 토큰을 실어 fetch 하고, 401 이면 공유 갱신 경로(refreshAccessToken)로 한 번 갱신해 **한 번만** 다시 보낸다 (WD-631).
 * SSE 처럼 axios 가 아닌 fetch 로 직접 보내야 하는 요청용 — axios 인터셉터의 401 처리를 받지 못하기 때문이다.
 * (useJobProgress·useNotificationStream 도 fetch 로 직접 보내므로 이 함수로 옮길 수 있다.)
 *
 * - 401 이 아니면 첫 응답을 그대로 돌려준다(본문은 읽지 않는다).
 * - 갱신 성공 → 새 토큰으로 같은 요청을 다시 보낸 응답(그것도 401 일 수 있다 — 더 재시도하지 않는다).
 * - 테넌트 강등 → 다시 보내지 않고 첫 401 응답과 `demoted: true`. 호출자는 그 본문의 서버 문구를 쓸 수 있다.
 * - 갱신 실패(세션 종료 중)·대기 초과 → {@link TokenRefreshError} 로 reject(네트워크 실패·중단은 fetch 오류 그대로).
 *
 * 응답 하나가 아니라 `{ response, demoted }` 를 돌려주는 이유: 강등의 401 과 재시도 후 401 은 상태 코드가 같아 호출자가 구분할 수 없다.
 */
export async function authedFetch(
  url: string,
  init: RequestInit = {},
): Promise<{ response: Response; demoted: boolean }> {
  /** 주어진 토큰을 Authorization 에 실어 한 번 보낸다 — 첫 요청과 재시도가 같은 init(본문·중단 신호)을 쓴다 */
  const send = (token: string | null) => {
    const headers = new Headers(init.headers);
    headers.set('Authorization', `Bearer ${token}`);
    return fetch(url, { ...init, headers });
  };

  const first = await send(getAccessToken());
  if (first.status !== 401) return { response: first, demoted: false };
  let result: RefreshResult;
  try {
    result = await refreshAccessToken();
  } catch (error) {
    throw new TokenRefreshError(error);
  }
  if (result.demoted) return { response: first, demoted: true };
  return { response: await send(result.token), demoted: false };
}

client.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    if (error.response?.status === 401 && !originalRequest._retry) {
      // /auth/login 또는 /auth/refresh 요청의 401은 토큰 갱신 시도 없이 그대로 reject
      // — 로그인 시도 실패 시 무한 refresh 루프와 강제 리다이렉트를 방지하기 위함
      const url = originalRequest.url ?? '';
      if (url.includes('/auth/login') || url.includes('/auth/refresh')) {
        return Promise.reject(error);
      }

      // 갱신을 직접 시작하는 요청만 재시도 표식을 단다(큐에서 기다리는 요청은 예전처럼 표식 없이 재시도)
      if (!isRefreshing) originalRequest._retry = true;

      // 갱신(또는 진행 중인 갱신 대기) — 갱신 실패면 그 오류로 reject 된다(세션 종료는 갱신 경로가 한다)
      const result = await refreshAccessToken();
      // 테넌트 강등이면 재시도하지 않고 이 요청 자신의 401 오류로 실패한다
      if (result.demoted) return Promise.reject(error);
      originalRequest.headers.Authorization = `Bearer ${result.token}`;
      return client(originalRequest);
    }

    // 비밀번호 변경 강제(WD-2): 서버가 403 PASSWORD_CHANGE_REQUIRED 를 주면 변경 화면으로 보낸다.
    // 코드로만 구분한다 — select-tenant 의 일반 403(코드 없음)은 호출자(useTenantSelection)가 처리해야
    // 한다. 이미 변경 화면이면 이동하지 않는다(무한 리로드 방지). 하드 이동인 이유: 부팅 refresh 가
    // 서버의 최신 표식을 다시 읽게 하려는 것.
    if (
      error.response?.status === 403 &&
      error.response.data?.code === 'PASSWORD_CHANGE_REQUIRED' &&
      window.location.pathname !== '/change-password'
    ) {
      window.location.assign('/change-password');
    }

    return Promise.reject(error);
  }
);
