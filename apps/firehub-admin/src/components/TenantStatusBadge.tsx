import { StatusBadge } from '@/components/ui/status-badge';

/**
 * 테넌트 활성/정지 배지. `TenantListPage`/`TenantDetailPage` 세 곳에 동일한 삼항식이
 * 복붙돼 있던 것을 한 곳으로 모았다.
 *
 * 정지 테넌트야말로 운영자가 찾아야 하는 행이라 흐리게 죽이지 않는다(D-2) — `warning`
 * variant 는 `active` 와 대비되는 색만 쓰고 채도를 낮추지 않는다. 이 디자인 판단은
 * 그대로 유지한다.
 */
export function TenantStatusBadge({ status }: { status: string }) {
  return status === 'ACTIVE' ? (
    <StatusBadge type="active">활성</StatusBadge>
  ) : (
    <StatusBadge type="warning">정지됨</StatusBadge>
  );
}
