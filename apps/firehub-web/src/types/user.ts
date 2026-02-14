import type { RoleResponse } from './role';

export interface UserDetailResponse {
  id: number;
  username: string;
  email: string | null;
  name: string;
  isActive: boolean;
  createdAt: string;
  roles: RoleResponse[];
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface UpdateProfileRequest {
  name: string;
  email?: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface SetRolesRequest {
  roleIds: number[];
}

export interface SetActiveRequest {
  active: boolean;
}
