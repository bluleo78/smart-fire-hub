import { createContext } from 'react';

import type { PlatformMeResponse, PlatformSignupRequest } from '../types/platform';

export interface AuthContextValue {
  me: PlatformMeResponse | null;
  /** 현재 세션의 플랫폼 권한 코드 목록. 메뉴·버튼 렌더 판단에만 쓴다(인가는 서버가 한다). */
  permissions: string[];
  isLoading: boolean;
  isAuthenticated: boolean;
  login: (username: string, password: string) => Promise<void>;
  /** 사용자 0명일 때 첫 운영자를 만들고 곧바로 로그인 상태가 된다(WD-749). */
  signup: (data: PlatformSignupRequest) => Promise<void>;
  logout: () => Promise<void>;
  hasPermission: (code: string) => boolean;
}

export const AuthContext = createContext<AuthContextValue | null>(null);
