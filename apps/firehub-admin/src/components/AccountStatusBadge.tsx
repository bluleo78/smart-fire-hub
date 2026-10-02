import { StatusBadge } from '@/components/ui/status-badge';

/**
 * 전역 계정 활성/비활성 배지(#784). 비활성 계정은 운영자가 찾아 재활성화해야 하는 행이라 회색(inactive)으로
 * 죽이지 않고 warning 으로 둔다 — `TenantStatusBadge` 의 D-2 판단과 같다.
 */
export function AccountStatusBadge({ active }: { active: boolean }) {
  return <StatusBadge type={active ? 'active' : 'warning'}>{active ? '활성' : '비활성'}</StatusBadge>;
}
