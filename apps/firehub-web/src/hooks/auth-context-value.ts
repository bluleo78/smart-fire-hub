import { createContext } from 'react';
import type { UserResponse } from '../types/auth';
import type { LoginFormData, SignupFormData } from '../lib/validations/auth';

export interface AuthContextValue {
  user: UserResponse | null;
  isLoading: boolean;
  isAuthenticated: boolean;
  login: (data: LoginFormData) => Promise<void>;
  signup: (data: SignupFormData) => Promise<void>;
  logout: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);
