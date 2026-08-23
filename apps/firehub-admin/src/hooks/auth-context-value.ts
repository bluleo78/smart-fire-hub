import { createContext } from 'react';

import type { PlatformMeResponse } from '../types/platform';

export interface AuthContextValue {
  me: PlatformMeResponse | null;
  /** 현재 세션의 플랫폼 권한 코드 목록. 메뉴·버튼 렌더 판단에만 쓴다(인가는 서버가 한다). */
  permissions: string[];
  isLoading: boolean;
  isAuthenticated: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  hasPermission: (code: string) => boolean;
}

export const AuthContext = createContext<AuthContextValue | null>(null);
