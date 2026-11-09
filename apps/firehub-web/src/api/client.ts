import axios from 'axios';

import type { TokenResponse } from '../types/auth';
import { withCrossTabRefreshLock } from './refresh-lock';
import { publishTenantSession } from './tenant-session';

let accessToken: string | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

/** 로그인 세션 표식 — 이 값이 있을 때만 부팅 refresh 를 시도하고, 지우면 다른 탭이 storage 이벤트로 로그아웃을 안다(#565). */
export const AUTH_FLAG_KEY = 'hasSession';

/**
 * 세션을 끝내고 로그인 화면으로 **하드 리로드**한다 — 로그아웃·다른 탭 로그아웃·refresh 실패가 모두 이 길로 간다(WD-276).
 *
 * <p>상태만 비우고 라우터로 보내면 두 가지가 남는다 — ① react-query 캐시(staleTime 30초 동안 재요청 없이
 * 그려진다)라 같은 브라우저에서 다음 사람이 로그인하면 앞 사람이 받아 둔 데이터(권한 없는 화면 포함)가
 * 보이고, ② ProtectedRoute 가 로그아웃 순간의 주소를 딥링크(state.from, #675)로 실어 보내 다음 사람이
 * 앞 사람이 보던 화면으로 바로 이동한다. 워크스페이스 전환(AuthProvider 의 selectTenant)과 같은
 * 이유로 리로드 한 번이 캐시·401 재시도 큐·열린 SSE·history state 를 전부 비운다.
 */
export function endSessionAndReloadToLogin() {
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

/** 실제 갱신 한 번 — refreshAccessToken 이 갱신 중이 아닐 때만 부른다. */
async function runRefresh(): Promise<RefreshResult> {
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
      rejectQueue(refreshError);
      endSessionAndReloadToLogin();
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
