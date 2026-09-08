import type { ReactNode } from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { authApi } from '../api/auth';
import { setAccessToken } from '../api/client';
import type { TenantSessionState } from '../api/tenant-session';
import { subscribeTenantSession } from '../api/tenant-session';
import { usersApi } from '../api/users';
import type { LoginFormData, SignupFormData } from '../lib/validations/auth';
import type { TokenResponse, UserResponse } from '../types/auth';
import type { RoleResponse } from '../types/role';
import type { MembershipResponse } from '../types/tenant';
import { AuthContext } from './auth-context-value';

const AUTH_FLAG_KEY = 'hasSession';

// React StrictMode(dev)에서 useEffect가 두 번 실행되어 refresh API가 중복 호출되는 것을 방지.
// 동시 호출 시 동일한 Promise를 반환하여 실제 HTTP 요청은 한 번만 발생한다.
let pendingRefresh: Promise<{ data: TokenResponse }> | null = null;
function deduplicatedRefresh() {
  if (!pendingRefresh) {
    pendingRefresh = authApi.refresh().finally(() => { pendingRefresh = null; });
  }
  return pendingRefresh;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(null);
  const [roles, setRoles] = useState<RoleResponse[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  // 테넌트 상태는 토큰이 말해 주는 것을 그대로 반영한다. localStorage 미러링을 두지 않는 이유:
  // 격리는 서버측 JWT + RLS 가 하고, 미러를 두면 "화면은 A, 토큰은 B" 상태를 만들 수 있다.
  const [activeTenantId, setActiveTenantId] = useState<number | null>(null);
  const [tenantOptions, setTenantOptions] = useState<MembershipResponse[]>([]);

  const isAuthenticated = user !== null;

  /**
   * 토큰이 말해 주는 테넌트 상태를 반영한다. 유입 경로는 셋뿐이다 — 부팅 시 `refresh`, `login`,
   * 그리고 백그라운드 refresh 가 보내는 통지(`subscribeTenantSession`). 세 경로가 같은 함수를
   * 쓰는 이유: 반영 규칙이 갈라지면 그중 하나만 아래의 빈 목록 규칙을 잃어도 알아채기 어렵다.
   * 인자를 `TokenResponse` 가 아니라 {@link TenantSessionState} 로 받는 것도 그래서다(토큰 응답은
   * 구조적으로 이 타입에 대입된다).
   *
   * <p>세 경로 모두 **현재 멤버십 목록을 그대로** 준다(정지·해제로 목록이 비는 경우까지 포함).
   * 그래서 빈 배열도 그대로 반영한다 — "비었으면 덮지 않는다" 로 막으면 멤버십이 전부 해제된
   * 사용자에게 낡은 목록이 남고, 이제는 속하지도 않은 워크스페이스 카드를 눌러 403 만 받는
   * 데드엔드가 된다.
   *
   * <p>`select-tenant` 응답은 `memberships` 를 빈 배열로 주지만 이 함수에 오지 않는다 —
   * {@link selectTenant} 는 상태를 갱신하지 않고 하드 리로드만 걸기 때문이다.
   */
  const applyTenantState = useCallback((state: TenantSessionState) => {
    setActiveTenantId(state.activeTenantId ?? null);
    setTenantOptions(state.memberships ?? []);
  }, []);

  const fetchUserWithRoles = useCallback(async () => {
    const { data: userDetail } = await usersApi.getMe();
    const userResponse: UserResponse = {
      id: userDetail.id,
      username: userDetail.username,
      email: userDetail.email,
      name: userDetail.name,
      isActive: userDetail.isActive,
      createdAt: userDetail.createdAt,
    };
    setUser(userResponse);
    setRoles(userDetail.roles);
    return userDetail;
  }, []);

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
        applyTenantState(tokens);
        // 테넌트 미선택(강등 포함) 상태에서도 `/users/me` 는 통과한다(권한 요구 없음) — 그래서
        // "인증됐지만 테넌트가 없다" 를 표현할 수 있고, 게이트가 로그인 화면 대신 워크스페이스
        // 선택 화면으로 보낼 수 있다. 이때 roles 는 RLS 때문에 자연히 빈 배열이 된다.
        await fetchUserWithRoles();
      } catch {
        if (ignore) return;
        setAccessToken(null);
        localStorage.removeItem(AUTH_FLAG_KEY);
      } finally {
        if (!ignore) setIsLoading(false);
      }
    };

    initAuth();
    return () => { ignore = true; };
  }, [applyTenantState, fetchUserWithRoles]);

  // 백그라운드 refresh(401 재시도 인터셉터)가 토큰을 강등시킬 수 있으므로 그 통지를 구독한다.
  // 구독하지 않으면 강등 후 UI 가 이유 없는 전 API 403 루프에 빠진다(api/tenant-session.ts 참조).
  useEffect(() => subscribeTenantSession(applyTenantState), [applyTenantState]);

  // 다른 탭에서의 로그아웃을 감지해 이 탭도 즉시 인증 상태를 초기화한다(#565).
  // `storage` 이벤트는 변경을 일으킨 탭 자신에게는 발생하지 않고 다른 탭에서만 발생하므로,
  // 여기서 초기화가 일어난다면 그 원인은 항상 "다른 탭의 로그아웃"이다. 액세스 토큰은
  // 탭마다 별도의 JS 메모리에 있어 이 통지 없이는 만료(최대 30분) 전까지 계속 인증된 것처럼
  // 동작한다. ProtectedRoute 가 `isAuthenticated`를 보고 `/login`으로 리다이렉트하므로
  // 여기서는 로컬 상태만 비우면 충분하다.
  useEffect(() => {
    const handleStorage = (event: StorageEvent) => {
      if (event.key !== AUTH_FLAG_KEY || event.newValue !== null) return;
      setAccessToken(null);
      setUser(null);
      setRoles([]);
      setActiveTenantId(null);
      setTenantOptions([]);
    };
    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, []);

  const login = useCallback(async (data: LoginFormData) => {
    const { data: tokens } = await authApi.login(data);
    setAccessToken(tokens.accessToken);
    localStorage.setItem(AUTH_FLAG_KEY, 'true');
    applyTenantState(tokens);
    await fetchUserWithRoles();
  }, [applyTenantState, fetchUserWithRoles]);

  /**
   * 실행 테넌트를 확정하고 **하드 리로드**한다.
   *
   * <p>왜 상태만 갱신하지 않고 리로드하는가: 전환은 이전 테넌트의 데이터가 화면·캐시·열린 스트림에
   * 남아 있으면 안 되는 사건이다. react-query 캐시 전량 폐기 + `client.ts` 의 401 재시도 큐 초기화 +
   * 인터셉터를 우회하는 손수 만든 SSE 3곳(알림·AI 챗·잡 진행률)의 구 토큰 연결 종료를 리로드 한
   * 번이 전부 처리한다. 선택적 무효화로 같은 보장을 만들려면 그 목록을 영구히 최신으로 유지해야
   * 하고, 새 스트림이 추가될 때마다 조용히 새는 곳이 생긴다.
   */
  const selectTenant = useCallback(async (tenantId: number) => {
    const { data: tokens } = await authApi.selectTenant(tenantId);
    setAccessToken(tokens.accessToken);
    localStorage.setItem(AUTH_FLAG_KEY, 'true');
    window.location.assign('/');
  }, []);

  const signup = useCallback(async (data: SignupFormData) => {
    // confirmPassword는 클라이언트 검증 전용이므로 서버 페이로드에서 제외
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { confirmPassword, ...payload } = data;
    await authApi.signup({
      ...payload,
      email: payload.email || undefined,
    });
    await login({ username: data.username, password: data.password });
  }, [login]);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      setAccessToken(null);
      localStorage.removeItem(AUTH_FLAG_KEY);
      setUser(null);
      setRoles([]);
      setActiveTenantId(null);
      setTenantOptions([]);
    }
  }, []);

  const hasRole = useCallback((roleName: string) => {
    return roles.some(r => r.name === roleName);
  }, [roles]);

  const isAdmin = useMemo(() => {
    return roles.some(r => r.name === 'ADMIN');
  }, [roles]);

  const refreshUser = useCallback(async () => {
    await fetchUserWithRoles();
  }, [fetchUserWithRoles]);

  const ctxValue = useMemo(
    () => ({ user, roles, isLoading, isAuthenticated, login, signup, logout, hasRole, isAdmin, refreshUser, activeTenantId, tenantOptions, selectTenant }),
    [user, roles, isLoading, isAuthenticated, login, signup, logout, hasRole, isAdmin, refreshUser, activeTenantId, tenantOptions, selectTenant]
  );

  return (
    <AuthContext value={ctxValue}>
      {children}
    </AuthContext>
  );
}
