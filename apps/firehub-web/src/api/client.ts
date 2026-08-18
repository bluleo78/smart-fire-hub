import axios from 'axios';

import type { TokenResponse } from '../types/auth';
import { publishTenantSession } from './tenant-session';

let accessToken: string | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
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
        const { data } = await axios.post<TokenResponse>('/api/v1/auth/refresh', null, {
          withCredentials: true,
        });
        const newAccessToken = data.accessToken;
        const newTenantId = data.activeTenantId ?? null;

        setAccessToken(newAccessToken);
        // 새 토큰의 테넌트 상태를 AuthContext 로 흘린다. refresh 는 멤버십/테넌트 상태를 재검증해
        // 정지된 경우 activeTenantId=null 로 **강등**하는데, 그 신호를 여기서 버리면 UI 는 이유를
        // 모른 채 전 API 403 루프에 빠진다(tenant-session.ts 주석 참조).
        publishTenantSession({
          activeTenantId: newTenantId,
          memberships: data.memberships ?? [],
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
        setAccessToken(null);
        localStorage.removeItem('hasSession');
        window.location.href = '/login';
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    return Promise.reject(error);
  }
);
