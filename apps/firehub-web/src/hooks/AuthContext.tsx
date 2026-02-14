import { useState, useEffect, useCallback, useMemo } from 'react';
import type { ReactNode } from 'react';
import type { UserResponse } from '../types/auth';
import type { RoleResponse } from '../types/role';
import type { LoginFormData, SignupFormData } from '../lib/validations/auth';
import { authApi } from '../api/auth';
import { usersApi } from '../api/users';
import { setAccessToken } from '../api/client';
import { AuthContext } from './auth-context-value';

const AUTH_FLAG_KEY = 'hasSession';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(null);
  const [roles, setRoles] = useState<RoleResponse[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  const isAuthenticated = user !== null;

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
    const initAuth = async () => {
      if (!localStorage.getItem(AUTH_FLAG_KEY)) {
        setIsLoading(false);
        return;
      }

      try {
        const { data: tokens } = await authApi.refresh();
        setAccessToken(tokens.accessToken);
        await fetchUserWithRoles();
      } catch {
        setAccessToken(null);
        localStorage.removeItem(AUTH_FLAG_KEY);
      } finally {
        setIsLoading(false);
      }
    };

    initAuth();
  }, [fetchUserWithRoles]);

  const login = useCallback(async (data: LoginFormData) => {
    const { data: tokens } = await authApi.login(data);
    setAccessToken(tokens.accessToken);
    localStorage.setItem(AUTH_FLAG_KEY, 'true');
    await fetchUserWithRoles();
  }, [fetchUserWithRoles]);

  const signup = useCallback(async (data: SignupFormData) => {
    await authApi.signup({
      ...data,
      email: data.email || undefined,
    });
    await login(data);
  }, [login]);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      setAccessToken(null);
      localStorage.removeItem(AUTH_FLAG_KEY);
      setUser(null);
      setRoles([]);
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

  return (
    <AuthContext value={{
      user,
      roles,
      isLoading,
      isAuthenticated,
      login,
      signup,
      logout,
      hasRole,
      isAdmin,
      refreshUser,
    }}>
      {children}
    </AuthContext>
  );
}
