import { client } from './client';
import type { UserDetailResponse, UpdateProfileRequest, ChangePasswordRequest, SetRolesRequest, SetActiveRequest } from '../types/user';
import type { UserResponse } from '../types/auth';
import type { PageResponse } from '../types/common';

export const usersApi = {
  getMe: () => client.get<UserDetailResponse>('/users/me'),
  updateMe: (data: UpdateProfileRequest) => client.put('/users/me', data),
  changePassword: (data: ChangePasswordRequest) => client.put('/users/me/password', data),
  getUsers: (params: { search?: string; page?: number; size?: number }) =>
    client.get<PageResponse<UserResponse>>('/users', { params }),
  getUserById: (id: number) => client.get<UserDetailResponse>(`/users/${id}`),
  setUserRoles: (id: number, data: SetRolesRequest) => client.put(`/users/${id}/roles`, data),
  setUserActive: (id: number, data: SetActiveRequest) => client.put(`/users/${id}/active`, data),
};
