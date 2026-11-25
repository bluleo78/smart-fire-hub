import type { UserResponse } from '../types/auth';
import type { PageResponse } from '../types/common';
import type {
  AddMemberRequest,
  AddMemberResponse,
  ChangePasswordRequest,
  MemberLookupResponse,
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
  /**
   * 멤버 추가 전 이메일 존재 확인(WD-751) — 새 계정/기존 계정/이미 멤버/정지 멤버.
   * 이메일(개인정보)이 접근 로그의 URL 에 남지 않도록 GET 쿼리가 아니라 POST 본문으로 보낸다.
   */
  lookupMember: (email: string) => client.post<MemberLookupResponse>('/users/member-lookup', { email }),
  /** 이 워크스페이스에서 제거 — 계정과 만든 데이터는 남는다. */
  removeMember: (id: number) => client.delete(`/users/${id}/membership`),
  /** 워크스페이스 소유권을 이 사용자에게 넘긴다(WD-1132) — confirmName 은 워크스페이스 이름(서버가 trim 후 정확 비교). 204. */
  transferOwnership: (id: number, confirmName: string) => client.post(`/users/${id}/ownership`, { confirmName }),
};
