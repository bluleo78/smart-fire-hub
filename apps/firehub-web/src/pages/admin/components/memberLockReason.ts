import type { UserDetailResponse } from '../../../types/user';

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
  if (!isCallerAdmin && target.roles.some((r) => r.isSystem && r.name === 'ADMIN')) {
    return `시스템 ADMIN 역할을 가진 멤버는 ADMIN 만 ${action}할 수 있습니다`;
  }
  return null;
}
