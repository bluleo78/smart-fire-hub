import { client } from './client';
import type { SignupRequest, LoginRequest, TokenResponse, UserResponse } from '../types/auth';

export const authApi = {
  signup: (data: SignupRequest) => client.post<UserResponse>('/auth/signup', data),
  login: (data: LoginRequest) => client.post<TokenResponse>('/auth/login', data),
  refresh: () => client.post<TokenResponse>('/auth/refresh'),
  logout: () => client.post('/auth/logout'),
  me: () => client.get<UserResponse>('/auth/me'),
};
