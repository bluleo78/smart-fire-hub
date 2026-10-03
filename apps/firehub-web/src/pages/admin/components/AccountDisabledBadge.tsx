import { StatusBadge } from '@/components/ui/status-badge';

/**
 * 운영자가 전역 계정을 비활성화한 사용자 표식(WD-3).
 *
 * 사용자 관리의 활성 표시·스위치는 "이 워크스페이스 멤버십" 이라 전역 비활성 계정도 "활성" 으로 보였다. 이 배지를 멤버십 배지
 * **옆에** 붙여 두 상태가 서로를 가리지 않게 한다. 운영자가 찾아 풀어야 하는 상태라 회색이 아니라 warning 이다
 * (운영자 콘솔 `AccountStatusBadge` 와 같은 판단).
 */
export function AccountDisabledBadge() {
  return (
    <StatusBadge type="warning" title="운영자가 이 계정을 비활성화해 모든 워크스페이스에 로그인할 수 없습니다">
      계정 비활성(운영자)
    </StatusBadge>
  );
}
