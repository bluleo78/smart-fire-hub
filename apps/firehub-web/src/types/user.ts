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
  /** 화면이 편집을 시작할 때 읽은 역할 목록(WD-440) — 서버 값과 다르면 409 EDIT_CONFLICT. 빼면 비교하지 않는다(덮어쓰기). */
  expectedRoleIds?: number[];
}

export interface SetActiveRequest {
  active: boolean;
}

/**
 * 멤버 추가 요청(POST /users). USER 역할은 서버가 항상 더한다.
 * name·temporaryPassword 는 새 계정일 때만 필수다(WD-751) — 기존 계정이면 서버가 쓰지 않으므로 키째 뺀다.
 */
export interface AddMemberRequest {
  email: string;
  name?: string;
  temporaryPassword?: string;
  roleIds: number[];
}

/**
 * 멤버 추가 전 이메일 존재 확인 결과(POST /users/member-lookup, WD-751).
 * NEW = 계정 없음, EXISTING = 계정 있음·이 워크스페이스 비멤버, MEMBER = 활성 멤버, SUSPENDED = 정지 멤버.
 */
export type MemberLookupStatus = 'NEW' | 'EXISTING' | 'MEMBER' | 'SUSPENDED';

/** userId·name 은 NEW 면 null. SUSPENDED 의 userId 로 상세 링크를 만든다. */
export interface MemberLookupResponse {
  status: MemberLookupStatus;
  userId: number | null;
  name: string | null;
}

/** created=true 면 새 계정(임시 비밀번호 1회 표시), false 면 기존 계정을 붙였다. */
export interface AddMemberResponse {
  userId: number;
  created: boolean;
}
