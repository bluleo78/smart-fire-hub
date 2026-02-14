import { createContext } from 'react';
import type { UserResponse } from '../types/auth';
import type { RoleResponse } from '../types/role';
import type { LoginFormData, SignupFormData } from '../lib/validations/auth';

export interface AuthContextValue {
  user: UserResponse | null;
  roles: RoleResponse[];
  isLoading: boolean;
  isAuthenticated: boolean;
  login: (data: LoginFormData) => Promise<void>;
  signup: (data: SignupFormData) => Promise<void>;
  logout: () => Promise<void>;
  hasRole: (roleName: string) => boolean;
  isAdmin: boolean;
  refreshUser: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);
