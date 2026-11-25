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
