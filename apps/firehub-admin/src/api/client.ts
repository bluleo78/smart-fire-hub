import axios from 'axios';

import type { PlatformTokenResponse } from '../types/platform';

/** 로그인 상태 플래그. firehub-web 의 `hasSession` 과 **반드시 달라야** 한다(R-5). */
export const AUTH_FLAG_KEY = 'hasAdminSession';

let accessToken: string | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

/** 이 앱은 `/api/platform/**` 만 호출한다. 테넌트 평면(`/api/v1`)을 부르면 평면 필터가 403 을 준다. */
export const client = axios.create({
  baseURL: '/api/platform',
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,
});

client.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

let isRefreshing = false;
let failedQueue: Array<{ resolve: (token: string) => void; reject: (error: unknown) => void }> = [];

const MAX_QUEUE_SIZE = 100;

function processQueue(error: unknown, token: string | null = null) {
  failedQueue.forEach((prom) => {
    if (error) prom.reject(error);
    else prom.resolve(token!);
  });
  failedQueue = [];
}

client.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    if (error.response?.status === 401 && !originalRequest._retry) {
      // 로그인/갱신 요청 자체의 401 은 갱신을 재시도하지 않는다 — 무한 루프와 강제 리다이렉트 방지.
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
        const { data } = await axios.post<PlatformTokenResponse>(
          '/api/platform/auth/refresh',
          null,
          { withCredentials: true },
        );
        setAccessToken(data.accessToken);
        originalRequest.headers.Authorization = `Bearer ${data.accessToken}`;
        processQueue(null, data.accessToken);
        return client(originalRequest);
      } catch (refreshError) {
        processQueue(refreshError, null);
        setAccessToken(null);
        localStorage.removeItem(AUTH_FLAG_KEY);
        window.location.href = '/login';
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    return Promise.reject(error);
  },
);
