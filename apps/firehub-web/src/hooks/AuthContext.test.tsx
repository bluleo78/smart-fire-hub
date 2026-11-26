/**
 * AuthContext.completePasswordChange 단위 테스트 (WD-1122)
 *
 * 비밀번호 변경 직후의 갱신 사이에 다른 탭이 다른 사용자로 로그인했다면, 세션을 세우지 않고 `/` 로 하드 이동한 뒤
 * UserSwitchedError 로 끝나야 한다 — 정상 반환하면 ChangePasswordPage 가 성공 토스트·navigate 로 그 이동을 덮는다.
 */
import { act, renderHook } from '@testing-library/react';
import axios from 'axios';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { authApi } from '../api/auth';
import { clearSessionExpiredError, getAccessToken, isOtherSessionUser, refreshAccessToken, setAccessToken } from '../api/client';
import { UserSwitchedError } from '../lib/api-error';
import { registerLeaveBlocker } from '../lib/unsaved-changes-guard-registry';
import { AuthProvider } from './AuthContext';
import { useAuth } from './useAuth';

vi.mock('../api/auth', () => ({ authApi: { refresh: vi.fn() } }));
vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  isOtherSessionUser: vi.fn(() => false),
}));

describe('completePasswordChange (WD-1122)', () => {
  const replace = vi.fn();
  const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;

  beforeEach(() => {
    // 앞 시험의 mockReturnValue(true) 가 새지 않게 판정 목을 매번 기본값으로 되돌린다(WD-1122)
    vi.mocked(isOtherSessionUser).mockReturnValue(false);
    localStorage.removeItem('hasSession');
    vi.stubGlobal('location', { ...window.location, replace });
    vi.mocked(authApi.refresh).mockResolvedValue({
      data: { accessToken: 'tok', activeTenantId: null, memberships: [] },
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
});

describe('다른 탭 로그아웃 storage 핸들러 — 세션 끝 표식 탭 (WD-1160)', () => {
  const replace = vi.fn();
  const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;
  let unregister: (() => void) | null = null;

  /** 다른 탭의 로그아웃 — hasSession 제거 통지 */
  function otherTabLogout() {
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: 'hasSession', newValue: null }));
    });
  }

  /** 사용자 바뀜으로 표식을 켜고(토큰 null) 지금 화면에서 첫 이동을 건 상태로 만든다 */
  async function markSessionEnded() {
    setAccessToken('old-token');
    vi.mocked(isOtherSessionUser).mockReturnValue(true);
    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(async () => {
      await expect(result.current.completePasswordChange()).rejects.toBeInstanceOf(UserSwitchedError);
    });
    replace.mockClear();
  }

  beforeEach(() => {
    vi.mocked(isOtherSessionUser).mockReturnValue(false);
    localStorage.removeItem('hasSession');
    vi.stubGlobal('location', { ...window.location, pathname: '/form', replace });
    vi.mocked(authApi.refresh).mockResolvedValue({
      data: { accessToken: 'tok', activeTenantId: null, memberships: [] },
    } as never);
  });
  afterEach(() => {
    unregister?.();
    unregister = null;
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    vi.clearAllMocks();
    clearSessionExpiredError();
    setAccessToken(null);
  });

  it('같은 화면에서 이미 이동을 걸었고 떠나기 가드가 걸려 있으면 로그인 이동을 다시 걸지 않는다', async () => {
    await markSessionEnded();
    unregister = registerLeaveBlocker(() => true);
    otherTabLogout();
    expect(replace).not.toHaveBeenCalled();
  });

  it('화면이 바뀌었으면 이동을 건다', async () => {
    await markSessionEnded();
    unregister = registerLeaveBlocker(() => true);
    vi.stubGlobal('location', { ...window.location, pathname: '/other', replace });
    otherTabLogout();
    expect(replace).toHaveBeenCalledWith('/login');
  });

  it('가드가 없으면 같은 화면이어도 이동을 건다', async () => {
    await markSessionEnded();
    otherTabLogout();
    expect(replace).toHaveBeenCalledWith('/login');
  });

  it('토큰이 있으면(표식 없음) 가드와 상관없이 지금처럼 이동한다', async () => {
    renderHook(() => useAuth(), { wrapper });
    setAccessToken('live');
    unregister = registerLeaveBlocker(() => true);
    otherTabLogout();
    expect(replace).toHaveBeenCalledWith('/login');
  });
});
