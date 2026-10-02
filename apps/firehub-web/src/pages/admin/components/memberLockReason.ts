import type { UserDetailResponse } from '../../../types/user';

/**
 * 정지·제거를 막는 이유(서버 규칙과 같은 순서: 자기 자신 → OWNER → 마지막 활성 ADMIN). 없으면 null.
 * 웹 표시는 안내일 뿐이고 최종 판정은 서버다(우회돼도 서버가 400/409).
 */
export function memberLockReason(
  target: UserDetailResponse,
  currentUserId: number | undefined,
  action: '정지' | '제거',
): string | null {
  if (currentUserId === target.id) return `자기 자신은 ${action}할 수 없습니다`;
  if (target.membershipRole === 'OWNER') return `워크스페이스 소유자는 ${action}할 수 없습니다`;
  if (target.lastActiveAdmin && target.isActive) return `마지막 활성 ADMIN 은 ${action}할 수 없습니다`;
  return null;
}
