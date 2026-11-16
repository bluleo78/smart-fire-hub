import type { ReactNode } from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { AUTH_FLAG_KEY, cancelQueuedRequests, setAccessToken } from '../api/client';
import { platformAuthApi } from '../api/platform-auth';
import type {
  PlatformMeResponse,
  PlatformSignupRequest,
  PlatformTokenResponse,
} from '../types/platform';
import { AuthContext } from './auth-context-value';
import { SignupSessionError } from './signup-session-error';

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
      try {
        // localStorage 접근 자체가 던질 수 있다(시크릿 모드 저장소 차단 등) — try 밖에 두면
        // setIsLoading(false) 가 영영 안 돌아 화면이 스켈레톤에 영구 고착된다(L1).
        if (!localStorage.getItem(AUTH_FLAG_KEY)) {
          setIsLoading(false);
          return;
        }
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

  /**
   * 로그인·가입 공통 마무리 — 토큰 저장 → me 확정 → 플래그 → setMe 순서다.
   * 플래그는 me() 성공 뒤에 세운다(L2) — 먼저 세우면 me() 실패 시 `hasAdminSession=true`
   * 인데 `me===null` 인 어긋난 상태가 남는다.
   */
  const establishSession = useCallback(async (tokens: PlatformTokenResponse) => {
    setAccessToken(tokens.accessToken);
    const { data: identity } = await platformAuthApi.me();
    localStorage.setItem(AUTH_FLAG_KEY, 'true');
    setMe(identity);
  }, []);

  const login = useCallback(
    async (username: string, password: string) => {
      const { data: tokens } = await platformAuthApi.login({ username, password });
      await establishSession(tokens);
    },
    [establishSession],
  );

  /**
   * 첫 운영자 가입(WD-749) — 응답이 로그인과 같아 곧바로 세션을 연다.
   * 가입 요청의 실패는 그대로 던지고, 가입 성공 뒤 세션 확정(me)이 실패하면 {@link SignupSessionError} 로
   * 감싼다 — 그때 계정은 이미 있으므로 화면이 "로그인해 주세요" 로 안내해야 한다.
   */
  const signup = useCallback(
    async (data: PlatformSignupRequest) => {
      const { data: tokens } = await platformAuthApi.signup(data);
      try {
        await establishSession(tokens);
      } catch (error) {
        // 반쯤 열린 세션(토큰만 있는 상태)을 남기지 않는다.
        setAccessToken(null);
        throw new SignupSessionError(error);
      }
    },
    [establishSession],
  );

  const logout = useCallback(async () => {
    try {
      await platformAuthApi.logout();
    } finally {
      setAccessToken(null);
      // 대기 중인 401 재시도 큐를 비운다(L3) — 안 그러면 로그아웃 이후에도 이미 베어러가
      // 박힌 재시도가 나갈 수 있다.
      cancelQueuedRequests();
      localStorage.removeItem(AUTH_FLAG_KEY);
      setMe(null);
    }
  }, []);

  const hasPermission = useCallback(
    (code: string) => permissions.includes(code),
    [permissions],
  );

  const ctxValue = useMemo(
    () => ({ me, permissions, isLoading, isAuthenticated, login, signup, logout, hasPermission }),
    [me, permissions, isLoading, isAuthenticated, login, signup, logout, hasPermission],
  );

  return <AuthContext value={ctxValue}>{children}</AuthContext>;
}
