import { StatusBadge } from '@/components/ui/status-badge';

/**
 * 운영자가 전역 계정을 비활성화한 멤버 표식(WD-313).
 *
 * 테넌트 상세 멤버 표의 「상태」는 이 워크스페이스 멤버십이라, 전역 비활성 계정도 「활성」으로 보였다. 멤버십 배지 **옆에**
 * 붙여 두 상태가 서로를 가리지 않게 한다. 문구는 웹 사용자 관리(`AccountDisabledBadge`, WD-3)와 맞추고, 운영자가 찾아
 * 풀어야 하는 상태라 warning 이다(`AccountStatusBadge` 와 같은 판단).
 */
export function AccountDisabledBadge() {
  return (
    <StatusBadge type="warning" title="운영자가 이 계정을 비활성화해 모든 워크스페이스에 로그인할 수 없습니다">
      계정 비활성(운영자)
    </StatusBadge>
  );
}
