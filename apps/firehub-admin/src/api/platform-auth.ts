import type {
  PlatformBootstrapStatus,
  PlatformMeResponse,
  PlatformSignupRequest,
  PlatformTokenResponse,
} from '../types/platform';
import { client } from './client';

export const platformAuthApi = {
  login: (data: { username: string; password: string }) =>
    client.post<PlatformTokenResponse>('/auth/login', data),
  /** 공개 — 사용자 0명일 때만 `available=true`. 로그인 화면 진입점·가입 화면 게이트가 쓴다(WD-749). */
  bootstrapStatus: () => client.get<PlatformBootstrapStatus>('/auth/bootstrap-status'),
  /** 공개 — 사용자 0명일 때만 첫 운영자를 만든다. 응답은 로그인과 같다(WD-749). */
  signup: (data: PlatformSignupRequest) =>
    client.post<PlatformTokenResponse>('/auth/signup', data),
  refresh: () => client.post<PlatformTokenResponse>('/auth/refresh'),
  logout: () => client.post<void>('/auth/logout'),
  /** 권한 요구가 없다 — 어떤 권한 조합의 운영자든 자기 세션은 확인할 수 있어야 한다. */
  me: () => client.get<PlatformMeResponse>('/auth/me'),
};
