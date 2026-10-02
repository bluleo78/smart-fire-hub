import type { UserResponse } from '../types/auth';
import type { PageResponse } from '../types/common';
import type {
  AddMemberRequest,
  AddMemberResponse,
  ChangePasswordRequest,
  SetActiveRequest,
  SetRolesRequest,
  UpdateProfileRequest,
  UserDetailResponse,
} from '../types/user';
import { client } from './client';

export const usersApi = {
  getMe: () => client.get<UserDetailResponse>('/users/me'),
  updateMe: (data: UpdateProfileRequest) => client.put('/users/me', data),
  changePassword: (data: ChangePasswordRequest) => client.put('/users/me/password', data),
  getUsers: (params: { search?: string; page?: number; size?: number }) =>
    client.get<PageResponse<UserResponse>>('/users', { params }),
  getUserById: (id: number) => client.get<UserDetailResponse>(`/users/${id}`),
  setUserRoles: (id: number, data: SetRolesRequest) => client.put(`/users/${id}/roles`, data),
  setUserActive: (id: number, data: SetActiveRequest) => client.put(`/users/${id}/active`, data),
  /** 멤버 추가(WD-2) — 새 계정이면 임시 비밀번호로 만들고, 기존 계정이면 이 워크스페이스에 붙인다. */
  addMember: (data: AddMemberRequest) => client.post<AddMemberResponse>('/users', data),
  /** 이 워크스페이스에서 제거 — 계정과 만든 데이터는 남는다. */
  removeMember: (id: number) => client.delete(`/users/${id}/membership`),
};
