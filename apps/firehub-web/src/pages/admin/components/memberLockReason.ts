import type { RoleResponse } from '../../../types/role';
import type { UserDetailResponse } from '../../../types/user';

/** 시스템 ADMIN 역할인가 — 같은 이름의 사용자 정의 역할과 구분한다. */
export function isSystemAdminRole(role: Pick<RoleResponse, 'isSystem' | 'name'>): boolean {
  return role.isSystem && role.name === 'ADMIN';
}

/**
 * 정지·제거를 막는 이유(서버 규칙과 같은 순서: 자기 자신 → OWNER → 마지막 활성 ADMIN → 호출자 범위). 없으면 null.
 * 웹 표시는 안내일 뿐이고 최종 판정은 서버다(우회돼도 서버가 400/409/403).
 *
 * 호출자 범위(WD-277)는 역할 할당 화면과 같이 "시스템 ADMIN 은 ADMIN 만" 만 미리 잠근다 — 권한·등급 비교는 화면이 알 수 없으므로 서버 403 문구로 안내한다.
 */
export function memberLockReason(
  target: UserDetailResponse,
  currentUserId: number | undefined,
  action: '정지' | '제거',
  isCallerAdmin: boolean,
): string | null {
  if (currentUserId === target.id) return `자기 자신은 ${action}할 수 없습니다`;
  if (target.membershipRole === 'OWNER') return `워크스페이스 소유자는 ${action}할 수 없습니다`;
  if (target.lastActiveAdmin && target.isActive) return `마지막 활성 ADMIN 은 ${action}할 수 없습니다`;
  if (!isCallerAdmin && target.roles.some(isSystemAdminRole)) {
    return `시스템 ADMIN 역할을 가진 멤버는 ADMIN 만 ${action}할 수 있습니다`;
  }
  return null;
}

/**
 * 역할 할당 화면에서 이 역할 체크를 잠그는 이유. 없으면 null. 서버 판정(UserService.setUserRoles·RoleGrantGuard)을 미리 보여 주는 안내다.
 *
 * - 시스템 ADMIN 은 ADMIN 만 부여·회수(WD-116) — 위임받은 역할 관리자에게는 붙이는 것도 떼는 것도 잠근다.
 * - 워크스페이스 소유자의 ADMIN 은 뺄 수 없다(WD-282, 서버 409) — 지금 ADMIN 을 가진 소유자만 잠근다. 이미 빠진 소유자에게 다시 붙이는 것까지
 *   막으면 되돌릴 길이 없다.
 */
export function roleLockReason(
  role: RoleResponse,
  target: UserDetailResponse,
  isCallerAdmin: boolean,
): string | null {
  if (!isSystemAdminRole(role)) return null;
  if (!isCallerAdmin) return '시스템 ADMIN 역할은 ADMIN 만 부여하거나 회수할 수 있습니다';
  if (target.membershipRole === 'OWNER' && target.roles.some((r) => r.id === role.id)) {
    return '워크스페이스 소유자의 ADMIN 역할은 뺄 수 없습니다';
  }
  return null;
}
