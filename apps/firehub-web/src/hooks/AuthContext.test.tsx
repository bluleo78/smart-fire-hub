/**
 * AuthContext.completePasswordChange 단위 테스트 (WD-1122, WD-1141)
 *
 * 비밀번호 변경 직후의 갱신 사이에 다른 탭이 다른 사용자로 로그인했다면, 세션을 세우지 않고 `/` 로 하드 이동한 뒤
 * UserSwitchedError 로 끝나야 한다 — 정상 반환하면 ChangePasswordPage 가 성공 토스트·navigate 로 그 이동을 덮는다.
 * 갱신 자체는 같은 탭 갱신 큐를 거치는 client.refreshSession 으로 보내고(WD-1141), 그 응답의 memberships 로
 * tenantOptions 를 채운다.
 */
import { act, renderHook } from '@testing-library/react';
import axios from 'axios';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { authApi } from '../api/auth';
import {
  clearSessionExpiredError,
  endSessionAndReloadToLogin,
  getAccessToken,
  hasSessionEndStarted,
  isOtherSessionUser,
  refreshAccessToken,
  refreshSession,
  setAccessToken,
} from '../api/client';
import { usersApi } from '../api/users';
import { UserSwitchedError } from '../lib/api-error';
import { AuthProvider } from './AuthContext';
import { useAuth } from './useAuth';

vi.mock('../api/auth', () => ({ authApi: { refresh: vi.fn(), logout: vi.fn() } }));
vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  isOtherSessionUser: vi.fn(() => false),
  // completePasswordChange 가 쓰는 갱신 — 목으로 잡아 실제 HTTP 없이 호출 여부·반환 값을 검증한다(WD-1141)
  refreshSession: vi.fn(),
}));
vi.mock('../api/users', () => ({ usersApi: { getMe: vi.fn() } }));

describe('completePasswordChange (WD-1122)', () => {
  const replace = vi.fn();
  const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;

  beforeEach(() => {
    // 앞 시험의 mockReturnValue(true) 가 새지 않게 판정 목을 매번 기본값으로 되돌린다(WD-1122)
    vi.mocked(isOtherSessionUser).mockReturnValue(false);
    localStorage.removeItem('hasSession');
    vi.stubGlobal('location', { ...window.location, replace });
    // 부팅용 deduplicatedRefresh(authApi.refresh) 가 남아 있어도 completePasswordChange 는 refreshSession 을 써야 한다(WD-1141)
    vi.mocked(authApi.refresh).mockResolvedValue({
      data: { accessToken: 'tok', activeTenantId: null, memberships: [] },
    } as never);
    vi.mocked(refreshSession).mockResolvedValue({
      accessToken: 'tok',
      activeTenantId: null,
      memberships: [],
      mustChangePassword: false,
    } as never);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    vi.clearAllMocks();
    clearSessionExpiredError();
    setAccessToken(null);
  });

  it('다른 사용자의 토큰이면 / 로 이동하고 UserSwitchedError 로 reject 한다', async () => {
    vi.mocked(isOtherSessionUser).mockReturnValue(true);
    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(async () => {
      await expect(result.current.completePasswordChange()).rejects.toBeInstanceOf(UserSwitchedError);
    });
    expect(replace).toHaveBeenCalledWith('/');
  });

  it('사용자 바뀜 뒤에는 메모리 토큰이 비고 다음 갱신은 HTTP 요청 없이 UserSwitchedError 로 끝난다 — runRefresh 와 같은 처리', async () => {
    setAccessToken('old-token');
    vi.mocked(isOtherSessionUser).mockReturnValue(true);
    const post = vi.spyOn(axios, 'post');
    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(async () => {
      await expect(result.current.completePasswordChange()).rejects.toBeInstanceOf(UserSwitchedError);
    });
    expect(getAccessToken()).toBeNull();
    await expect(refreshAccessToken()).rejects.toBeInstanceOf(UserSwitchedError);
    expect(post).not.toHaveBeenCalled();
  });

  it('갱신은 client.refreshSession 을 한 번 부르고, 그 응답의 memberships 로 tenantOptions 를 채운다(WD-1141)', async () => {
    const memberships = [{ tenantId: 7, tenantSlug: 'acme', tenantName: '워크스페이스', role: 'OWNER' }];
    vi.mocked(refreshSession).mockResolvedValue({
      accessToken: 'tok',
      activeTenantId: 7,
      memberships,
      mustChangePassword: false,
    } as never);
    // applySession 이 fetchUserWithRoles 로 부르는 /users/me — 응답이 없으면 갱신 후 세션 재설정이 끝나지 않는다
    vi.mocked(usersApi.getMe).mockResolvedValue({
      data: {
        id: 1,
        username: 'user',
        email: 'user@example.com',
        name: '사용자',
        isActive: true,
        createdAt: '2026-01-01T00:00:00',
        roles: [],
      },
    } as never);

    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(async () => {
      await result.current.completePasswordChange();
    });

    expect(refreshSession).toHaveBeenCalledTimes(1);
    // 같은 탭 갱신 큐를 우회하는 부팅용 경로는 쓰지 않는다 — 두 refresh 가 회전된 쿠키를 다투지 않게(WD-1141)
    expect(authApi.refresh).not.toHaveBeenCalled();
    expect(result.current.tenantOptions).toEqual(memberships);
  });

  /**
   * logout() 이 서버 로그아웃 POST 를 보낼 자리를 이 탭의 명시 표식으로 가른다(WD-1141 반려 2차 4).
   *
   * <p>E2E(change-password)는 doRefresh 가 이미 location.replace 를 건 뒤라 logout() 이 아예 실행되지 않아도
   * 요청 0건으로 통과한다 — 그래서 "표식을 읽는 자리" 자체를 이 자리에서 직접 고정한다.
   */
  it('이 탭이 세션 종료를 시작했으면 logout 은 서버 로그아웃 POST 를 건너뛴다', async () => {
    endSessionAndReloadToLogin();
    expect(hasSessionEndStarted()).toBe(true);

    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(async () => {
      await result.current.logout();
    });

    expect(authApi.logout).not.toHaveBeenCalled();
    expect(replace).toHaveBeenCalledWith('/login');
  });

  it('종료 표식이 꺼져 있으면(세션이 살아났으면) logout 은 서버 로그아웃을 보낸다', async () => {
    endSessionAndReloadToLogin();
    // 값 있는 토큰이 저장되면 세션이 살아난 것 — 표식이 꺼지고, 그 뒤의 로그아웃은 서버에도 전달돼야 한다
    setAccessToken('tok');
    expect(hasSessionEndStarted()).toBe(false);

    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(async () => {
      await result.current.logout();
    });

    expect(authApi.logout).toHaveBeenCalledTimes(1);
  });
});
