import type { LoginRequest, SignupRequest, TokenResponse, UserResponse } from '../types/auth';
import { client } from './client';

export const authApi = {
  signup: (data: SignupRequest) => client.post<UserResponse>('/auth/signup', data),
  login: (data: LoginRequest) => client.post<TokenResponse>('/auth/login', data),
  refresh: () => client.post<TokenResponse>('/auth/refresh'),
  logout: () => client.post<void>('/auth/logout'),
  me: () => client.get<UserResponse>('/auth/me'),
};
