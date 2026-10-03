import { Badge } from '@/components/ui/badge';

/** 멤버십 역할(V81 CHECK: OWNER/ADMIN/MEMBER)의 한국어 라벨(WD-15). 표시용 라벨이라 인가와 무관하다. */
const ROLE_LABELS: Record<string, string> = { OWNER: '소유자', ADMIN: '관리자', MEMBER: '멤버' };

/** 알 수 없는 값은 원문을 보인다 — 서버가 새 역할을 주면 숨기지 않고 드러낸다. title 에 원문 코드. */
export function MembershipRoleBadge({ role }: { role: string }) {
  return (
    <Badge variant="outline" title={role}>
      {ROLE_LABELS[role] ?? role}
    </Badge>
  );
}
