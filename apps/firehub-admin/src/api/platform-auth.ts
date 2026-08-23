import type { PlatformMeResponse, PlatformTokenResponse } from '../types/platform';
import { client } from './client';

export const platformAuthApi = {
  login: (data: { username: string; password: string }) =>
    client.post<PlatformTokenResponse>('/auth/login', data),
  refresh: () => client.post<PlatformTokenResponse>('/auth/refresh'),
  logout: () => client.post<void>('/auth/logout'),
  /** 권한 요구가 없다 — 어떤 권한 조합의 운영자든 자기 세션은 확인할 수 있어야 한다. */
  me: () => client.get<PlatformMeResponse>('/auth/me'),
};
