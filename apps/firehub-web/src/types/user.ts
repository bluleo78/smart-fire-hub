import type { RoleResponse } from './role';

export interface UserDetailResponse {
  id: number;
  username: string;
  email: string | null;
  name: string;
  isActive: boolean;
  /** 전역 계정 활성 여부(WD-3). 관리 상세에서 isActive(이 워크스페이스 멤버십)와 다를 수 있다. */
  accountActive?: boolean;
  createdAt: string;
  roles: RoleResponse[];
  /** 관리 상세에서 이 워크스페이스 멤버십 라벨. 자기 프로필(/users/me)에서는 null. */
  membershipRole?: 'OWNER' | 'ADMIN' | 'MEMBER' | null;
  /** 이 사용자가 이 워크스페이스의 마지막 활성 ADMIN 인가 — 정지·제거 버튼 비활성 판단용(최종 판정은 서버). */
  lastActiveAdmin?: boolean;
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

/** 멤버 추가 요청(POST /users). USER 역할은 서버가 항상 더한다. */
export interface AddMemberRequest {
  email: string;
  name: string;
  temporaryPassword: string;
  roleIds: number[];
}

/** created=true 면 새 계정(임시 비밀번호 1회 표시), false 면 기존 계정을 붙였다. */
export interface AddMemberResponse {
  userId: number;
  created: boolean;
}
