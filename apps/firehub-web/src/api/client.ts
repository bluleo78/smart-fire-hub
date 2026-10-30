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
let failedQueue: Array<{
  resolve: (token: string) => void;
  reject: (error: unknown) => void;
}> = [];

const MAX_QUEUE_SIZE = 100;

function processQueue(error: unknown, token: string | null = null) {
  failedQueue.forEach((prom) => {
    if (error) {
      prom.reject(error);
    } else {
      prom.resolve(token!);
    }
  });
  failedQueue = [];
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

      if (isRefreshing) {
        if (failedQueue.length >= MAX_QUEUE_SIZE) {
          return Promise.reject(new Error('Too many queued requests'));
        }
        return new Promise<string>((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        }).then((token) => {
          originalRequest.headers.Authorization = `Bearer ${token}`;
          return client(originalRequest);
        });
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        // 다른 탭의 갱신과 겹치지 않게 탭 사이 잠금 안에서 보낸다(WD-438) — 겹치면 진 쪽 401 이 모든 탭 로그아웃이 된다.
        const { data } = await withCrossTabRefreshLock(() =>
          axios.post<TokenResponse>('/api/v1/auth/refresh', null, {
            withCredentials: true,
          }),
        );
        const newAccessToken = data.accessToken;
        const newTenantId = data.activeTenantId ?? null;

        setAccessToken(newAccessToken);
        // 새 토큰의 테넌트 상태를 AuthContext 로 흘린다. refresh 는 멤버십/테넌트 상태를 재검증해
        // 정지된 경우 activeTenantId=null 로 **강등**하는데, 그 신호를 여기서 버리면 UI 는 이유를
        // 모른 채 전 API 403 루프에 빠진다(tenant-session.ts 주석 참조).
        publishTenantSession({
          activeTenantId: newTenantId,
          memberships: data.memberships ?? [],
          // 강등 직후 선택 화면이 정지 사유를 보여줄 수 있게 함께 흘린다(WD-281). 빠뜨리면 백그라운드
          // refresh 가 컨텍스트의 목록을 비워 「참여 중인 워크스페이스가 없습니다」로 되돌아간다.
          unavailableWorkspaces: data.unavailableWorkspaces ?? [],
        });

        // 강등됐다면 대기 중인 요청을 재시도하지 않는다. 테넌트 없는 토큰으로 보내면 GUC 가 비어
        // 전부 403 이 확정이고, 그 사이 게이트가 다시 그려질 뿐이다 — 확실히 실패할 요청 뭉치를
        // 보내 403 폭탄과 그만큼의 에러 토스트를 만드는 대신 여기서 끊는다.
        if (newTenantId === null) {
          processQueue(error, null);
          return Promise.reject(error);
        }

        originalRequest.headers.Authorization = `Bearer ${newAccessToken}`;
        processQueue(null, newAccessToken);

        return client(originalRequest);
      } catch (refreshError) {
        processQueue(refreshError, null);
        endSessionAndReloadToLogin();
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
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
