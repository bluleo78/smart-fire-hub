import { InlineBanner } from '@/components/ui/inline-banner';

/**
 * 403 응답의 공통 표현. 토스트가 아니라 **본문 교체**다 — 재시도할 것이 없는 상황에서
 * 사라지는 토스트를 띄우면 사용자는 화면이 왜 비었는지 알 방법이 없다(설계서 §2).
 */
export function PermissionDeniedBanner() {
  return <InlineBanner variant="warning">이 작업을 수행할 권한이 없습니다.</InlineBanner>;
}
