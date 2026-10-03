/**
 * 워크스페이스 멤버십 역할 코드(V81 CHECK: OWNER/ADMIN/MEMBER)의 한국어 라벨(WD-15).
 * admin 콘솔 `MembershipRoleBadge` 와 같은 용어 — 표시용이라 인가 판단에 쓰지 않는다.
 */
const MEMBERSHIP_ROLE_LABELS: Record<string, string> = { OWNER: '소유자', ADMIN: '관리자', MEMBER: '멤버' };

/** 역할 코드 → 한국어 라벨. 알 수 없는 값은 원문 그대로 — 서버가 새 역할을 주면 숨기지 않고 드러낸다. */
export function membershipRoleLabel(role: string): string {
  return MEMBERSHIP_ROLE_LABELS[role] ?? role;
}
