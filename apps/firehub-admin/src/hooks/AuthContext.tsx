import type { ReactNode } from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { AUTH_FLAG_KEY, setAccessToken } from '../api/client';
import { platformAuthApi } from '../api/platform-auth';
import type { PlatformMeResponse, PlatformTokenResponse } from '../types/platform';
import { AuthContext } from './auth-context-value';

// React StrictMode(dev)에서 useEffect 가 두 번 실행되어 refresh 가 중복 호출되는 것을 막는다.
let pendingRefresh: Promise<{ data: PlatformTokenResponse }> | null = null;
function deduplicatedRefresh() {
  if (!pendingRefresh) {
    pendingRefresh = platformAuthApi.refresh().finally(() => {
      pendingRefresh = null;
    });
  }
  return pendingRefresh;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<PlatformMeResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const isAuthenticated = me !== null;

  /**
   * 권한 목록의 권위는 `GET /auth/me` 다. 로그인 응답에도 `permissions` 가 있지만 그것만 믿으면
   * 권한이 회수된 세션이 부팅 경로(refresh)에서 옛 목록을 그대로 들고 있게 된다 — 두 경로가
   * 같은 출처를 쓰도록 항상 `me()` 로 확정한다.
   */
  const permissions = useMemo(() => me?.permissions ?? [], [me]);

  useEffect(() => {
    let ignore = false;

    const initAuth = async () => {
      if (!localStorage.getItem(AUTH_FLAG_KEY)) {
        setIsLoading(false);
        return;
      }
      try {
        const { data: tokens } = await deduplicatedRefresh();
        if (ignore) return;
        setAccessToken(tokens.accessToken);
        const { data: identity } = await platformAuthApi.me();
        if (ignore) return;
        setMe(identity);
      } catch {
        if (ignore) return;
        setAccessToken(null);
        localStorage.removeItem(AUTH_FLAG_KEY);
      } finally {
        if (!ignore) setIsLoading(false);
      }
    };

    initAuth();
    return () => {
      ignore = true;
    };
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const { data: tokens } = await platformAuthApi.login({ username, password });
    setAccessToken(tokens.accessToken);
    localStorage.setItem(AUTH_FLAG_KEY, 'true');
    const { data: identity } = await platformAuthApi.me();
    setMe(identity);
  }, []);

  const logout = useCallback(async () => {
    try {
      await platformAuthApi.logout();
    } finally {
      setAccessToken(null);
      localStorage.removeItem(AUTH_FLAG_KEY);
      setMe(null);
    }
  }, []);

  const hasPermission = useCallback(
    (code: string) => permissions.includes(code),
    [permissions],
  );

  const ctxValue = useMemo(
    () => ({ me, permissions, isLoading, isAuthenticated, login, logout, hasPermission }),
    [me, permissions, isLoading, isAuthenticated, login, logout, hasPermission],
  );

  return <AuthContext value={ctxValue}>{children}</AuthContext>;
}
