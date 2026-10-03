import { StatusBadge } from '@/components/ui/status-badge';

/**
 * 워크스페이스 멤버십 상태 배지(WD-8). 값은 membership.status — `ACTIVE`/`SUSPENDED` 둘뿐이다(V81 CHECK).
 *
 * 라벨은 웹 사용자 관리 화면이 같은 멤버십 상태에 쓰는 "활성"/"정지" 와 맞춘다(테넌트 정지 "정지됨" 과는 대상이 다르다).
 * 정지 행은 운영자가 찾아야 하는 행이라 회색으로 죽이지 않고 warning — `TenantStatusBadge` 의 D-2 판단과 같다.
 * 서버가 모르는 값을 주면 원문을 unknown 톤으로 보여 준다 — "활성" 으로 둔갑시키면 운영자를 오도한다.
 */
export function MembershipStatusBadge({ status }: { status: string }) {
  if (status === 'ACTIVE') return <StatusBadge type="active">활성</StatusBadge>;
  if (status === 'SUSPENDED') return <StatusBadge type="warning">정지</StatusBadge>;
  return <StatusBadge type="unknown">{status}</StatusBadge>;
}
