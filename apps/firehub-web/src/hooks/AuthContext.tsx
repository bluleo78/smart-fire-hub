import { useState, useEffect, useCallback } from 'react';
import type { ReactNode } from 'react';
import type { UserResponse } from '../types/auth';
import type { LoginFormData, SignupFormData } from '../lib/validations/auth';
import { authApi } from '../api/auth';
import { setAccessToken } from '../api/client';
import { AuthContext } from './auth-context-value';

const AUTH_FLAG_KEY = 'hasSession';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const isAuthenticated = user !== null;

  useEffect(() => {
    const initAuth = async () => {
      if (!localStorage.getItem(AUTH_FLAG_KEY)) {
        setIsLoading(false);
        return;
      }

      try {
        const { data: tokens } = await authApi.refresh();
        setAccessToken(tokens.accessToken);
        const { data: userData } = await authApi.me();
        setUser(userData);
      } catch {
        setAccessToken(null);
        localStorage.removeItem(AUTH_FLAG_KEY);
      } finally {
        setIsLoading(false);
      }
    };

    initAuth();
  }, []);

  const login = useCallback(async (data: LoginFormData) => {
    const { data: tokens } = await authApi.login(data);
    setAccessToken(tokens.accessToken);
    localStorage.setItem(AUTH_FLAG_KEY, 'true');
    const { data: userData } = await authApi.me();
    setUser(userData);
  }, []);

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
    }
  }, []);

  return (
    <AuthContext value={{
      user,
      isLoading,
      isAuthenticated,
      login,
      signup,
      logout,
    }}>
      {children}
    </AuthContext>
  );
}
